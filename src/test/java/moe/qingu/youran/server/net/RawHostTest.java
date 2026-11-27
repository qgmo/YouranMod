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

/**
 * 原始主机：收发原始 IPv6 包、没人收时回不可达、只认自己的源地址、排队上限；0B 起它接在机房的局域网上，邻居发现与重复地址检测由 YouranMod 代答
 * （服主 11-27），邻居发现报文不交给插件。
 * <p>
 * 跟着 0B 变了的预期：同一个局域网上的主机直接把包交给它（不经路由器，跳数不减）；没设接收方时不回地址解析的 NS，
 * 解析失败（约 3 秒）后回“地址不可达”；接收方返回 false、抛异常的包丢掉（原来是路由器回“地址不可达”：包已经交到了这台主机的链路上，
 * 没有路由器在中间）；插件发给局域网上没有的地址的包，YouranMod 解析不到，丢掉计数（原来路由器回不可达）；发给机房外面没有的地址时由那边的路由器回。
 */
class RawHostTest {
    final TestNetwork net = new TestNetwork();
    final Ipv6Address routerAddr = net.routerAddr;
    final Ipv6Address hostAddr = net.resolverAddr;
    final Ipv6Address rawAddr = net.layout.hostAddress(0, 0x99);
    final NetStack host = net.host(hostAddr);
    final RawHostPort raw = net.rawHost("test:raw", rawAddr);
    final List<byte[]> got = new ArrayList<>();

    RawHostTest() {
        net.settle();
        assertTrue(raw.ready().isDone() && raw.ready().join(), "重复地址检测过了");
    }

    PingResult ping(NetStack from, Ipv6Address to) throws Exception {
        CompletableFuture<PingResult> f = from.ping(null, to, 56, 5000);
        net.loop.advance(6000);
        return RouterTest.done(f);
    }

    PingResult ping(Ipv6Address to) throws Exception {
        return ping(host, to);
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
        CompletableFuture<PingResult> f = host.ping(null, rawAddr, 56, 5000);
        net.loop.advance(100);
        assertEquals(1, got.size(), "邻居发现报文不交给插件，只有回显请求");
        Ipv6Packet p = Ipv6Packet.parse(got.get(0));
        assertEquals(hostAddr, p.source);
        assertEquals(rawAddr, p.destination);
        assertEquals(Ipv6Packet.DEFAULT_HOP_LIMIT, p.hopLimit, "同一个局域网上，不经路由器");
        Icmpv6Message m = Icmpv6Message.parse(p);
        assertEquals(Icmpv6Message.ECHO_REQUEST, m.type);
        // 原始主机自己回 echo reply（照抄内容），协议栈收到算 ping 通
        byte[] reply = Icmpv6Message.echo(Icmpv6Message.ECHO_REPLY, m.echoIdentifier(), m.echoSequence(), m.echoData())
                .toPacket(rawAddr, hostAddr).encode();
        assertTrue(raw.send(reply));
        net.loop.advance(100);
        assertEquals(PingResult.Status.REPLY, RouterTest.done(f).status);
        assertEquals(1, raw.received);
        assertEquals(1, raw.sent);
        // 从坐标层的网卡来：经过维度、核心、玩法、机房四台路由器
        got.clear();
        TestNetwork.StackNic dev = net.emulated(0, 4, 64, 4);
        dev.stack.ping(null, rawAddr, 8, 1000);
        net.loop.advance(100);
        assertEquals(1, got.size());
        assertEquals(Ipv6Packet.DEFAULT_HOP_LIMIT - 4, Ipv6Packet.parse(got.get(0)).hopLimit);
    }

    @Test
    void noReceiverIsAddressUnreachable() throws Exception {
        PingResult r = ping(rawAddr);
        assertEquals(PingResult.Status.UNREACHABLE, r.status, "没设接收方：不回邻居请求，解析失败");
        assertEquals(Icmpv6Message.UNREACH_ADDRESS, r.code);
        TestNetwork.StackNic dev = net.emulated(0, 5, 64, 5);
        r = ping(dev.stack, rawAddr);
        assertEquals(PingResult.Status.UNREACHABLE, r.status);
        assertEquals(routerAddr, r.from, "从机房外面来的：机房路由器解析失败，回地址不可达");
        raw.setReceiver(p -> false);
        assertEquals(PingResult.Status.TIMEOUT, ping(rawAddr).status, "接收方收不了：丢掉");
        raw.setReceiver(p -> {
            throw new IllegalStateException("坏了");
        });
        assertEquals(PingResult.Status.TIMEOUT, ping(rawAddr).status, "接收方抛异常：按收不了算，丢掉");
        raw.setReceiver(p -> true);
        // 收下了但没人回：超时
        assertEquals(PingResult.Status.TIMEOUT, ping(rawAddr).status);
        assertEquals(2, raw.refused);
        assertEquals(1, raw.received);
    }

    @Test
    void rawHostReachesHostsAndGetsRepliesAndErrors() throws Exception {
        raw.setReceiver(p -> {
            got.add(p);
            return true;
        });
        assertTrue(raw.send(echoFromRaw(rawAddr, hostAddr, 1)));
        net.loop.advance(100);
        assertEquals(1, got.size());
        Icmpv6Message m = Icmpv6Message.parse(Ipv6Packet.parse(got.get(0)));
        assertEquals(Icmpv6Message.ECHO_REPLY, m.type);
        assertEquals(1, m.echoSequence());
        // 别的机房里没有的主机：那个机房的路由器回地址不可达，送回原始主机
        got.clear();
        assertTrue(raw.send(echoFromRaw(rawAddr, net.layout.hostAddress(2, 0x1234), 2)));
        net.loop.advance(5000);
        assertEquals(1, got.size());
        Ipv6Packet err = Ipv6Packet.parse(got.get(0));
        assertEquals(net.datacenterRouter(2).address, err.source);
        Icmpv6Message e = Icmpv6Message.parse(err);
        assertEquals(Icmpv6Message.DEST_UNREACHABLE, e.type);
        assertEquals(Icmpv6Message.UNREACH_ADDRESS, e.code);
        // 同一个局域网上没有的地址：YouranMod 代答的那一头解析不到，丢掉计数
        got.clear();
        long before = raw.rejected;
        assertTrue(raw.send(echoFromRaw(rawAddr, net.layout.hostAddress(0, 0x1234), 3)));
        net.loop.advance(5000);
        assertTrue(got.isEmpty());
        assertEquals(before + 1, raw.rejected);
    }

    @Test
    void neighborDiscoveryIsAnsweredForThePlugin() throws Exception {
        raw.setReceiver(p -> {
            got.add(p);
            return true;
        });
        host.ping(null, rawAddr, 8, 1000);
        net.loop.advance(100);
        // 主机解析到了原始主机，插件只看到回显请求
        assertEquals("REACHABLE", host.defaultInterface().neighborDiscovery().neighborState(rawAddr));
        for (byte[] p : got) {
            Ipv6Packet ip = Ipv6Packet.parse(p);
            assertEquals(rawAddr, ip.destination);
            assertEquals(Icmpv6Message.ECHO_REQUEST, Icmpv6Message.parse(ip).type);
        }
        // 代答的那一头也从机房路由器的 RA 学到了默认路由器
        assertEquals(1, raw.neighborDiscovery().defaultRouters().size());
    }

    @Test
    void duplicateAddressIsNotEnabled() {
        RawHostPort twin = net.rawHost("test:twin", rawAddr);
        net.settle();
        assertTrue(twin.ready().isDone());
        assertFalse(twin.ready().join(), "地址和已经在用的原始主机重复：不启用");
        assertFalse(twin.isOpen());
        assertTrue(raw.isOpen(), "先在的那台照常");
    }

    @Test
    void contentIsNotFilteredButSourceMustBeOwn() {
        // 任意上层协议号、任意内容：照收
        byte[] odd = new Ipv6Packet(253, rawAddr, hostAddr, new byte[]{9, 9, 9}).encode();
        assertTrue(raw.send(odd));
        // 源地址不是自己：拒绝（设备、主机的源地址都不能伪造）
        assertFalse(raw.send(echoFromRaw(hostAddr, routerAddr, 1)));
        assertFalse(raw.send(echoFromRaw(net.layout.hostAddress(0, 0x98), routerAddr, 1)));
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
        net.loop.drain();
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
