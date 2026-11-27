package moe.qingu.youran.api.network.stack;

import moe.qingu.youran.api.network.Ipv6Address;
import moe.qingu.youran.api.network.packet.Icmpv6Message;
import moe.qingu.youran.api.network.packet.TcpSegment;

import java.io.ByteArrayOutputStream;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import java.util.concurrent.CompletableFuture;

/**
 * 一条 TCP 连接（RFC 9293）：三次握手、按序收发（乱序的段先存着，补齐了再交给应用）、超时重传（RFC 6298 算 RTO，
 * 指数退避，Karn 算法）、四次挥手与 TIME_WAIT、RST。最大段长 {@value #MSS}（对方 SYN 里的 MSS 更小时用对方的）。
 * <p>
 * 没做的（以后接公网时再补）：拥塞控制（慢启动等，现在对方窗口内有多少发多少）、窗口缩放、SACK、时间戳、延迟确认、
 * Nagle、快速重传。收到的数据马上交给应用，所以接收窗口固定为 65535。
 * <p>
 * 所有方法可以从任意线程调用；回调都在协议栈的事件循环线程上。
 */
public final class TcpConnection {
    public static final int MSS = 1220;
    static final int RCV_WND = 65535;
    static final long RTO_INITIAL = 1000;
    static final long RTO_MIN = 200;
    static final long RTO_MAX = 60_000;
    static final int MAX_SYN_RETRIES = 5;
    static final int MAX_RETRIES = 8;
    /** 2×MSL（MSL 取 15 秒） */
    static final long TIME_WAIT_MS = 30_000;
    /** 我方关闭后等对方 FIN 的上限（同 Linux 的 tcp_fin_timeout），过了就丢掉连接 */
    static final long FIN_WAIT_2_MS = 60_000;
    /** 应用写进来、还没发出去的数据上限；超过时连接以 LIMIT 中止 */
    static final int MAX_SEND_BUFFER = 4 * 1024 * 1024;
    private static final long MASK = 0xFFFFFFFFL;

    public enum State {
        SYN_SENT, SYN_RECEIVED, ESTABLISHED, FIN_WAIT_1, FIN_WAIT_2, CLOSE_WAIT, CLOSING, LAST_ACK, TIME_WAIT, CLOSED
    }

    /** 连接上的事件 */
    public interface Listener {
        /** 按顺序收到的数据 */
        void onData(TcpConnection connection, byte[] data);

        /** 对方发完了（收到 FIN）；这之后还可以继续发，发完调用 {@link #close()} */
        default void onEnd(TcpConnection connection) {
        }

        /** 连接结束：正常关闭时 {@code error} 为 null，否则是原因（超时、被重置等）。只调用一次 */
        default void onClosed(TcpConnection connection, NetworkException error) {
        }
    }

    private final NetStack stack;
    final NetStack.TcpKey key;
    private Listener listener;
    private ByteArrayOutputStream undelivered;
    private boolean undeliveredEnd;
    private State state = State.CLOSED;
    private CompletableFuture<TcpConnection> connectFuture;
    private NetStack.TcpAcceptor acceptor;

    // 发送
    private long iss;
    private long sndUna;
    private long sndNxt;
    private long sndWnd;
    private long sndWl1;
    private long sndWl2;
    private int sndMss = MSS;
    private final ByteQueue pending = new ByteQueue();
    private final ArrayDeque<Sent> unacked = new ArrayDeque<>();
    private boolean finRequested;
    private boolean finSent;
    private boolean finAcked;

    // 接收
    private long irs;
    private long rcvNxt;
    private final List<Sent> outOfOrder = new ArrayList<>();
    private int outOfOrderBytes;
    private boolean finReceived;

    // 计时
    /** 由 RTT 算出的 RTO（还没取样时为初始值）；实际用的是它左移退避次数（见 {@link #currentRto()}） */
    private long rtoBase = RTO_INITIAL;
    /** 连续超时的次数（退避），收到新数据的确认时清零（同 BSD 的 t_rxtshift） */
    private int backoff;
    private long srtt = -1;
    private long rttvar;
    private int retries;
    /** 零窗口后把没确认的数据放回待发（{@link #processAck}）：放回的部分到哪里为止，发它们时按重传算 */
    private boolean rewound;
    private long rewoundEnd;
    private EventLoop.Timer retransmitTimer;
    private EventLoop.Timer timeWaitTimer;
    private boolean closedNotified;

    // 统计（测试用）
    private int retransmissions;

    TcpConnection(NetStack stack, NetStack.TcpKey key, Listener listener) {
        this.stack = stack;
        this.key = key;
        this.listener = listener;
    }

    public Ipv6Address localAddress() {
        return key.localAddress;
    }

    public int localPort() {
        return key.localPort;
    }

    public Ipv6Address remoteAddress() {
        return key.remoteAddress;
    }

    public int remotePort() {
        return key.remotePort;
    }

    /** 连接所在协议栈的事件循环（回调都在它上面） */
    public EventLoop loop() {
        return stack.loop;
    }

    /** 当前状态（只在事件循环线程上读才准） */
    public State state() {
        return state;
    }

    /** 重传过几次（测试用） */
    public int retransmissions() {
        return retransmissions;
    }

    /** 设回调；之前到了还没交出去的数据马上交给它 */
    public void setListener(Listener l) {
        stack.run(() -> {
            listener = l;
            if (undelivered != null && undelivered.size() > 0) {
                byte[] d = undelivered.toByteArray();
                undelivered = null;
                deliver(d);
            }
            if (undeliveredEnd) {
                undeliveredEnd = false;
                deliverEnd();
            }
        });
    }

    /** 发数据（异步，放进发送缓冲） */
    public void write(byte[] data) {
        byte[] copy = data.clone();
        stack.run(() -> {
            if (finRequested || state == State.CLOSED || state == State.TIME_WAIT || state == State.FIN_WAIT_1
                    || state == State.FIN_WAIT_2 || state == State.CLOSING || state == State.LAST_ACK) {
                return;
            }
            if ((long) pending.size() + copy.length > MAX_SEND_BUFFER) {
                abortWith(new NetworkException(NetworkException.Reason.LIMIT, "发送缓冲已满"));
                return;
            }
            pending.add(copy);
            trySend();
        });
    }

    /** 发完缓冲里的数据后关闭（发 FIN） */
    public void close() {
        stack.run(() -> {
            switch (state) {
                case SYN_SENT:
                    destroy(new NetworkException(NetworkException.Reason.CLOSED, "连接未建立就关闭了"));
                    break;
                case SYN_RECEIVED:
                case ESTABLISHED:
                case CLOSE_WAIT:
                    finRequested = true;
                    trySend();
                    break;
                default:
                    break;
            }
        });
    }

    /** 立即中止（发 RST） */
    public void abort() {
        stack.run(() -> abortWith(null));
    }

    private void abortWith(NetworkException error) {
        if (state != State.CLOSED && state != State.SYN_SENT && state != State.TIME_WAIT) {
            sendSegment(sndNxt, 0, TcpSegment.RST, new byte[0], false);
        }
        destroy(error);
    }

    // ---- 打开 ----

    void openActive(CompletableFuture<TcpConnection> future) {
        connectFuture = future;
        iss = stack.random.nextInt() & MASK;
        sndUna = iss;
        sndNxt = (iss + 1) & MASK;
        state = State.SYN_SENT;
        Sent syn = new Sent(iss, new byte[0], TcpSegment.SYN);
        unacked.add(syn);
        transmit(syn);
        armTimer();
    }

    void openPassive(TcpSegment syn, NetStack.TcpAcceptor acceptor) {
        this.acceptor = acceptor;
        irs = syn.seq;
        rcvNxt = (irs + 1) & MASK;
        sndWnd = syn.window;
        sndWl1 = syn.seq;
        if (syn.mss > 0) {
            sndMss = Math.min(MSS, syn.mss);
        }
        iss = stack.random.nextInt() & MASK;
        sndUna = iss;
        sndNxt = (iss + 1) & MASK;
        state = State.SYN_RECEIVED;
        Sent synAck = new Sent(iss, new byte[0], TcpSegment.SYN);
        unacked.add(synAck);
        transmit(synAck);
        armTimer();
    }

    // ---- 收 ----

    void onSegment(TcpSegment s) {
        if (state == State.CLOSED) {
            return;
        }
        if (state == State.SYN_SENT) {
            onSegmentSynSent(s);
            return;
        }
        int segLen = s.sequenceLength();
        if (!acceptable(s.seq, segLen)) {
            if (!s.has(TcpSegment.RST)) {
                sendAck();
            }
            return;
        }
        if (s.has(TcpSegment.RST)) {
            if (s.seq == rcvNxt) {
                // 被动打开还没建立的连接被重置：安静地丢掉（RFC 9293 第 3.10.7.4 节）
                destroy(state == State.SYN_RECEIVED && connectFuture == null ? null
                        : new NetworkException(NetworkException.Reason.RESET, "连接被对方重置"));
            } else {
                sendAck(); // 挑战确认（RFC 5961）
            }
            return;
        }
        if (s.has(TcpSegment.SYN)) {
            sendAck(); // 同步后的 SYN：挑战确认（RFC 5961），丢掉
            return;
        }
        if (!s.has(TcpSegment.ACK)) {
            return;
        }
        if (state == State.SYN_RECEIVED) {
            if (gt(s.ack, sndUna) && le(s.ack, sndNxt)) {
                state = State.ESTABLISHED;
                sndWnd = s.window;
                sndWl1 = s.seq;
                sndWl2 = s.ack;
                processAck(s);
                established();
                if (state == State.CLOSED) {
                    return;
                }
            } else {
                sendSegment(s.ack, 0, TcpSegment.RST, new byte[0], false);
                return;
            }
        } else {
            if (gt(s.ack, sndNxt)) {
                sendAck();
                return;
            }
            processAck(s);
            if (state == State.CLOSED) {
                return;
            }
        }
        if (s.data.length > 0 || s.has(TcpSegment.FIN)) {
            onData(s.seq, s.data, s.has(TcpSegment.FIN));
        }
    }

    private void onSegmentSynSent(TcpSegment s) {
        if (s.has(TcpSegment.ACK) && (le(s.ack, iss) || gt(s.ack, sndNxt))) {
            if (!s.has(TcpSegment.RST)) {
                sendSegment(s.ack, 0, TcpSegment.RST, new byte[0], false);
            }
            return;
        }
        if (s.has(TcpSegment.RST)) {
            if (s.has(TcpSegment.ACK)) {
                destroy(new NetworkException(NetworkException.Reason.REFUSED, "对方拒绝连接"));
            }
            return;
        }
        if (!s.has(TcpSegment.SYN)) {
            return;
        }
        irs = s.seq;
        rcvNxt = (irs + 1) & MASK;
        if (s.mss > 0) {
            sndMss = Math.min(MSS, s.mss);
        }
        sndWnd = s.window;
        sndWl1 = s.seq;
        sndWl2 = s.ack;
        if (s.has(TcpSegment.ACK)) {
            state = State.ESTABLISHED;
            processAck(s);
            sendAck();
            established();
        } else {
            // 同时打开：重发 SYN（带 ACK）
            state = State.SYN_RECEIVED;
            transmit(unacked.peekFirst());
        }
    }

    private void established() {
        if (connectFuture != null) {
            CompletableFuture<TcpConnection> f = connectFuture;
            connectFuture = null;
            f.complete(this);
        }
        if (acceptor != null) {
            NetStack.TcpAcceptor a = acceptor;
            acceptor = null;
            try {
                a.accept(this);
            } catch (Throwable t) {
                NetStack.LOG.error("TCP 接受回调出错", t);
            }
        }
        trySend();
    }

    private boolean acceptable(long seq, int segLen) {
        long d = (seq - rcvNxt) & MASK;
        if (segLen == 0) {
            return d < RCV_WND;
        }
        long dEnd = (seq + segLen - 1 - rcvNxt) & MASK;
        return d < RCV_WND || dEnd < RCV_WND;
    }

    private void processAck(TcpSegment s) {
        if (gt(s.ack, sndUna)) {
            long now = stack.loop.nowMillis();
            // Karn 算法：这次确认的段里有重传过的就不取样；否则用最后一个被确认的段取样
            boolean anyRetransmitted = false;
            long newestSentAt = -1;
            Iterator<Sent> it = unacked.iterator();
            while (it.hasNext()) {
                Sent u = it.next();
                long end = (u.seq + u.length()) & MASK;
                if (le(end, s.ack)) {
                    anyRetransmitted |= u.retransmitted;
                    newestSentAt = u.sentAt;
                    if ((u.flags & TcpSegment.FIN) != 0) {
                        finAcked = true;
                    }
                    it.remove();
                } else {
                    if (gt(s.ack, u.seq)) {
                        u.trimFront((int) ((s.ack - u.seq) & MASK));
                    }
                    break;
                }
            }
            if (newestSentAt >= 0 && !anyRetransmitted) {
                sampleRtt(now - newestSentAt);
            }
            sndUna = s.ack;
            retries = 0;
            backoff = 0;
            cancelRetransmit();
            armTimer();
        }
        // 窗口更新（RFC 9293 第 3.10.7.4 节的 SND.WL1 / SND.WL2）
        long oldWnd = sndWnd;
        if (ge(s.ack, sndUna) && (lt(sndWl1, s.seq) || (sndWl1 == s.seq && le(sndWl2, s.ack)))) {
            sndWnd = s.window;
            sndWl1 = s.seq;
            sndWl2 = s.ack;
        }
        if (ge(s.ack, sndUna) && !unacked.isEmpty() && !finAcked) {
            if (sndWnd == 0) {
                // 零窗口：对方还在回确认，探测没确认不算失败，不因此断开（RFC 1122 第 4.2.2.17 节、RFC 9293 第 3.8.6.1 节）；
                // 探测间隔照旧按退避增长（封顶 RTO_MAX）
                retries = 0;
            } else if (oldWnd == 0) {
                // 窗口开了：从没确认的地方（零窗口探测的那 1 字节）起马上重发，不等重传计时器；
                // 没确认的数据放回待发的最前面，和后面的数据合成整段发（同 079 的网卡固件）
                cancelRetransmit();
                backoff = 0;
                retransmissions++;
                stack.tcpRetransmits++;
                if (!finSent) {
                    rewoundEnd = sndNxt;
                    rewound = true;
                    for (Iterator<Sent> back = unacked.descendingIterator(); back.hasNext(); ) {
                        pending.addFirst(back.next().data);
                    }
                    unacked.clear();
                    sndNxt = sndUna;
                } else {
                    Sent first = unacked.peekFirst();
                    first.retransmitted = true;
                    transmit(first);
                    armTimer();
                }
            }
        }
        if (finAcked) {
            switch (state) {
                case FIN_WAIT_1:
                    state = State.FIN_WAIT_2;
                    timeWaitTimer = stack.loop.schedule(() -> destroy(null), FIN_WAIT_2_MS);
                    break;
                case CLOSING:
                    enterTimeWait();
                    return;
                case LAST_ACK:
                    destroy(null);
                    return;
                default:
                    break;
            }
        }
        trySend();
    }

    private long currentRto() {
        return Math.min(RTO_MAX, rtoBase << backoff);
    }

    private void sampleRtt(long r) {
        // RFC 6298 第 2 节
        if (srtt < 0) {
            srtt = r;
            rttvar = r / 2;
        } else {
            rttvar = (3 * rttvar + Math.abs(srtt - r)) / 4;
            srtt = (7 * srtt + r) / 8;
        }
        rtoBase = Math.max(RTO_MIN, Math.min(RTO_MAX, srtt + Math.max(10, 4 * rttvar)));
    }

    private void onData(long seq, byte[] data, boolean fin) {
        if (state != State.ESTABLISHED && state != State.FIN_WAIT_1 && state != State.FIN_WAIT_2) {
            // 对方已经发过 FIN：后面不该再有数据，只回确认
            sendAck();
            return;
        }
        int off = (int) ((seq - rcvNxt) & MASK);
        if (off != 0 && off < RCV_WND) {
            // 乱序：先存着（同一段只存一次），回重复确认
            storeOutOfOrder(seq, data, fin);
            sendAck();
            return;
        }
        int skip = off == 0 ? 0 : (int) ((rcvNxt - seq) & MASK);
        if (skip > data.length) {
            sendAck();
            return;
        }
        accept(data, skip, fin);
        drainOutOfOrder();
        sendAck();
    }

    /** 交出 {@code data[skip..]}，处理 FIN */
    private void accept(byte[] data, int skip, boolean fin) {
        if (skip < data.length) {
            byte[] fresh = new byte[data.length - skip];
            System.arraycopy(data, skip, fresh, 0, fresh.length);
            rcvNxt = (rcvNxt + fresh.length) & MASK;
            deliver(fresh);
        }
        if (fin && !finReceived) {
            onFin();
        }
    }

    private void storeOutOfOrder(long seq, byte[] data, boolean fin) {
        for (Sent o : outOfOrder) {
            if (o.seq == seq && o.data.length >= data.length) {
                return;
            }
        }
        if (outOfOrderBytes + data.length > RCV_WND) {
            return;
        }
        outOfOrder.add(new Sent(seq, data, fin ? TcpSegment.FIN : 0));
        outOfOrderBytes += data.length;
    }

    private void drainOutOfOrder() {
        boolean progress = true;
        while (progress && !outOfOrder.isEmpty() && !finReceived) {
            progress = false;
            Iterator<Sent> it = outOfOrder.iterator();
            while (it.hasNext()) {
                Sent o = it.next();
                long end = (o.seq + o.data.length) & MASK;
                if (le(o.seq, rcvNxt)) {
                    it.remove();
                    outOfOrderBytes -= o.data.length;
                    if (ge(end, rcvNxt)) {
                        accept(o.data, (int) ((rcvNxt - o.seq) & MASK), (o.flags & TcpSegment.FIN) != 0);
                        progress = true;
                    }
                    break;
                }
            }
        }
        if (finReceived) {
            outOfOrder.clear();
            outOfOrderBytes = 0;
        }
    }

    private void onFin() {
        finReceived = true;
        rcvNxt = (rcvNxt + 1) & MASK;
        switch (state) {
            case ESTABLISHED:
                state = State.CLOSE_WAIT;
                break;
            case FIN_WAIT_1:
                if (finAcked) {
                    enterTimeWait();
                } else {
                    state = State.CLOSING;
                }
                break;
            case FIN_WAIT_2:
                enterTimeWait();
                break;
            default:
                break;
        }
        deliverEnd();
    }

    private void deliver(byte[] data) {
        if (listener == null) {
            if (undelivered == null) {
                undelivered = new ByteArrayOutputStream();
            }
            undelivered.write(data, 0, data.length);
            return;
        }
        try {
            listener.onData(this, data);
        } catch (Throwable t) {
            NetStack.LOG.error("TCP 数据回调出错", t);
        }
    }

    private void deliverEnd() {
        if (listener == null) {
            undeliveredEnd = true;
            return;
        }
        try {
            listener.onEnd(this);
        } catch (Throwable t) {
            NetStack.LOG.error("TCP 回调出错", t);
        }
    }

    void onIcmpError(int type, int code, Ipv6Address reporter) {
        if (state == State.SYN_SENT && (type == Icmpv6Message.DEST_UNREACHABLE || type == Icmpv6Message.TIME_EXCEEDED)) {
            destroy(new NetworkException(NetworkException.Reason.UNREACHABLE,
                    "目标不可达（ICMPv6 类型 " + type + " 代码 " + code + "，来自 " + reporter + "）"));
        }
    }

    // ---- 发 ----

    private void trySend() {
        if (state != State.ESTABLISHED && state != State.CLOSE_WAIT) {
            return;
        }
        while (pending.size() > 0) {
            long inFlight = (sndNxt - sndUna) & MASK;
            long wnd = sndWnd;
            if (wnd == 0 && inFlight == 0) {
                wnd = 1; // 零窗口探测：发 1 字节，按重传计时器重发
            }
            long can = wnd - inFlight;
            if (can <= 0) {
                break;
            }
            int n = (int) Math.min(Math.min(sndMss, pending.size()), can);
            Sent s = new Sent(sndNxt, pending.take(n), 0);
            if (rewound) {
                // 零窗口后重发的那部分：Karn 算法不拿它取样
                s.retransmitted = lt(s.seq, rewoundEnd);
            }
            sndNxt = (sndNxt + n) & MASK;
            if (rewound && ge(sndNxt, rewoundEnd)) {
                rewound = false;
            }
            unacked.add(s);
            transmit(s);
        }
        if (pending.size() == 0 && finRequested && !finSent) {
            Sent fin = new Sent(sndNxt, new byte[0], TcpSegment.FIN);
            sndNxt = (sndNxt + 1) & MASK;
            finSent = true;
            state = state == State.ESTABLISHED ? State.FIN_WAIT_1 : State.LAST_ACK;
            unacked.add(fin);
            transmit(fin);
        }
        armTimer();
    }

    private void transmit(Sent s) {
        s.sentAt = stack.loop.nowMillis();
        boolean syn = (s.flags & TcpSegment.SYN) != 0;
        boolean withAck = state != State.SYN_SENT;
        int flags = s.flags | (withAck ? TcpSegment.ACK : 0) | (s.data.length > 0 ? TcpSegment.PSH : 0);
        sendSegment(s.seq, withAck ? rcvNxt : 0, flags, s.data, syn);
    }

    private void sendAck() {
        sendSegment(sndNxt, rcvNxt, TcpSegment.ACK, new byte[0], false);
    }

    private void sendSegment(long seq, long ack, int flags, byte[] data, boolean mssOption) {
        TcpSegment seg = new TcpSegment(key.localPort, key.remotePort, seq, ack, flags, RCV_WND, mssOption ? MSS : -1, data);
        stack.send(seg.toPacket(key.localAddress, key.remoteAddress));
    }

    private void armTimer() {
        if (retransmitTimer == null && !unacked.isEmpty() && state != State.CLOSED) {
            retransmitTimer = stack.loop.schedule(this::onRetransmitTimeout, currentRto());
        }
    }

    private void cancelRetransmit() {
        if (retransmitTimer != null) {
            retransmitTimer.cancel();
            retransmitTimer = null;
        }
    }

    private void onRetransmitTimeout() {
        retransmitTimer = null;
        if (state == State.CLOSED || unacked.isEmpty()) {
            return;
        }
        boolean handshake = state == State.SYN_SENT || state == State.SYN_RECEIVED;
        if (++retries > (handshake ? MAX_SYN_RETRIES : MAX_RETRIES)) {
            destroy(new NetworkException(NetworkException.Reason.TIMEOUT, handshake ? "连接超时" : "重传多次仍没有确认"));
            return;
        }
        backoff = Math.min(backoff + 1, 16);
        Sent first = unacked.peekFirst();
        first.retransmitted = true;
        retransmissions++;
        stack.tcpRetransmits++;
        transmit(first);
        armTimer();
    }

    private void enterTimeWait() {
        state = State.TIME_WAIT;
        cancelRetransmit();
        if (timeWaitTimer != null) {
            timeWaitTimer.cancel();
        }
        unacked.clear();
        notifyClosed(null);
        timeWaitTimer = stack.loop.schedule(() -> destroy(null), TIME_WAIT_MS);
    }

    /** 彻底结束：从协议栈里拿掉，通知应用（只通知一次） */
    void destroy(NetworkException error) {
        if (state == State.CLOSED && closedNotified) {
            return;
        }
        state = State.CLOSED;
        cancelRetransmit();
        if (timeWaitTimer != null) {
            timeWaitTimer.cancel();
            timeWaitTimer = null;
        }
        unacked.clear();
        outOfOrder.clear();
        stack.removeConnection(this);
        if (connectFuture != null) {
            CompletableFuture<TcpConnection> f = connectFuture;
            connectFuture = null;
            f.completeExceptionally(error != null ? error : new NetworkException(NetworkException.Reason.CLOSED, "连接已关闭"));
        }
        notifyClosed(error);
    }

    private void notifyClosed(NetworkException error) {
        if (closedNotified) {
            return;
        }
        closedNotified = true;
        if (listener != null && acceptor == null) {
            try {
                listener.onClosed(this, error);
            } catch (Throwable t) {
                NetStack.LOG.error("TCP 回调出错", t);
            }
        }
    }

    // ---- 序号比较（模 2^32） ----

    static boolean lt(long a, long b) {
        return (int) (a - b) < 0;
    }

    static boolean le(long a, long b) {
        return (int) (a - b) <= 0;
    }

    static boolean gt(long a, long b) {
        return (int) (a - b) > 0;
    }

    static boolean ge(long a, long b) {
        return (int) (a - b) >= 0;
    }

    /** 发出去还没确认的段，或收到的乱序段 */
    private static final class Sent {
        long seq;
        byte[] data;
        final int flags;
        long sentAt;
        boolean retransmitted;

        Sent(long seq, byte[] data, int flags) {
            this.seq = seq;
            this.data = data;
            this.flags = flags;
        }

        int length() {
            return data.length + ((flags & TcpSegment.SYN) != 0 ? 1 : 0) + ((flags & TcpSegment.FIN) != 0 ? 1 : 0);
        }

        /** 对方只确认了前一部分：去掉已确认的数据（SYN 段不会走到这里） */
        void trimFront(int n) {
            int k = Math.min(n, data.length);
            byte[] d = new byte[data.length - k];
            System.arraycopy(data, k, d, 0, d.length);
            data = d;
            seq = (seq + k) & MASK;
        }
    }

    /** 待发数据 */
    private static final class ByteQueue {
        private final ArrayDeque<byte[]> chunks = new ArrayDeque<>();
        private int head;
        private int size;

        void add(byte[] b) {
            if (b.length > 0) {
                chunks.add(b);
                size += b.length;
            }
        }

        int size() {
            return size;
        }

        /** 放回最前面（零窗口后重发没确认的数据） */
        void addFirst(byte[] b) {
            if (b.length == 0) {
                return;
            }
            if (head > 0) {
                byte[] c = chunks.pollFirst();
                chunks.addFirst(java.util.Arrays.copyOfRange(c, head, c.length));
                head = 0;
            }
            chunks.addFirst(b);
            size += b.length;
        }

        byte[] take(int n) {
            byte[] out = new byte[n];
            int got = 0;
            while (got < n) {
                byte[] c = chunks.peekFirst();
                int k = Math.min(n - got, c.length - head);
                System.arraycopy(c, head, out, got, k);
                got += k;
                head += k;
                if (head == c.length) {
                    chunks.pollFirst();
                    head = 0;
                }
            }
            size -= n;
            return out;
        }
    }
}
