package moe.qingu.youran.server.net;

import moe.qingu.youran.api.network.DefaultDimensionNetwork;
import moe.qingu.youran.api.network.Ipv6Address;
import moe.qingu.youran.api.network.NetworkLayout;
import moe.qingu.youran.api.network.RawHost;
import moe.qingu.youran.api.network.packet.Icmpv6Message;
import moe.qingu.youran.api.network.packet.Ipv6Packet;
import moe.qingu.youran.api.network.stack.ManualEventLoop;
import moe.qingu.youran.api.network.stack.NetStack;
import moe.qingu.youran.api.network.stack.PingResult;
import net.minecraft.entity.player.EntityPlayerMP;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** 原始主机：收发原始 IPv6 包、没人收时回不可达、只认自己的源地址、排队上限 */
class RawHostTest {
    final ManualEventLoop loop = new ManualEventLoop();
    final NetworkLayout layout = NetworkLayout.fromGlobalId(0x123456789aL);
    final Ipv6Address routerAddr = layout.hostAddress(0, 1);
    final Ipv6Address hostAddr = layout.hostAddress(0, 0x53);
    final Ipv6Address rawAddr = layout.hostAddress(0, 0x99);
    final RejectLog rejects = new RejectLog(10_000);
    final Router router;
    final NetStack host;
    final RawHostPort raw;
    final List<byte[]> got = new ArrayList<>();

    RawHostTest() {
        Map<Integer, AddressBook.Dim> dims = new LinkedHashMap<>();
        dims.put(0, new AddressBook.Dim(0, 0, DefaultDimensionNetwork.INSTANCE));
        Map<String, Ipv6Address> hosts = new LinkedHashMap<>();
        hosts.put("youranmod:router", routerAddr);
        hosts.put("youranmod:resolver", hostAddr);
        hosts.put("test:raw", rawAddr);
        AddressBook book = new AddressBook(layout, dims, Collections.singletonMap("youranmod:main", 0L), hosts);
        Router[] holder = new Router[1];
        NicTable devices = new NicTable(book::coordinateAddress, loop, new RejectLog(10_000), p -> holder[0].forward(p), 50, 50 * 1280, p -> {
        });
        router = new Router(loop, book, devices, routerAddr);
        holder[0] = router;
        NetStack r = new NetStack(loop, p -> loop.execute(() -> router.forward(p)), new Random(1));
        r.addAddress(routerAddr);
        router.attachHost(routerAddr, r);
        host = new NetStack(loop, p -> loop.execute(() -> router.forward(p)), new Random(2));
        host.addAddress(hostAddr);
        router.attachHost(hostAddr, host);
        raw = new RawHostPort("test:raw", rawAddr, loop, router, rejects);
        router.attachHost(rawAddr, raw);
        router.attachDatacenter(0);
    }

    PingResult ping(Ipv6Address to) throws Exception {
        CompletableFuture<PingResult> f = host.ping(null, to, 56, 2000);
        loop.advance(3000);
        return RouterTest.done(f);
    }

    byte[] echoFromRaw(Ipv6Address src, Ipv6Address dst, int seq) {
        return Icmpv6Message.echo(Icmpv6Message.ECHO_REQUEST, 7, seq, new byte[]{1, 2, 3}).toPacket(src, dst).encode();
    }

    @Test
    void packetsToRawHostReachReceiverVerbatim() throws Exception {
        raw.setReceiver(p -> {
            got.add(p);
            return true;
        });
        CompletableFuture<PingResult> f = host.ping(null, rawAddr, 56, 2000);
        loop.advance(100);
        assertEquals(1, got.size());
        Ipv6Packet p = Ipv6Packet.parse(got.get(0));
        assertEquals(hostAddr, p.source);
        assertEquals(rawAddr, p.destination);
        assertEquals(Ipv6Packet.DEFAULT_HOP_LIMIT - 1, p.hopLimit, "路由器减了一跳");
        Icmpv6Message m = Icmpv6Message.parse(p);
        assertEquals(Icmpv6Message.ECHO_REQUEST, m.type);
        // 原始主机自己回 echo reply（照抄内容），协议栈收到算 ping 通
        byte[] reply = Icmpv6Message.echo(Icmpv6Message.ECHO_REPLY, m.echoIdentifier(), m.echoSequence(), m.echoData())
                .toPacket(rawAddr, hostAddr).encode();
        assertTrue(raw.send(reply));
        loop.advance(2900);
        assertEquals(PingResult.Status.REPLY, RouterTest.done(f).status);
        assertEquals(1, raw.received);
        assertEquals(1, raw.sent);
    }

    @Test
    void noReceiverIsAddressUnreachable() throws Exception {
        assertEquals(PingResult.Status.UNREACHABLE, ping(rawAddr).status);
        raw.setReceiver(p -> false);
        assertEquals(PingResult.Status.UNREACHABLE, ping(rawAddr).status);
        raw.setReceiver(p -> {
            throw new IllegalStateException("坏了");
        });
        assertEquals(PingResult.Status.UNREACHABLE, ping(rawAddr).status);
        raw.setReceiver(p -> true);
        // 收下了但没人回：超时，不是不可达
        assertEquals(PingResult.Status.TIMEOUT, ping(rawAddr).status);
        assertEquals(3, raw.refused);
    }

    @Test
    void rawHostReachesHostsAndGetsRepliesAndErrors() throws Exception {
        raw.setReceiver(p -> {
            got.add(p);
            return true;
        });
        assertTrue(raw.send(echoFromRaw(rawAddr, hostAddr, 1)));
        loop.advance(100);
        assertEquals(1, got.size());
        Icmpv6Message m = Icmpv6Message.parse(Ipv6Packet.parse(got.get(0)));
        assertEquals(Icmpv6Message.ECHO_REPLY, m.type);
        assertEquals(1, m.echoSequence());
        // 机房里没有的主机：路由器回地址不可达，也送回原始主机
        got.clear();
        assertTrue(raw.send(echoFromRaw(rawAddr, layout.hostAddress(0, 0x1234), 2)));
        loop.advance(100);
        assertEquals(1, got.size());
        Ipv6Packet err = Ipv6Packet.parse(got.get(0));
        assertEquals(routerAddr, err.source);
        Icmpv6Message e = Icmpv6Message.parse(err);
        assertEquals(Icmpv6Message.DEST_UNREACHABLE, e.type);
        assertEquals(Icmpv6Message.UNREACH_ADDRESS, e.code);
    }

    @Test
    void contentIsNotFilteredButSourceMustBeOwn() {
        // 任意上层协议号、任意内容：照收
        byte[] odd = new Ipv6Packet(253, rawAddr, hostAddr, new byte[]{9, 9, 9}).encode();
        assertTrue(raw.send(odd));
        // 源地址不是自己：拒绝（设备、主机的源地址都不能伪造）
        assertFalse(raw.send(echoFromRaw(hostAddr, routerAddr, 1)));
        assertFalse(raw.send(echoFromRaw(layout.hostAddress(0, 0x98), routerAddr, 1)));
        // 不是 IPv6 包
        assertFalse(raw.send(new byte[10]));
        byte[] v4 = echoFromRaw(rawAddr, hostAddr, 1);
        v4[0] = 0x45;
        assertFalse(raw.send(v4));
        // 长度字段不对
        byte[] cut = Arrays.copyOf(echoFromRaw(rawAddr, hostAddr, 1), 50);
        assertFalse(raw.send(cut));
        // 超过 1280 字节
        byte[] big = new byte[Ipv6Packet.MAX_PACKET + 1];
        big[0] = 0x60;
        assertFalse(raw.send(big));
        assertEquals(6, raw.rejected);
        assertNotNull(RawHostPort.check(null, rawAddr));
        assertEquals(null, RawHostPort.check(echoFromRaw(rawAddr, hostAddr, 1), rawAddr));
    }

    @Test
    void queueIsBounded() {
        byte[] p = echoFromRaw(rawAddr, hostAddr, 1);
        for (int i = 0; i < RawHost.MAX_QUEUED; i++) {
            assertTrue(raw.send(p.clone()));
        }
        assertFalse(raw.send(p.clone()), "网络线程还没处理，排满了");
        loop.drain();
        assertTrue(raw.send(p.clone()), "处理完又能排");
    }

    @Test
    void closedHostRefusesBothWays() throws Exception {
        raw.setReceiver(p -> true);
        raw.close();
        assertFalse(raw.isOpen());
        assertFalse(raw.send(echoFromRaw(rawAddr, hostAddr, 1)));
        assertEquals(PingResult.Status.UNREACHABLE, ping(rawAddr).status);
    }
}
