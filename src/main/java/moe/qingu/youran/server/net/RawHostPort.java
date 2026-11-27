package moe.qingu.youran.server.net;

import moe.qingu.youran.api.network.Ipv6Address;
import moe.qingu.youran.api.network.RawHost;
import moe.qingu.youran.api.network.RawPacketReceiver;
import moe.qingu.youran.api.network.packet.Icmpv6Message;
import moe.qingu.youran.api.network.packet.Ipv6Packet;
import moe.qingu.youran.api.network.packet.MalformedPacketException;
import moe.qingu.youran.api.network.stack.EventLoop;
import moe.qingu.youran.api.network.stack.NeighborDiscovery;

import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * 原始主机（{@link RawHost}）接在机房局域网上的那一头。原始主机自己不跑协议栈（清的那边是一块静态配置的 TUN），所以这一头替它做
 * 链路上的事（服主 11-27：NDP、DAD 由 YouranMod 代答，RA、NS、NA 不交给插件）：
 * <ul>
 * <li>链路本地地址（由链路层地址按 EUI-64 生成）与主机的 /128 都先做重复地址检测；/128 重复时这台原始主机不启用、记日志（{@link #ready}）；</li>
 * <li>回邻居请求。没设接收方时不回地址解析的 NS（主机“没开”），路由器解析不到、回“地址不可达”；重复地址检测照样防守；</li>
 * <li>收 RA（默认路由器、在链路上的前缀），按它给插件发的包选下一跳、解析邻居；</li>
 * <li>邻居发现报文不交给插件；别的发给这个 /128 的包原样交给接收方。接收方返回 false 的包丢掉（计数）。</li>
 * </ul>
 * 发：任意线程交进来的包先查是 IPv6 包、源地址是本主机，排进网络线程，选下一跳发到局域网上（照常转发、不看内容）。
 */
final class RawHostPort implements RawHost {
    private final String key;
    private final Ipv6Address address;
    private final EventLoop loop;
    private final RejectLog rejects;
    private final AtomicInteger queued = new AtomicInteger();
    private final Lan lan;
    private final long linkAddress;
    private final NeighborDiscovery nd;
    private final CompletableFuture<Boolean> ready = new CompletableFuture<>();
    private volatile RawPacketReceiver receiver;
    private volatile boolean open = true;

    // 统计（只用来显示）
    volatile long received;
    volatile long refused;
    volatile long sent;
    volatile long rejected;

    RawHostPort(String key, Ipv6Address address, EventLoop loop, Lan lan, java.util.Random random, RejectLog rejects) {
        this.key = key;
        this.address = address;
        this.loop = loop;
        this.lan = lan;
        this.rejects = rejects;
        this.linkAddress = lan.attach(this::frame);
        this.nd = new NeighborDiscovery(loop, random, lan.linkFor(linkAddress), linkAddress, false, new NeighborDiscovery.Events() {
            @Override
            public void addressReady(Ipv6Address a) {
                if (a.equals(address)) {
                    ready.complete(true);
                }
            }

            @Override
            public void addressDuplicated(Ipv6Address a) {
                NetworkServer.LOG.error("游戏内网络：原始主机 {} 的地址 {} 和机房里别人的重复（重复地址检测），不启用", key, a);
                if (a.equals(address)) {
                    open = false;
                    ready.complete(false);
                }
            }

            @Override
            public void resolutionFailed(byte[] packet) {
                rejected++;
            }
        });
        // 没人收时不回地址解析的 NS
        nd.setAnswering(a -> !a.equals(address) || receiver != null);
        nd.addAddress(Ipv6Address.linkLocal(NeighborDiscovery.eui64(linkAddress)));
        nd.addAddress(address);
        nd.solicitRouters();
    }

    /** 地址过了重复地址检测时以 true 结束，重复时以 false 结束 */
    CompletableFuture<Boolean> ready() {
        return ready;
    }

    @Override
    public String key() {
        return key;
    }

    @Override
    public Ipv6Address address() {
        return address;
    }

    @Override
    public void setReceiver(RawPacketReceiver r) {
        receiver = r;
    }

    @Override
    public boolean isOpen() {
        return open;
    }

    /** 关服（网络线程上） */
    void close() {
        open = false;
        receiver = null;
        loop.execute(() -> {
            nd.close();
            lan.detach(linkAddress);
        });
    }

    /** 测试用：代答的邻居发现 */
    NeighborDiscovery neighborDiscovery() {
        return nd;
    }

    /** 局域网上来的帧（网络线程上） */
    private void frame(byte[] packet) {
        if (!open && !nd.isTentative(address)) {
            return;
        }
        Ipv6Packet p;
        try {
            p = Ipv6Packet.parse(packet);
        } catch (MalformedPacketException e) {
            return;
        }
        if (!nd.accepts(p.destination)) {
            return;
        }
        if (p.nextHeader == Ipv6Packet.PROTO_ICMPV6) {
            try {
                Icmpv6Message m = Icmpv6Message.parse(p);
                if (nd.handle(p, m)) {
                    return;
                }
            } catch (MalformedPacketException e) {
                return;
            }
        }
        if (!p.destination.equals(address)) {
            // 发给链路本地地址、组播的别的包：插件不管这些
            return;
        }
        RawPacketReceiver r = receiver;
        if (!open || r == null) {
            refused++;
            return;
        }
        boolean ok;
        try {
            ok = r.receive(packet.clone());
        } catch (Throwable t) {
            rejects.reject("原始主机 " + key, "接收方抛出异常：" + t, loop.nowMillis());
            ok = false;
        }
        if (ok) {
            received++;
        } else {
            refused++;
        }
    }

    @Override
    public boolean send(byte[] packet) {
        String problem = check(packet, address);
        if (problem != null) {
            rejected++;
            loop.execute(() -> rejects.reject("原始主机 " + key, problem, loop.nowMillis()));
            return false;
        }
        if (!open) {
            return false;
        }
        if (queued.incrementAndGet() > MAX_QUEUED) {
            queued.decrementAndGet();
            rejected++;
            loop.execute(() -> rejects.reject("原始主机 " + key, "排队的包超过 " + MAX_QUEUED + " 个", loop.nowMillis()));
            return false;
        }
        loop.execute(() -> {
            queued.decrementAndGet();
            if (!open || !nd.isUsable(address)) {
                rejected++;
                return;
            }
            Ipv6Address next = nd.nextHop(Ipv6Packet.destinationOf(packet));
            if (next == null) {
                rejected++;
                rejects.reject("原始主机 " + key, "没有路由（还没收到机房路由器的通告）", loop.nowMillis());
                return;
            }
            sent++;
            nd.send(packet, next);
        });
        return true;
    }

    /** 不合格的原因；合格为 null */
    static String check(byte[] p, Ipv6Address self) {
        if (p == null || p.length < Ipv6Packet.HEADER) {
            return "不是 IPv6 包（不到 40 字节）";
        }
        if (p.length > Ipv6Packet.MAX_PACKET) {
            return "包超过 " + Ipv6Packet.MAX_PACKET + " 字节（" + p.length + " 字节）";
        }
        if ((p[0] & 0xF0) != 0x60) {
            return "不是 IPv6 包（版本 " + ((p[0] & 0xF0) >> 4) + "）";
        }
        int payload = (p[4] & 0xFF) << 8 | (p[5] & 0xFF);
        if (payload != p.length - Ipv6Packet.HEADER) {
            return "长度字段（" + payload + "）和包的长度（" + p.length + " 字节）对不上";
        }
        Ipv6Address src = Ipv6Packet.sourceOf(p);
        if (!src.equals(self)) {
            return "源地址 " + src + " 不是这台主机的地址";
        }
        return null;
    }
}
