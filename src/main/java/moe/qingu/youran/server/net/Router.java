package moe.qingu.youran.server.net;

import moe.qingu.youran.api.network.DimensionNetwork;
import moe.qingu.youran.api.network.Ipv6Address;
import moe.qingu.youran.api.network.NetworkLayout;
import moe.qingu.youran.api.network.packet.Icmpv6Message;
import moe.qingu.youran.api.network.packet.Ipv6Packet;
import moe.qingu.youran.api.network.stack.EventLoop;
import moe.qingu.youran.api.network.stack.NetStack;

import java.util.HashMap;
import java.util.Map;
import java.util.Random;

/**
 * /48 路由器（网络线程上）。按目的地址交给：坐标路由（第 0 号 /64 → 维度 /72 → 维度路由器 → 设备）、
 * 玩法子路由器（第 1 号 /64 → 机房 /96 → 主机 /128 的协议栈或原始主机；最后一个 /96 是玩家临时地址，交给 {@link PlayerNics} 过滤）、上行口（/48 以外）。
 * 跳数在这里减一（整个 /48 算一跳），用完回“超时”；到不了的回“目标不可达”（ICMPv6 差错按 RFC 4443 限速）。
 * <p>
 * 测试开关 {@link #setSimulation}：按概率丢包、把包推迟 20–80 毫秒（造成乱序）。
 */
public final class Router {
    private final EventLoop loop;
    private final AddressBook book;
    private final DeviceTable devices;
    private final Ipv6Address routerAddress;
    private final Map<Integer, DimRouting> dimensions = new HashMap<>();
    private final Map<Long, Map<Long, HostPort>> datacenters = new HashMap<>();
    private final RateLimiter errorLimiter = new RateLimiter(200, 200 * Ipv6Packet.MAX_PACKET);
    private final Random random = new Random();
    private Uplink uplink;
    /** 玩家临时地址（玩法 /64 的最后一个 /96）；没开时为 null，那个 /96 和别的没登记的机房一样回“无路由” */
    private PlayerNics players;
    private double lossRate;
    private double reorderRate;

    // 统计（网络线程上改，读的时候不加锁，只用来显示）
    volatile long forwarded;
    volatile long deliveredToDevices;
    volatile long deliveredToHosts;
    volatile long unreachableSent;
    volatile long timeExceededSent;
    volatile long malformed;
    volatile long simulatedDrops;
    volatile long simulatedDelays;

    Router(EventLoop loop, AddressBook book, DeviceTable devices, Ipv6Address routerAddress) {
        this.loop = loop;
        this.book = book;
        this.devices = devices;
        this.routerAddress = routerAddress;
        for (AddressBook.Dim d : book.byDimension.values()) {
            dimensions.put(d.index, new DimRouting(d));
        }
    }

    /**
     * 机房主机的收包口：协议栈（{@link NetStack#receive}），或原始主机（{@link RawHostPort}）。
     * 返回 false 表示这台主机现在收不了（原始主机没有接收方），路由器回“地址不可达”。
     */
    interface HostPort {
        boolean receive(byte[] packet);
    }

    /** 机房里的主机挂上来（启动时，网络线程上） */
    void attachHost(Ipv6Address address, NetStack stack) {
        attachHost(address, p -> {
            stack.receive(p);
            return true;
        });
    }

    /** 同上，任意收包口（原始主机） */
    void attachHost(Ipv6Address address, HostPort port) {
        datacenters.computeIfAbsent(NetworkLayout.datacenterIndexOf(address), k -> new HashMap<>())
                .put(NetworkLayout.hostPartOf(address), port);
    }

    /** 机房登记了但还没有主机的，也要能回“地址不可达”而不是“无路由” */
    void attachDatacenter(long index) {
        datacenters.computeIfAbsent(index, k -> new HashMap<>());
    }

    void setUplink(Uplink u) {
        uplink = u;
    }

    void setPlayers(PlayerNics p) {
        players = p;
    }

    /** 测试开关：丢包率、推迟率（0–1）；都为 0 时关掉 */
    void setSimulation(double loss, double reorder) {
        lossRate = Math.max(0, Math.min(1, loss));
        reorderRate = Math.max(0, Math.min(1, reorder));
    }

    double lossRate() {
        return lossRate;
    }

    double reorderRate() {
        return reorderRate;
    }

    /** 收一个包（网络线程上；源地址的校验在交进来之前做完） */
    void forward(byte[] raw) {
        if (raw.length < Ipv6Packet.HEADER || raw.length > Ipv6Packet.MAX_PACKET || (raw[0] & 0xF0) != 0x60
                || ((raw[4] & 0xFF) << 8 | (raw[5] & 0xFF)) > raw.length - Ipv6Packet.HEADER) {
            malformed++;
            return;
        }
        if (lossRate > 0 && random.nextDouble() < lossRate) {
            simulatedDrops++;
            return;
        }
        if (reorderRate > 0 && random.nextDouble() < reorderRate) {
            simulatedDelays++;
            loop.schedule(() -> route(raw), 20 + random.nextInt(61));
            return;
        }
        route(raw);
    }

    /** 从上行口进来的包：源地址不能是 /48 里的（防伪造） */
    void fromUplink(byte[] raw) {
        if (raw.length < Ipv6Packet.HEADER || book.layout.contains(Ipv6Packet.sourceOf(raw))) {
            malformed++;
            return;
        }
        forward(raw);
    }

    private void route(byte[] raw) {
        Ipv6Address dst = Ipv6Packet.destinationOf(raw);
        if (!book.layout.contains(dst)) {
            if (uplink != null) {
                forwarded++;
                uplink.send(raw);
            } else {
                unreachable(raw, Icmpv6Message.UNREACH_NO_ROUTE);
            }
            return;
        }
        int hop = raw[7] & 0xFF;
        if (hop <= 1) {
            sendError(raw, Icmpv6Message.TIME_EXCEEDED, 0);
            timeExceededSent++;
            return;
        }
        byte[] p = raw.clone();
        p[7] = (byte) (hop - 1);
        forwarded++;
        switch (book.layout.subnetOf(dst)) {
            case NetworkLayout.SUBNET_COORDINATES: {
                DimRouting d = dimensions.get(NetworkLayout.dimensionIndexOf(dst));
                if (d == null) {
                    unreachable(p, Icmpv6Message.UNREACH_NO_ROUTE);
                    return;
                }
                d.info.network.route(d, p, NetworkLayout.dimensionBitsOf(dst));
                return;
            }
            case NetworkLayout.SUBNET_GAMEPLAY: {
                if (players != null && NetworkLayout.datacenterIndexOf(dst) == NetworkLayout.PLAYERS_DATACENTER) {
                    players.deliver(p);
                    return;
                }
                Map<Long, HostPort> dc = datacenters.get(NetworkLayout.datacenterIndexOf(dst));
                if (dc == null) {
                    unreachable(p, Icmpv6Message.UNREACH_NO_ROUTE);
                    return;
                }
                HostPort host = dc.get(NetworkLayout.hostPartOf(dst));
                if (host == null || !host.receive(p)) {
                    unreachable(p, Icmpv6Message.UNREACH_ADDRESS);
                    return;
                }
                deliveredToHosts++;
                return;
            }
            default:
                unreachable(p, Icmpv6Message.UNREACH_NO_ROUTE);
        }
    }

    void unreachable(byte[] raw, int code) {
        unreachableSent++;
        sendError(raw, Icmpv6Message.DEST_UNREACHABLE, code);
    }

    private void sendError(byte[] raw, int type, int code) {
        if (!Icmpv6Message.mayReplyWithError(raw) || !errorLimiter.tryAcquire(Ipv6Packet.MAX_PACKET, loop.nowMillis())) {
            return;
        }
        byte[] err = Icmpv6Message.error(type, code, 0, raw).toPacket(routerAddress, Ipv6Packet.sourceOf(raw)).encode();
        // 排进队列再转发（不在这里递归）
        loop.execute(() -> forward(err));
    }

    /** 维度路由器的环境 */
    private final class DimRouting implements DimensionNetwork.Routing {
        final AddressBook.Dim info;

        DimRouting(AddressBook.Dim info) {
            this.info = info;
        }

        @Override
        public int dimension() {
            return info.dimension;
        }

        @Override
        public boolean deliverToDevice(int x, int y, int z, byte[] packet) {
            DeviceTable.Device d = devices.byPosition(info.dimension, x, y, z);
            if (d == null || d.blockedReason() != null || !d.address.equals(Ipv6Packet.destinationOf(packet))) {
                return false;
            }
            DeviceTable.Attachment a = d.attachment;
            if (a == null) {
                return false;
            }
            deliveredToDevices++;
            a.deliver(packet);
            return true;
        }

        @Override
        public void unreachable(byte[] packet, int code) {
            Router.this.unreachable(packet, code);
        }
    }
}
