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
import java.util.LinkedHashMap;
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
 * <b>网口</b>（{@link Interface}，服主 11-27：一个协议栈、多个网口，同真实系统）：每个网口有编号、名字、自己的地址、自己的出口
 * （{@link PacketOutput}）、DNS 与 NTP 配置，随时可以增减。包从源地址所在的那个网口发出去；收包时目的地址要是收到它的那个网口的地址
 * （强主机模式）。机房主机这类只有一个网口的，用 {@link #NetStack(EventLoop, PacketOutput, Random)}（建好一个网口，{@link #addAddress} 加在它上面）。
 * <p>
 * <b>源地址怎么选</b>（RFC 6724 的最简版）：套接字绑了网口（{@code …Via}）就用那个网口的地址；绑了源地址就用它；都没绑时用默认网口
 * （{@link #setDefaultInterface}）的地址。网口去掉时，绑在它上面（源地址是它的地址）的 TCP 连接以 {@link NetworkException.Reason#CLOSED} 报错、
 * UDP 套接字关掉、还没回来的 ping 报错。
 * <p>
 * 收包：外面把原始 IPv6 包交给 {@link #receive(byte[])}（不分网口）或 {@link Interface#receive(byte[])}。
 * 目的地址不是本机地址的包丢掉；坏包（长度、版本、校验和不对）丢掉。
 */
public final class NetStack {
    static final Logger LOG = LogManager.getLogger("YouranMod");
    /** 临时端口范围（RFC 6335） */
    static final int EPHEMERAL_MIN = 49152;
    static final int EPHEMERAL_MAX = 65535;

    final EventLoop loop;
    final Random random;
    /** 网口（按加进来的先后）；只在事件循环线程上改 */
    private final List<Interface> interfaces = new ArrayList<>();
    /** 本机地址 → 它所在的网口；只在事件循环线程上改 */
    private final Map<Ipv6Address, Interface> owners = new LinkedHashMap<>();
    /** 不绑网口、不绑源地址时用的网口 */
    private Interface defaultInterface;
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

    /** 只有一个网口的协议栈（机房主机等）：建好 0 号网口，发出的包都交给 {@code output}，它就是默认网口 */
    public NetStack(EventLoop loop, PacketOutput output, Random random) {
        this(loop, random);
        Interface i = new Interface(this, 0, "default", output);
        interfaces.add(i);
        defaultInterface = i;
    }

    /** 还没有网口的协议栈（玩家客户端：网口由服务器增减，见 {@link #addInterface}） */
    public NetStack(EventLoop loop, Random random) {
        this.loop = loop;
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

    /** 给默认网口加一个地址（只有一个网口的协议栈用）；没有默认网口时什么都不做 */
    public void addAddress(Ipv6Address address) {
        run(() -> {
            if (defaultInterface != null) {
                defaultInterface.addInLoop(address);
            }
        });
    }

    public void removeAddress(Ipv6Address address) {
        run(() -> {
            Interface i = owners.get(address);
            if (i != null) {
                i.removeInLoop(address);
            }
        });
    }

    /** 默认网口的第一个地址（主动发包时不绑网口、不指定源地址就用它）；没有时为 null。只在事件循环线程上调用 */
    public Ipv6Address primaryAddress() {
        return defaultInterface == null || defaultInterface.removed ? null : defaultInterface.first();
    }

    public boolean hasAddress(Ipv6Address a) {
        return owners.containsKey(a);
    }

    // ---- 网口 ----

    /**
     * 网口：协议栈接到一段链路上的那一头（玩家客户端上是“玩家自己的”“接着的电脑”这样的网口，机房主机只有一个）。
     * 编号、名字、出口建的时候定；地址、DNS 与 NTP 配置可以改。去掉以后（{@link #isRemoved()}）不再收发。
     */
    public static final class Interface {
        private final NetStack stack;
        private final int id;
        private final String name;
        private final PacketOutput output;
        /** 只在事件循环线程上改 */
        private final Set<Ipv6Address> addresses = new LinkedHashSet<>();
        private volatile Ipv6Address first;
        private volatile Ipv6Address dnsServer;
        private volatile Ipv6Address ntpServer;
        volatile boolean removed;

        Interface(NetStack stack, int id, String name, PacketOutput output) {
            this.stack = stack;
            this.id = id;
            this.name = name;
            this.output = output;
        }

        public NetStack stack() {
            return stack;
        }

        public int id() {
            return id;
        }

        public String name() {
            return name;
        }

        /** 第一个地址（这个网口发包用的源地址）；没有为 null。任意线程 */
        public Ipv6Address address() {
            return first;
        }

        /** 这个网口用的 DNS 服务器（递归解析器）；没配为 null。任意线程 */
        public Ipv6Address dnsServer() {
            return dnsServer;
        }

        /** 这个网口用的 NTP 服务器；没配为 null。任意线程 */
        public Ipv6Address ntpServer() {
            return ntpServer;
        }

        /** 任意线程 */
        public void setDnsServer(Ipv6Address a) {
            dnsServer = a;
        }

        /** 任意线程 */
        public void setNtpServer(Ipv6Address a) {
            ntpServer = a;
        }

        /** 任意线程 */
        public boolean isRemoved() {
            return removed;
        }

        /** 加一个地址。任意线程 */
        public void addAddress(Ipv6Address a) {
            stack.run(() -> addInLoop(a));
        }

        /** 去掉一个地址（绑在它上面的连接不动，同 {@link NetStack#removeAddress}）。任意线程 */
        public void removeAddress(Ipv6Address a) {
            stack.run(() -> removeInLoop(a));
        }

        /** 这个网口收到一个原始 IPv6 包（目的地址要是这个网口的地址，否则丢掉）。任意线程 */
        public void receive(byte[] raw) {
            stack.run(() -> stack.receiveInLoop(this, raw));
        }

        boolean has(Ipv6Address a) {
            return addresses.contains(a);
        }

        Ipv6Address first() {
            return first;
        }

        void addInLoop(Ipv6Address a) {
            if (removed || a == null || stack.owners.containsKey(a)) {
                return;
            }
            addresses.add(a);
            stack.owners.put(a, this);
            if (first == null) {
                first = a;
            }
        }

        void removeInLoop(Ipv6Address a) {
            if (addresses.remove(a)) {
                stack.owners.remove(a, this);
                first = addresses.isEmpty() ? null : addresses.iterator().next();
            }
        }

        @Override
        public String toString() {
            Ipv6Address a = first;
            return id + " " + name + (a == null ? "" : " " + a);
        }
    }

    /** 加一个网口（编号由调用方定，同一个协议栈里不要重复）。任意线程；返回的网口马上能用（对它的操作排在加网口之后） */
    public Interface addInterface(int id, String name, PacketOutput output) {
        Interface i = new Interface(this, id, name, output);
        run(() -> {
            if (closed) {
                i.removed = true;
                return;
            }
            interfaces.add(i);
        });
        return i;
    }

    /**
     * 去掉网口：它的地址都去掉；源地址是它的地址的 TCP 连接以 {@link NetworkException.Reason#CLOSED} 报错，UDP 套接字关掉
     * （{@link UdpSocket.Handler#onClosed}），还没回来的 ping 报错，在它的地址上监听的端口去掉。是默认网口时默认网口变成没有。任意线程
     */
    public void removeInterface(Interface i) {
        run(() -> {
            if (i == null || i.removed || !interfaces.remove(i)) {
                return;
            }
            i.removed = true;
            Set<Ipv6Address> gone = new LinkedHashSet<>(i.addresses);
            for (Ipv6Address a : gone) {
                i.removeInLoop(a);
            }
            if (defaultInterface == i) {
                defaultInterface = null;
            }
            NetworkException e = new NetworkException(NetworkException.Reason.CLOSED, "网口 " + i.name + " 已去掉");
            for (TcpConnection c : new ArrayList<>(tcpConnections.values())) {
                if (gone.contains(c.key.localAddress)) {
                    c.destroy(e);
                }
            }
            for (UdpSocket s : new ArrayList<>(udpSockets.values())) {
                if (s.localAddress() != null && gone.contains(s.localAddress())) {
                    s.closeWith(e);
                }
            }
            tcpListeners.keySet().removeIf(k -> k.address != null && gone.contains(k.address));
            for (PendingPing pp : new ArrayList<>(pings.values())) {
                if (gone.contains(pp.source)) {
                    pp.fail(e);
                }
            }
        });
    }

    /** 不绑网口、不绑源地址的套接字用哪个网口（null 为没有，这时它们没有源地址可用）。任意线程 */
    public void setDefaultInterface(Interface i) {
        run(() -> defaultInterface = i == null || i.removed ? null : i);
    }

    /** 现在的默认网口；没有为 null。只在事件循环线程上调用 */
    public Interface defaultInterface() {
        return defaultInterface;
    }

    /** 现在的网口（快照）。只在事件循环线程上调用 */
    public List<Interface> interfaces() {
        return new ArrayList<>(interfaces);
    }

    /**
     * 选源地址：绑了网口用它的地址（同时给了 {@code source} 时要是这个网口的）；只给了源地址时要是本机地址；都没给时用默认网口的。
     * 选不出时为 null。只在事件循环线程上调用
     */
    Ipv6Address pickSource(Interface via, Ipv6Address source) {
        if (via != null) {
            if (via.removed || via.stack != this) {
                return null;
            }
            if (source != null) {
                return via.has(source) ? source : null;
            }
            return via.first();
        }
        if (source != null) {
            return owners.containsKey(source) ? source : null;
        }
        return primaryAddress();
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

    /** 收到一个原始 IPv6 包，不管从哪个网口来（只有一个网口的协议栈用；任意线程都可以调用） */
    public void receive(byte[] raw) {
        run(() -> receiveInLoop(null, raw));
    }

    /** {@code via} 不为 null 时目的地址要是这个网口的地址 */
    void receiveInLoop(Interface via, byte[] raw) {
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
        Interface owner = owners.get(p.destination);
        if (owner == null || (via != null && owner != via)) {
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

    /** 发一个包：从源地址所在的网口出去（源地址不是本机地址、网口已去掉时丢掉）。只在事件循环线程上调用 */
    void send(Ipv6Packet p) {
        if (closed) {
            return;
        }
        Interface i = owners.get(p.source);
        if (i == null || i.removed) {
            dropped++;
            return;
        }
        i.output.send(p.encode());
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
        if (!owners.containsKey(origSrc)) {
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
        return ping(null, source, target, dataLength, timeoutMillis);
    }

    /** 同上，从网口 {@code via} 发（它去掉时以 {@link NetworkException} 结束） */
    public CompletableFuture<PingResult> pingVia(Interface via, Ipv6Address target, int dataLength, long timeoutMillis) {
        return ping(via, null, target, dataLength, timeoutMillis);
    }

    private CompletableFuture<PingResult> ping(Interface via, Ipv6Address source, Ipv6Address target, int dataLength, long timeoutMillis) {
        CompletableFuture<PingResult> f = new CompletableFuture<>();
        run(() -> {
            Ipv6Address src = pickSource(via, source);
            if (closed || src == null) {
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
            PendingPing pp = new PendingPing(key, src, target, loop.nowMillis(), f);
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
        final Ipv6Address source;
        final Ipv6Address target;
        final long sentAt;
        final CompletableFuture<PingResult> future;
        EventLoop.Timer timer;

        PendingPing(int key, Ipv6Address source, Ipv6Address target, long sentAt, CompletableFuture<PingResult> future) {
            this.key = key;
            this.source = source;
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

        void fail(NetworkException e) {
            if (pings.get(key) == this) {
                pings.remove(key);
            }
            if (timer != null) {
                timer.cancel();
            }
            future.completeExceptionally(e);
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
        return openUdp(null, local, port, handler);
    }

    /** 同上，绑在网口 {@code via} 上（绑它现在的地址；网口去掉时套接字关掉） */
    public CompletableFuture<UdpSocket> openUdpVia(Interface via, int port, UdpSocket.Handler handler) {
        return openUdp(via, null, port, handler);
    }

    private CompletableFuture<UdpSocket> openUdp(Interface via, Ipv6Address bind, int port, UdpSocket.Handler handler) {
        CompletableFuture<UdpSocket> f = new CompletableFuture<>();
        run(() -> {
            if (closed) {
                f.completeExceptionally(new NetworkException(NetworkException.Reason.CLOSED, "协议栈已关"));
                return;
            }
            Ipv6Address local = bind;
            if (via != null) {
                local = pickSource(via, null);
                if (local == null) {
                    f.completeExceptionally(new NetworkException(NetworkException.Reason.CLOSED, "网口没有地址或已去掉：" + via.name()));
                    return;
                }
            }
            if (local != null && !owners.containsKey(local)) {
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

    /** 有没有在监听 TCP {@code port}（本机任何一个地址上）。在事件循环线程上调用 */
    public boolean isListening(int port) {
        for (PortKey k : tcpListeners.keySet()) {
            if (k.port == port) {
                return true;
            }
        }
        return false;
    }

    /**
     * 主动连接。握手完成时 future 给出连接；对方拒绝、不可达、超时时以 {@link NetworkException} 异常结束。
     *
     * @param source   源地址（null 为本机第一个地址）
     * @param listener 连接上以后的回调（可以为 null，之后用 {@link TcpConnection#setListener} 设）
     */
    public CompletableFuture<TcpConnection> connect(Ipv6Address source, Ipv6Address remote, int port, TcpConnection.Listener listener) {
        return connect(null, source, remote, port, listener);
    }

    /** 同上，从网口 {@code via} 连（源地址是它的地址；网口去掉时连接以 {@link NetworkException.Reason#CLOSED} 报错） */
    public CompletableFuture<TcpConnection> connectVia(Interface via, Ipv6Address remote, int port, TcpConnection.Listener listener) {
        return connect(via, null, remote, port, listener);
    }

    private CompletableFuture<TcpConnection> connect(Interface via, Ipv6Address source, Ipv6Address remote, int port, TcpConnection.Listener listener) {
        CompletableFuture<TcpConnection> f = new CompletableFuture<>();
        run(() -> {
            Ipv6Address src = pickSource(via, source);
            if (closed || src == null) {
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
