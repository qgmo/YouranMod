package moe.qingu.youran.api.network.stack;

import moe.qingu.youran.api.network.Ipv6Address;
import moe.qingu.youran.api.network.packet.Icmpv6Message;
import moe.qingu.youran.api.network.packet.Ipv6Packet;
import moe.qingu.youran.api.network.packet.MalformedPacketException;
import moe.qingu.youran.api.network.packet.TcpSegment;
import moe.qingu.youran.api.network.packet.UdpDatagram;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.function.Consumer;

/**
 * 一台主机的协议栈：IPv6、ICMPv6（回显、差错）、UDP、TCP。客户端（家具电脑等设备）与服务端（机房里的服务）共用这一份。
 * <p>
 * <b>线程</b>：所有状态只在 {@link EventLoop} 的线程上改。公开方法可以从任意线程调用（会转进事件循环）；
 * future 与回调都在事件循环线程上完成，调用方要动别的线程的东西（如 Minecraft 主线程）时自己转过去。
 * 全部异步：发出就返回，不等回应。
 * <p>
 * 收包：外面把原始 IPv6 包交给 {@link #receive(byte[])}；发包：交给构造时给的 {@link PacketOutput}。
 * 目的地址不是本机地址的包丢掉；坏包（长度、版本、校验和不对）丢掉。
 */
public final class NetStack {
    static final Logger LOG = LogManager.getLogger("YouranMod");
    /** 临时端口范围（RFC 6335） */
    static final int EPHEMERAL_MIN = 49152;
    static final int EPHEMERAL_MAX = 65535;

    final EventLoop loop;
    private final PacketOutput output;
    final Random random;
    private final Set<Ipv6Address> addresses = new LinkedHashSet<>();
    private final Map<PortKey, UdpSocket> udpSockets = new HashMap<>();
    private final Map<TcpKey, TcpConnection> tcpConnections = new HashMap<>();
    private final Map<PortKey, TcpAcceptor> tcpListeners = new HashMap<>();
    private final Map<Integer, PendingPing> pings = new HashMap<>();
    private final int pingIdentifier;
    private int pingSequence;
    private int maxConnections = 1024;
    private boolean closed;
    /** 测试与统计用 */
    private long received;
    private long dropped;
    /** 所有 TCP 连接重传过的段数 */
    long tcpRetransmits;

    public NetStack(EventLoop loop, PacketOutput output, Random random) {
        this.loop = loop;
        this.output = output;
        this.random = random;
        this.pingIdentifier = random.nextInt(0x10000);
    }

    public EventLoop loop() {
        return loop;
    }

    /** TCP 连接数上限（含握手中、TIME_WAIT 的）；超过时新的 SYN 回 RST，主动连接失败 */
    public void setMaxConnections(int max) {
        run(() -> maxConnections = max);
    }

    public void addAddress(Ipv6Address address) {
        run(() -> addresses.add(address));
    }

    public void removeAddress(Ipv6Address address) {
        run(() -> addresses.remove(address));
    }

    /** 第一个地址（主动发包时不指定源地址就用它）；没有地址时为 null。只在事件循环线程上调用 */
    public Ipv6Address primaryAddress() {
        return addresses.isEmpty() ? null : addresses.iterator().next();
    }

    public boolean hasAddress(Ipv6Address a) {
        return addresses.contains(a);
    }

    /** 关掉：所有连接以 CLOSED 结束，未完成的 ping 以超时结束，以后收到的包都丢掉 */
    public void close() {
        run(() -> {
            if (closed) {
                return;
            }
            closed = true;
            for (TcpConnection c : new ArrayList<>(tcpConnections.values())) {
                c.destroy(new NetworkException(NetworkException.Reason.CLOSED, "协议栈已关"));
            }
            tcpConnections.clear();
            tcpListeners.clear();
            for (UdpSocket s : new ArrayList<>(udpSockets.values())) {
                s.closeQuietly();
            }
            udpSockets.clear();
            for (PendingPing p : new ArrayList<>(pings.values())) {
                p.finish(new PingResult(PingResult.Status.TIMEOUT, null, 0, 0, 0));
            }
            pings.clear();
        });
    }

    public boolean isClosed() {
        return closed;
    }

    public long receivedCount() {
        return received;
    }

    public long droppedCount() {
        return dropped;
    }

    /** 这个协议栈上所有 TCP 连接一共重传过几次（统计用） */
    public long tcpRetransmissions() {
        return tcpRetransmits;
    }

    void run(Runnable r) {
        if (loop.inEventLoop()) {
            r.run();
        } else {
            loop.execute(r);
        }
    }

    // ---- 收发 ----

    /** 收到一个原始 IPv6 包（任意线程都可以调用） */
    public void receive(byte[] raw) {
        run(() -> receiveInLoop(raw));
    }

    private void receiveInLoop(byte[] raw) {
        if (closed) {
            return;
        }
        received++;
        Ipv6Packet p;
        try {
            p = Ipv6Packet.parse(raw);
        } catch (MalformedPacketException e) {
            dropped++;
            return;
        }
        if (!addresses.contains(p.destination)) {
            dropped++;
            return;
        }
        try {
            switch (p.nextHeader) {
                case Ipv6Packet.PROTO_ICMPV6:
                    onIcmp(p, Icmpv6Message.parse(p));
                    break;
                case Ipv6Packet.PROTO_UDP:
                    onUdp(p, UdpDatagram.parse(p), raw);
                    break;
                case Ipv6Packet.PROTO_TCP:
                    onTcp(p, TcpSegment.parse(p));
                    break;
                default:
                    // 不认识的下一首部（含扩展首部）：参数问题，指针指向下一首部字段（RFC 4443 第 3.4 节）
                    sendError(p.destination, raw, Icmpv6Message.PARAMETER_PROBLEM, Icmpv6Message.PARAM_UNKNOWN_NEXT_HEADER, 6);
                    break;
            }
        } catch (MalformedPacketException e) {
            dropped++;
        }
    }

    /** 发一个包；源地址必须是本机地址。只在事件循环线程上调用 */
    void send(Ipv6Packet p) {
        if (closed) {
            return;
        }
        output.send(p.encode());
    }

    void sendError(Ipv6Address from, byte[] invoking, int type, int code, long word) {
        if (!Icmpv6Message.mayReplyWithError(invoking)) {
            return;
        }
        Ipv6Address dst = Ipv6Packet.sourceOf(invoking);
        send(Icmpv6Message.error(type, code, word, invoking).toPacket(from, dst));
    }

    // ---- ICMPv6 ----

    private void onIcmp(Ipv6Packet p, Icmpv6Message m) {
        switch (m.type) {
            case Icmpv6Message.ECHO_REQUEST:
                send(new Icmpv6Message(Icmpv6Message.ECHO_REPLY, 0, m.body).toPacket(p.destination, p.source));
                break;
            case Icmpv6Message.ECHO_REPLY: {
                int key = (m.echoIdentifier() << 16) | m.echoSequence();
                PendingPing pp = pings.get(key);
                if (pp != null && pp.target.equals(p.source)) {
                    pp.finish(new PingResult(PingResult.Status.REPLY, p.source, 0, loop.nowMillis() - pp.sentAt, m.body.length - 4));
                }
                break;
            }
            case Icmpv6Message.DEST_UNREACHABLE:
            case Icmpv6Message.TIME_EXCEEDED:
            case Icmpv6Message.PARAMETER_PROBLEM:
            case Icmpv6Message.PACKET_TOO_BIG:
                onIcmpError(p, m);
                break;
            default:
                break;
        }
    }

    /** 差错：按里面附带的原始包找到是谁发的，通知它 */
    private void onIcmpError(Ipv6Packet p, Icmpv6Message m) {
        byte[] inv = m.invokingPacket();
        if (inv == null) {
            return;
        }
        Ipv6Address origSrc = Ipv6Packet.sourceOf(inv);
        Ipv6Address origDst = Ipv6Packet.destinationOf(inv);
        if (!addresses.contains(origSrc)) {
            return;
        }
        int nh = inv[6] & 0xFF;
        int off = Ipv6Packet.HEADER;
        if (nh == Ipv6Packet.PROTO_ICMPV6 && inv.length >= off + 8) {
            if ((inv[off] & 0xFF) == Icmpv6Message.ECHO_REQUEST) {
                int id = ((inv[off + 4] & 0xFF) << 8) | (inv[off + 5] & 0xFF);
                int seq = ((inv[off + 6] & 0xFF) << 8) | (inv[off + 7] & 0xFF);
                PendingPing pp = pings.get((id << 16) | seq);
                if (pp != null && pp.target.equals(origDst)) {
                    PingResult.Status st = m.type == Icmpv6Message.TIME_EXCEEDED ? PingResult.Status.TIME_EXCEEDED : PingResult.Status.UNREACHABLE;
                    pp.finish(new PingResult(st, p.source, m.code, loop.nowMillis() - pp.sentAt, 0));
                }
            }
        } else if (nh == Ipv6Packet.PROTO_UDP && inv.length >= off + 8) {
            int sport = ((inv[off] & 0xFF) << 8) | (inv[off + 1] & 0xFF);
            UdpSocket s = udpSockets.get(udpKey(origSrc, sport));
            if (s == null) {
                s = udpSockets.get(udpKey(null, sport));
            }
            if (s != null) {
                s.onError(origDst, m.type, m.code, p.source);
            }
        } else if (nh == Ipv6Packet.PROTO_TCP && inv.length >= off + 4) {
            int sport = ((inv[off] & 0xFF) << 8) | (inv[off + 1] & 0xFF);
            int dport = ((inv[off + 2] & 0xFF) << 8) | (inv[off + 3] & 0xFF);
            TcpConnection c = tcpConnections.get(new TcpKey(origSrc, sport, origDst, dport));
            if (c != null) {
                c.onIcmpError(m.type, m.code, p.source);
            }
        }
    }

    /**
     * ping：发一个回显请求，{@code timeoutMillis} 内等回应。
     *
     * @param source      源地址（null 为本机第一个地址）
     * @param dataLength  回显数据长度（0–1232）
     */
    public CompletableFuture<PingResult> ping(Ipv6Address source, Ipv6Address target, int dataLength, long timeoutMillis) {
        CompletableFuture<PingResult> f = new CompletableFuture<>();
        run(() -> {
            Ipv6Address src = source != null ? source : primaryAddress();
            if (closed || src == null || !addresses.contains(src)) {
                f.completeExceptionally(new NetworkException(NetworkException.Reason.CLOSED, "没有可用的源地址"));
                return;
            }
            if (dataLength < 0 || dataLength > Ipv6Packet.MAX_PAYLOAD - 8) {
                f.completeExceptionally(new NetworkException(NetworkException.Reason.LIMIT, "回显数据最多 " + (Ipv6Packet.MAX_PAYLOAD - 8) + " 字节"));
                return;
            }
            int seq = pingSequence = (pingSequence + 1) & 0xFFFF;
            int key = (pingIdentifier << 16) | seq;
            byte[] data = new byte[dataLength];
            for (int i = 0; i < data.length; i++) {
                data[i] = (byte) i;
            }
            PendingPing pp = new PendingPing(key, target, loop.nowMillis(), f);
            PendingPing old = pings.put(key, pp);
            if (old != null) {
                old.finish(new PingResult(PingResult.Status.TIMEOUT, null, 0, 0, 0));
                pings.put(key, pp);
            }
            pp.timer = loop.schedule(() -> pp.finish(new PingResult(PingResult.Status.TIMEOUT, null, 0, timeoutMillis, 0)), timeoutMillis);
            send(Icmpv6Message.echo(Icmpv6Message.ECHO_REQUEST, pingIdentifier, seq, data).toPacket(src, target));
        });
        return f;
    }

    private final class PendingPing {
        final int key;
        final Ipv6Address target;
        final long sentAt;
        final CompletableFuture<PingResult> future;
        EventLoop.Timer timer;

        PendingPing(int key, Ipv6Address target, long sentAt, CompletableFuture<PingResult> future) {
            this.key = key;
            this.target = target;
            this.sentAt = sentAt;
            this.future = future;
        }

        void finish(PingResult r) {
            if (pings.get(key) == this) {
                pings.remove(key);
            }
            if (timer != null) {
                timer.cancel();
            }
            future.complete(r);
        }
    }

    // ---- UDP ----

    static PortKey udpKey(Ipv6Address local, int port) {
        return new PortKey(local, port);
    }

    /** （本机地址或 null 表示所有地址，端口） */
    static final class PortKey {
        final Ipv6Address address;
        final int port;

        PortKey(Ipv6Address address, int port) {
            this.address = address;
            this.port = port;
        }

        @Override
        public boolean equals(Object o) {
            if (!(o instanceof PortKey)) {
                return false;
            }
            PortKey k = (PortKey) o;
            return k.port == port && (address == null ? k.address == null : address.equals(k.address));
        }

        @Override
        public int hashCode() {
            return (address == null ? 0 : address.hashCode()) * 31 + port;
        }
    }

    /**
     * 打开一个 UDP 端口。
     *
     * @param local   本机地址；null 表示本机所有地址
     * @param port    端口；0 表示随便分一个临时端口
     * @param handler 收到数据报时在事件循环线程上调用
     */
    public CompletableFuture<UdpSocket> openUdp(Ipv6Address local, int port, UdpSocket.Handler handler) {
        CompletableFuture<UdpSocket> f = new CompletableFuture<>();
        run(() -> {
            if (closed) {
                f.completeExceptionally(new NetworkException(NetworkException.Reason.CLOSED, "协议栈已关"));
                return;
            }
            if (local != null && !addresses.contains(local)) {
                f.completeExceptionally(new NetworkException(NetworkException.Reason.CLOSED, "不是本机地址：" + local));
                return;
            }
            int p = port;
            if (p == 0) {
                p = pickUdpPort(local);
                if (p < 0) {
                    f.completeExceptionally(new NetworkException(NetworkException.Reason.LIMIT, "没有空闲的临时端口"));
                    return;
                }
            } else if (udpSockets.containsKey(udpKey(local, p))) {
                f.completeExceptionally(new NetworkException(NetworkException.Reason.LIMIT, "端口已被占用：" + p));
                return;
            }
            UdpSocket s = new UdpSocket(this, local, p, handler);
            udpSockets.put(udpKey(local, p), s);
            f.complete(s);
        });
        return f;
    }

    private int pickUdpPort(Ipv6Address local) {
        int range = EPHEMERAL_MAX - EPHEMERAL_MIN + 1;
        int start = random.nextInt(range);
        for (int i = 0; i < range; i++) {
            int p = EPHEMERAL_MIN + (start + i) % range;
            if (!udpSockets.containsKey(udpKey(local, p)) && !udpSockets.containsKey(udpKey(null, p))) {
                return p;
            }
        }
        return -1;
    }

    void unbindUdp(UdpSocket s) {
        PortKey k = udpKey(s.localAddress(), s.port());
        if (udpSockets.get(k) == s) {
            udpSockets.remove(k);
        }
    }

    private void onUdp(Ipv6Packet p, UdpDatagram d, byte[] raw) {
        UdpSocket s = udpSockets.get(udpKey(p.destination, d.destinationPort));
        if (s == null) {
            s = udpSockets.get(udpKey(null, d.destinationPort));
        }
        if (s == null) {
            sendError(p.destination, raw, Icmpv6Message.DEST_UNREACHABLE, Icmpv6Message.UNREACH_PORT, 0);
            return;
        }
        s.deliver(p.source, d.sourcePort, p.destination, d.data);
    }

    // ---- TCP ----

    /** 监听 TCP 端口；{@code local} 为 null 表示本机所有地址 */
    public void listen(Ipv6Address local, int port, TcpAcceptor acceptor) {
        run(() -> tcpListeners.put(udpKey(local, port), acceptor));
    }

    public void stopListening(Ipv6Address local, int port) {
        run(() -> tcpListeners.remove(udpKey(local, port)));
    }

    /**
     * 主动连接。握手完成时 future 给出连接；对方拒绝、不可达、超时时以 {@link NetworkException} 异常结束。
     *
     * @param source   源地址（null 为本机第一个地址）
     * @param listener 连接上以后的回调（可以为 null，之后用 {@link TcpConnection#setListener} 设）
     */
    public CompletableFuture<TcpConnection> connect(Ipv6Address source, Ipv6Address remote, int port, TcpConnection.Listener listener) {
        CompletableFuture<TcpConnection> f = new CompletableFuture<>();
        run(() -> {
            Ipv6Address src = source != null ? source : primaryAddress();
            if (closed || src == null || !addresses.contains(src)) {
                f.completeExceptionally(new NetworkException(NetworkException.Reason.CLOSED, "没有可用的源地址"));
                return;
            }
            if (tcpConnections.size() >= maxConnections) {
                f.completeExceptionally(new NetworkException(NetworkException.Reason.LIMIT, "TCP 连接数已满"));
                return;
            }
            int range = EPHEMERAL_MAX - EPHEMERAL_MIN + 1;
            int start = random.nextInt(range);
            TcpKey key = null;
            for (int i = 0; i < range; i++) {
                TcpKey k = new TcpKey(src, EPHEMERAL_MIN + (start + i) % range, remote, port);
                if (!tcpConnections.containsKey(k)) {
                    key = k;
                    break;
                }
            }
            if (key == null) {
                f.completeExceptionally(new NetworkException(NetworkException.Reason.LIMIT, "没有空闲的临时端口"));
                return;
            }
            TcpConnection c = new TcpConnection(this, key, listener);
            tcpConnections.put(key, c);
            c.openActive(f);
        });
        return f;
    }

    void removeConnection(TcpConnection c) {
        if (tcpConnections.get(c.key) == c) {
            tcpConnections.remove(c.key);
        }
    }

    /** 当前连接数（测试用；在事件循环线程上调用） */
    public int connectionCount() {
        return tcpConnections.size();
    }

    private void onTcp(Ipv6Packet p, TcpSegment s) {
        TcpKey key = new TcpKey(p.destination, s.destinationPort, p.source, s.sourcePort);
        TcpConnection c = tcpConnections.get(key);
        if (c != null) {
            c.onSegment(s);
            return;
        }
        TcpAcceptor acceptor = tcpListeners.get(udpKey(p.destination, s.destinationPort));
        if (acceptor == null) {
            acceptor = tcpListeners.get(udpKey(null, s.destinationPort));
        }
        if (acceptor != null && s.has(TcpSegment.SYN) && !s.has(TcpSegment.ACK) && !s.has(TcpSegment.RST)) {
            if (tcpConnections.size() >= maxConnections) {
                sendReset(key, s);
                return;
            }
            c = new TcpConnection(this, key, null);
            tcpConnections.put(key, c);
            c.openPassive(s, acceptor);
            return;
        }
        sendReset(key, s);
    }

    /** 没有连接时的回应（RFC 9293 第 3.10.7.1 节）：RST 不回；带 ACK 的回 seq=ack 的 RST；否则回 RST+ACK */
    void sendReset(TcpKey key, TcpSegment s) {
        if (s.has(TcpSegment.RST)) {
            return;
        }
        TcpSegment rst;
        if (s.has(TcpSegment.ACK)) {
            rst = new TcpSegment(key.localPort, key.remotePort, s.ack, 0, TcpSegment.RST, 0, -1, new byte[0]);
        } else {
            rst = new TcpSegment(key.localPort, key.remotePort, 0, s.seq + s.sequenceLength(), TcpSegment.RST | TcpSegment.ACK, 0, -1, new byte[0]);
        }
        send(rst.toPacket(key.localAddress, key.remoteAddress));
    }

    /** 收到连接请求时调用 */
    @FunctionalInterface
    public interface TcpAcceptor {
        /** 握手完成后在事件循环线程上调用；在这里给连接设 {@link TcpConnection.Listener} */
        void accept(TcpConnection connection);
    }

    static final class TcpKey {
        final Ipv6Address localAddress;
        final int localPort;
        final Ipv6Address remoteAddress;
        final int remotePort;

        TcpKey(Ipv6Address localAddress, int localPort, Ipv6Address remoteAddress, int remotePort) {
            this.localAddress = localAddress;
            this.localPort = localPort;
            this.remoteAddress = remoteAddress;
            this.remotePort = remotePort;
        }

        @Override
        public boolean equals(Object o) {
            if (!(o instanceof TcpKey)) {
                return false;
            }
            TcpKey k = (TcpKey) o;
            return k.localPort == localPort && k.remotePort == remotePort
                    && k.localAddress.equals(localAddress) && k.remoteAddress.equals(remoteAddress);
        }

        @Override
        public int hashCode() {
            return ((localAddress.hashCode() * 31 + remoteAddress.hashCode()) * 31 + localPort) * 31 + remotePort;
        }
    }

    /** 回调里抛出的异常记下来，不让它弄坏协议栈 */
    static <T> void safely(Consumer<T> c, T value) {
        if (c == null) {
            return;
        }
        try {
            c.accept(value);
        } catch (Throwable t) {
            LOG.error("网络回调出错", t);
        }
    }

    static void safely(Runnable r) {
        if (r == null) {
            return;
        }
        try {
            r.run();
        } catch (Throwable t) {
            LOG.error("网络回调出错", t);
        }
    }

    static List<byte[]> split(byte[] data, int max) {
        List<byte[]> list = new ArrayList<>();
        for (int i = 0; i < data.length; i += max) {
            int n = Math.min(max, data.length - i);
            byte[] b = new byte[n];
            System.arraycopy(data, i, b, 0, n);
            list.add(b);
        }
        return list;
    }
}
