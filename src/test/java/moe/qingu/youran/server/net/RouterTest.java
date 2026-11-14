package moe.qingu.youran.server.net;

import moe.qingu.youran.api.network.DefaultDimensionNetwork;
import moe.qingu.youran.api.network.DeviceHandle;
import moe.qingu.youran.api.network.Ipv6Address;
import moe.qingu.youran.api.network.NetworkLayout;
import moe.qingu.youran.api.network.packet.Icmpv6Message;
import moe.qingu.youran.api.network.packet.Ipv6Packet;
import moe.qingu.youran.api.network.packet.UdpDatagram;
import moe.qingu.youran.api.network.stack.ManualEventLoop;
import moe.qingu.youran.api.network.stack.NetStack;
import moe.qingu.youran.api.network.stack.PingResult;
import moe.qingu.youran.net.NicMessages;
import net.minecraft.entity.player.EntityPlayerMP;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** /48 路由器、维度路由、机房路由与设备网卡的校验（网络线程上的逻辑，世界换成假的） */
class RouterTest {
    final ManualEventLoop loop = new ManualEventLoop();
    final NetworkLayout layout = NetworkLayout.fromGlobalId(0x123456789aL);
    final Ipv6Address routerAddr = layout.hostAddress(0, 1);
    final Ipv6Address hostAddr = layout.hostAddress(0, 0x53);
    final Map<String, Boolean> loaded = new LinkedHashMap<>();
    DeviceTable devices;
    Router router;
    NicGateway nic;
    RejectLog rejects = new RejectLog(10_000);
    NetStack host;

    RouterTest() {
        Map<Integer, AddressBook.Dim> dims = new LinkedHashMap<>();
        dims.put(0, new AddressBook.Dim(0, 0, DefaultDimensionNetwork.INSTANCE));
        dims.put(-1, new AddressBook.Dim(-1, 1, DefaultDimensionNetwork.INSTANCE));
        Map<String, Long> dcs = Collections.singletonMap("youranmod:main", 0L);
        Map<String, Ipv6Address> hosts = new LinkedHashMap<>();
        hosts.put("youranmod:router", routerAddr);
        hosts.put("youranmod:resolver", hostAddr);
        AddressBook book = new AddressBook(layout, dims, dcs, hosts);
        devices = new DeviceTable(book, new DeviceTable.Hooks() {
            @Override
            public boolean isLoaded(int dimension, int x, int y, int z) {
                return loaded.getOrDefault(dimension + " " + x + " " + y + " " + z, true);
            }

            @Override
            public Ipv6Address resolverAddress() {
                return hostAddr;
            }

            @Override
            public Ipv6Address ntpAddress() {
                return null;
            }

            @Override
            public EntityPlayerMP player(UUID id) {
                return null;
            }

            @Override
            public void fromDevice(DeviceTable.Device d, byte[] packet) {
                byte[] copy = packet.clone();
                loop.execute(() -> nic.fromDevice(d, copy));
            }
        });
        router = new Router(loop, book, devices, routerAddr);
        nic = new NicGateway(loop, devices, router, rejects, 50, 50 * 1280);
        NetStack r = new NetStack(loop, p -> loop.execute(() -> router.forward(p)), new Random(1));
        r.addAddress(routerAddr);
        router.attachHost(routerAddr, r);
        host = new NetStack(loop, p -> loop.execute(() -> router.forward(p)), new Random(2));
        host.addAddress(hostAddr);
        router.attachHost(hostAddr, host);
        router.attachDatacenter(0);
    }

    /** 服务端测试设备：网卡接到一个协议栈 */
    NetStack emulated(int dim, int x, int y, int z) {
        DeviceHandle h = devices.register(dim, x, y, z);
        NetStack s = new NetStack(loop, h::send, new Random(x));
        s.addAddress(h.address());
        h.attachEndpoint(s::receive);
        return s;
    }

    PingResult ping(NetStack from, Ipv6Address to) throws Exception {
        CompletableFuture<PingResult> f = from.ping(null, to, 56, 2000);
        loop.advance(3000);
        return done(f);
    }

    @Test
    void deviceAndHostsAreReachable() throws Exception {
        NetStack dev = emulated(0, -100, 64, 200);
        assertEquals(PingResult.Status.REPLY, ping(dev, hostAddr).status);
        assertEquals(PingResult.Status.REPLY, ping(dev, routerAddr).status);
        assertEquals(PingResult.Status.REPLY, ping(host, devices.addressAt(0, -100, 64, 200)).status);
        // 两台设备之间（不同维度）
        NetStack nether = emulated(-1, 5, 5, 5);
        assertEquals(PingResult.Status.REPLY, ping(nether, devices.addressAt(0, -100, 64, 200)).status);
    }

    @Test
    void emptyCoordinateIsAddressUnreachable() throws Exception {
        PingResult r = ping(host, devices.addressAt(0, 1, 2, 3));
        assertEquals(PingResult.Status.UNREACHABLE, r.status);
        assertEquals(Icmpv6Message.UNREACH_ADDRESS, r.code);
        assertEquals(routerAddr, r.from);
        // 1–255 号槽位现在也没有路由器
        Ipv6Address slot1 = layout.coordinateAddress(0, DefaultDimensionNetwork.INSTANCE.encode(1, 2, 3, 1));
        assertEquals(Icmpv6Message.UNREACH_ADDRESS, ping(host, slot1).code);
    }

    @Test
    void unpoweredUnloadedUnattachedDevicesAreUnreachable() throws Exception {
        NetStack dev = emulated(0, 10, 10, 10);
        DeviceHandle h = devices.at(0, 10, 10, 10);
        Ipv6Address a = h.address();
        h.setPowered(false);
        assertEquals(PingResult.Status.UNREACHABLE, ping(host, a).status);
        h.setPowered(true);
        assertEquals(PingResult.Status.REPLY, ping(host, a).status);
        devices.onChunk(0, 0, 0, false);
        assertEquals(PingResult.Status.UNREACHABLE, ping(host, a).status);
        devices.onChunk(0, 0, 0, true);
        h.detach();
        assertEquals(PingResult.Status.UNREACHABLE, ping(host, a).status);
        // 断电的设备发的包被网卡丢掉
        h.attachEndpoint(dev::receive);
        h.setPowered(false);
        long before = rejects.total();
        assertEquals(PingResult.Status.TIMEOUT, ping(dev, hostAddr).status);
        assertEquals(before + 1, rejects.total());
    }

    @Test
    void unknownSubnetsAndDimensionsHaveNoRoute() throws Exception {
        assertEquals(Icmpv6Message.UNREACH_NO_ROUTE, ping(host, layout.coordinateAddress(9, 0)).code, "没分的维度序号");
        assertEquals(Icmpv6Message.UNREACH_NO_ROUTE, ping(host, layout.hostAddress(7, 1)).code, "没登记的机房");
        assertEquals(Icmpv6Message.UNREACH_ADDRESS, ping(host, layout.hostAddress(0, 0x99)).code, "机房里没登记的主机");
        assertEquals(Icmpv6Message.UNREACH_NO_ROUTE, ping(host, new Ipv6Address(layout.prefix().high() | 5, 1)).code, "第 5 号 /64");
        assertEquals(Icmpv6Message.UNREACH_NO_ROUTE, ping(host, Ipv6Address.parse("2001:db8::1")).code, "/48 以外（没有上行口）");
    }

    @Test
    void uplinkGetsOutsidePackets() throws Exception {
        List<byte[]> out = new ArrayList<>();
        router.setUplink(out::add);
        host.ping(null, Ipv6Address.parse("2001:db8::1"), 8, 100);
        loop.advance(10);
        assertEquals(1, out.size());
        // 上行口进来的包不能用 /48 里的源地址
        router.fromUplink(Icmpv6Message.echo(128, 1, 1, new byte[0]).toPacket(routerAddr, hostAddr).encode());
        loop.advance(10);
        assertEquals(1, router.malformed);
    }

    @Test
    void hopLimitExpires() throws Exception {
        byte[] p = Icmpv6Message.echo(128, 1, 1, new byte[0]).toPacket(hostAddr, routerAddr).encode();
        p[7] = 1;
        // 重新算校验和不需要：跳数不在伪首部里
        router.forward(p);
        loop.advance(10);
        assertEquals(1, router.timeExceededSent);
    }

    @Test
    void nicRejectsSpoofedOversizedAndFloodingPackets() {
        UUID player = UUID.randomUUID();
        DeviceTable.Device d = (DeviceTable.Device) devices.register(0, 1, 64, 1);
        List<byte[]> toClient = new ArrayList<>();
        List<byte[]> control = new ArrayList<>();
        d.attach(new DeviceTable.PlayerAttachment(player, "Tester", null, 1) {
            @Override
            void deliver(byte[] packet) {
                toClient.add(packet);
            }

            @Override
            void control(byte[] message) {
                control.add(message);
            }
        });
        assertEquals(NicMessages.ATTACH, control.get(0)[0]);
        Ipv6Address me = d.address();
        // 正常的包
        nic.fromPlayer(player, "Tester", Icmpv6Message.echo(128, 1, 1, new byte[8]).toPacket(me, hostAddr).encode(), 0);
        loop.advance(10);
        assertEquals(1, toClient.size(), "收到回显应答");
        assertEquals(1, nic.accepted);
        // 伪造源地址
        nic.fromPlayer(player, "Tester", Icmpv6Message.echo(128, 1, 2, new byte[8]).toPacket(hostAddr, routerAddr).encode(), 0);
        // 超大包（客户端消息超过 1281 字节时到这里是 null）
        nic.fromPlayer(player, "Tester", null, 5000);
        // 1281 字节的“包”
        byte[] big = new byte[1281];
        big[0] = 0x60;
        nic.fromPlayer(player, "Tester", big, 1282);
        // 别的玩家冒充操作者
        nic.fromPlayer(UUID.randomUUID(), "Other", Icmpv6Message.echo(128, 1, 3, new byte[8]).toPacket(me, hostAddr).encode(), 0);
        loop.advance(10);
        assertEquals(1, nic.accepted);
        assertEquals(4, rejects.total());
        // 超频：每秒 50 个，第 51 个起丢
        for (int i = 0; i < 100; i++) {
            nic.fromPlayer(player, "Tester", new UdpDatagram(1000, 9, new byte[1]).toPacket(me, hostAddr).encode(), 0);
        }
        // 桶里第一包后剩 49 个，过了 20 毫秒又补回 1 个
        assertEquals(1 + 50, nic.accepted);
        assertEquals(4 + 50, rejects.total());
        // 断开后发给它的包回目标不可达
        d.detach();
        assertEquals(NicMessages.DETACH, control.get(1)[0]);
        assertNull(devices.byPlayer(player));
    }

    @Test
    void outOfRangeHasNoAddressAndDevicesReplace() {
        assertNull(devices.register(0, 600_000, 64, 0));
        assertNull(devices.register(0, 0, 300, 0));
        assertNull(devices.register(7, 0, 64, 0), "维度 7 没有全局 Wi-Fi");
        DeviceHandle a = devices.register(0, 0, 64, 0);
        DeviceHandle b = devices.register(0, 0, 64, 0);
        assertTrue(a.isRemoved());
        assertNotNull(devices.at(0, 0, 64, 0));
        assertEquals(b, devices.at(0, 0, 64, 0));
        assertEquals(a.address(), b.address());
    }

    @Test
    void simulatedLossDropsPackets() throws Exception {
        NetStack dev = emulated(0, 3, 3, 3);
        router.setSimulation(1.0, 0);
        assertEquals(PingResult.Status.TIMEOUT, ping(dev, hostAddr).status);
        router.setSimulation(0, 1.0);
        PingResult r = ping(dev, hostAddr);
        assertEquals(PingResult.Status.REPLY, r.status);
        assertTrue(r.rttMillis >= 40, "两个方向都推迟 20–80 毫秒");
        assertTrue(Ipv6Packet.MAX_PACKET == 1280);
    }

    static <T> T done(java.util.concurrent.CompletableFuture<T> f) throws Exception {
        org.junit.jupiter.api.Assertions.assertTrue(f.isDone(), "future 还没完成");
        return f.get();
    }
}
