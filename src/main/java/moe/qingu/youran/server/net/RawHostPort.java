package moe.qingu.youran.server.net;

import moe.qingu.youran.api.network.Ipv6Address;
import moe.qingu.youran.api.network.RawHost;
import moe.qingu.youran.api.network.RawPacketReceiver;
import moe.qingu.youran.api.network.packet.Ipv6Packet;
import moe.qingu.youran.api.network.stack.EventLoop;

import java.util.concurrent.atomic.AtomicInteger;

/**
 * 原始主机（{@link RawHost}）在路由器上的一侧。收：路由器在网络线程上交来的包原样给接收方，没人收时让路由器回“地址不可达”。
 * 发：任意线程交进来的包先查是 IPv6 包、源地址是本主机，排进网络线程再进路由器（{@link Router#forward}，照常转发、不看内容）。
 */
final class RawHostPort implements RawHost, Router.HostPort {
    private final String key;
    private final Ipv6Address address;
    private final EventLoop loop;
    private final Router router;
    private final RejectLog rejects;
    private final AtomicInteger queued = new AtomicInteger();
    private volatile RawPacketReceiver receiver;
    private volatile boolean open = true;

    // 统计（只用来显示）
    volatile long received;
    volatile long refused;
    volatile long sent;
    volatile long rejected;

    RawHostPort(String key, Ipv6Address address, EventLoop loop, Router router, RejectLog rejects) {
        this.key = key;
        this.address = address;
        this.loop = loop;
        this.router = router;
        this.rejects = rejects;
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

    void close() {
        open = false;
        receiver = null;
    }

    /** 路由器交来的（网络线程上） */
    @Override
    public boolean receive(byte[] packet) {
        RawPacketReceiver r = receiver;
        if (!open || r == null) {
            refused++;
            return false;
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
        return ok;
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
            if (open) {
                sent++;
                router.forward(packet);
            }
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
