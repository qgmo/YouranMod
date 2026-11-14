package moe.qingu.youran.api.network.stack;

import moe.qingu.youran.api.network.Ipv6Address;
import moe.qingu.youran.api.network.packet.Icmpv6Message;
import moe.qingu.youran.api.network.packet.Ipv6Packet;
import moe.qingu.youran.api.network.packet.UdpDatagram;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** 协议栈的 ICMPv6 与 UDP */
class NetStackTest {
    private static final Ipv6Address A = Ipv6Address.parse("fd00::a");
    private static final Ipv6Address B = Ipv6Address.parse("fd00::b");

    @Test
    void pingReply() throws Exception {
        TestLink link = new TestLink();
        NetStack a = link.host("fd00::a");
        link.host("fd00::b");
        CompletableFuture<PingResult> f = a.ping(null, B, 1232, 2000);
        link.loop.advance(10);
        PingResult r = done(f);
        assertEquals(PingResult.Status.REPLY, r.status);
        assertEquals(B, r.from);
        assertEquals(2, r.rttMillis);
        assertEquals(1232, r.bytes);
        assertEquals(1280, link.sent.get(0).length);
    }

    @Test
    void pingTimeout() throws Exception {
        TestLink link = new TestLink();
        NetStack a = link.host("fd00::a");
        CompletableFuture<PingResult> f = a.ping(null, Ipv6Address.parse("fd00::99"), 56, 2000);
        link.loop.advance(1999);
        assertTrue(!f.isDone());
        link.loop.advance(1);
        assertEquals(PingResult.Status.TIMEOUT, done(f).status);
    }

    @Test
    void udpAndPortUnreachable() throws Exception {
        TestLink link = new TestLink();
        NetStack a = link.host("fd00::a");
        NetStack b = link.host("fd00::b");
        List<String> got = new ArrayList<>();
        b.openUdp(null, 9, (s, from, port, to, data) -> {
            got.add(new String(data) + "@" + from + ":" + port);
            s.send(to, from, port, data);
        });
        List<Integer> errors = new ArrayList<>();
        UdpSocket sa = done(a.openUdp(null, 0, new UdpSocket.Handler() {
            @Override
            public void onDatagram(UdpSocket s, Ipv6Address from, int port, Ipv6Address to, byte[] data) {
                got.add("echo " + new String(data));
            }

            @Override
            public void onError(UdpSocket s, Ipv6Address dst, int type, int code, Ipv6Address reporter) {
                errors.add(type * 100 + code);
            }
        }));
        assertTrue(sa.port() >= 49152);
        sa.send(B, 9, "hi".getBytes());
        sa.send(B, 10, "nobody".getBytes());
        link.loop.advance(10);
        assertEquals("hi@" + A + ":" + sa.port(), got.get(0));
        assertEquals("echo hi", got.get(1));
        assertEquals(1, errors.size());
        assertEquals(Icmpv6Message.DEST_UNREACHABLE * 100 + Icmpv6Message.UNREACH_PORT, (int) errors.get(0));
        // 超过 1232 字节的数据报不发
        assertTrue(!sa.send(B, 9, new byte[1233]));
    }

    @Test
    void unknownNextHeaderGetsParameterProblem() throws Exception {
        TestLink link = new TestLink();
        link.host("fd00::a");
        NetStack b = link.host("fd00::b");
        b.receive(new Ipv6Packet(0x99, A, B, new byte[8]).encode());
        link.loop.advance(10);
        Ipv6Packet err = Ipv6Packet.parse(link.sent.get(0));
        Icmpv6Message m = Icmpv6Message.parse(err);
        assertEquals(Icmpv6Message.PARAMETER_PROBLEM, m.type);
        assertEquals(Icmpv6Message.PARAM_UNKNOWN_NEXT_HEADER, m.code);
        assertEquals(6, ((m.body[2] & 0xFF) << 8) | (m.body[3] & 0xFF));
    }

    @Test
    void foreignAndCorruptPacketsAreDropped() {
        TestLink link = new TestLink();
        NetStack b = link.host("fd00::b");
        b.receive(new UdpDatagram(1, 9, new byte[1]).toPacket(A, Ipv6Address.parse("fd00::c")).encode());
        byte[] bad = new UdpDatagram(1, 9, new byte[1]).toPacket(A, B).encode();
        bad[bad.length - 1] ^= 1;
        b.receive(bad);
        b.receive(new byte[10]);
        link.loop.advance(10);
        assertEquals(3, b.droppedCount());
        assertEquals(0, link.sent.size(), "坏包不回差错");
    }

    @Test
    void echoDataIsReturnedUnchanged() throws Exception {
        TestLink link = new TestLink();
        NetStack a = link.host("fd00::a");
        link.host("fd00::b");
        a.ping(null, B, 100, 1000);
        link.loop.advance(10);
        Icmpv6Message req = Icmpv6Message.parse(Ipv6Packet.parse(link.sent.get(0)));
        Icmpv6Message rep = Icmpv6Message.parse(Ipv6Packet.parse(link.sent.get(1)));
        assertEquals(Icmpv6Message.ECHO_REPLY, rep.type);
        assertArrayEquals(req.body, rep.body);
    }

    static <T> T done(java.util.concurrent.CompletableFuture<T> f) throws Exception {
        org.junit.jupiter.api.Assertions.assertTrue(f.isDone(), "future 还没完成");
        return f.get();
    }
}
