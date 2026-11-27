package moe.qingu.youran.api.network.stack;

import moe.qingu.youran.api.network.Ipv6Address;
import moe.qingu.youran.api.network.packet.Ipv6Packet;
import moe.qingu.youran.api.network.packet.TcpSegment;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayOutputStream;
import java.util.ArrayList;
import java.util.List;
import java.util.Random;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** TCP：握手、按序收发、乱序、丢包重传、关闭、RST、序号回绕 */
class TcpConnectionTest {
    private static final Ipv6Address A = Ipv6Address.parse("fd00::a");
    private static final Ipv6Address B = Ipv6Address.parse("fd00::b");

    /** 收到的数据与结束事件 */
    static class Sink implements TcpConnection.Listener {
        final ByteArrayOutputStream data = new ByteArrayOutputStream();
        boolean ended;
        int closedCalls;
        NetworkException error;

        @Override
        public void onData(TcpConnection c, byte[] d) {
            data.write(d, 0, d.length);
        }

        @Override
        public void onEnd(TcpConnection c) {
            ended = true;
        }

        @Override
        public void onClosed(TcpConnection c, NetworkException e) {
            closedCalls++;
            error = e;
        }
    }

    private static byte[] pattern(int n) {
        byte[] b = new byte[n];
        Random r = new Random(n);
        r.nextBytes(b);
        return b;
    }

    private static TcpSegment tcpOf(byte[] raw) {
        try {
            Ipv6Packet p = Ipv6Packet.parse(raw);
            return p.nextHeader == Ipv6Packet.PROTO_TCP ? TcpSegment.parse(p) : null;
        } catch (Exception e) {
            throw new AssertionError(e);
        }
    }

    /** 服务端：收下所有数据，收到 FIN 后把收到的原样发回去再关 */
    private static List<Sink> echoServer(NetStack server) {
        List<Sink> sinks = new ArrayList<>();
        server.listen(null, 80, c -> {
            Sink s = new Sink() {
                @Override
                public void onEnd(TcpConnection conn) {
                    super.onEnd(conn);
                    conn.write(data.toByteArray());
                    conn.close();
                }
            };
            sinks.add(s);
            c.setListener(s);
        });
        return sinks;
    }

    private static <T> T get(CompletableFuture<T> f) {
        assertTrue(f.isDone(), "future 还没完成");
        try {
            return f.get();
        } catch (InterruptedException | ExecutionException e) {
            throw new AssertionError(e);
        }
    }

    private static NetworkException failure(CompletableFuture<?> f) {
        assertTrue(f.isCompletedExceptionally());
        try {
            f.get();
            throw new AssertionError();
        } catch (ExecutionException e) {
            return (NetworkException) e.getCause();
        } catch (InterruptedException e) {
            throw new AssertionError(e);
        }
    }

    private void roundTrip(TestLink link, int size) {
        NetStack client = link.host("fd00::a");
        NetStack server = link.host("fd00::b");
        List<Sink> serverSinks = echoServer(server);
        Sink clientSink = new Sink();
        CompletableFuture<TcpConnection> f = client.connect(null, B, 80, clientSink);
        link.loop.advance(5000);
        TcpConnection c = get(f);
        assertEquals(TcpConnection.State.ESTABLISHED, c.state());
        byte[] payload = pattern(size);
        c.write(payload);
        c.close();
        link.loop.advance(120_000);
        assertEquals(1, serverSinks.size());
        assertArrayEquals(payload, serverSinks.get(0).data.toByteArray(), "服务端收到的");
        assertArrayEquals(payload, clientSink.data.toByteArray(), "客户端收到的回声（客户端 " + c.state() + "，结束 " + clientSink.ended
                + "，关闭 " + clientSink.closedCalls + " " + clientSink.error + "；服务端结束 " + serverSinks.get(0).ended + " 关闭 " + serverSinks.get(0).closedCalls + " " + serverSinks.get(0).error + "）");
        assertTrue(clientSink.ended);
        assertEquals(1, clientSink.closedCalls);
        assertNull(clientSink.error);
        assertEquals(1, serverSinks.get(0).closedCalls);
        assertEquals(TcpConnection.State.CLOSED, c.state());
        assertEquals(0, client.connectionCount());
        assertEquals(0, server.connectionCount());
    }

    @Test
    void handshakeTransferAndClose() {
        TestLink link = new TestLink();
        roundTrip(link, 5000);
        // 握手：SYN、SYN+ACK 带 MSS 1220；数据段不超过 1220 字节
        TcpSegment syn = tcpOf(link.sent.get(0));
        assertTrue(syn.has(TcpSegment.SYN) && !syn.has(TcpSegment.ACK));
        assertEquals(1220, syn.mss);
        TcpSegment synAck = tcpOf(link.sent.get(1));
        assertTrue(synAck.has(TcpSegment.SYN) && synAck.has(TcpSegment.ACK));
        assertEquals(syn.seq + 1, synAck.ack);
        for (byte[] raw : link.sent) {
            assertTrue(raw.length <= 1280);
            TcpSegment s = tcpOf(raw);
            assertTrue(s.data.length <= TcpConnection.MSS);
        }
    }

    @Test
    void largeTransfer() {
        roundTrip(new TestLink(), 300_000);
    }

    @Test
    void outOfOrderSegmentsAreReassembled() {
        TestLink link = new TestLink();
        AtomicInteger n = new AtomicInteger();
        // 每三个带数据的段推迟一个 30 毫秒
        link.filter = raw -> {
            TcpSegment s = tcpOf(raw);
            return s != null && s.data.length > 0 && n.incrementAndGet() % 3 == 0 ? 30L : 0L;
        };
        roundTrip(link, 100_000);
    }

    @Test
    void lostSegmentsAreRetransmitted() {
        TestLink link = new TestLink();
        AtomicInteger n = new AtomicInteger();
        List<Long> dropped = new ArrayList<>();
        // 每五个带数据的段丢一个（同一个段只丢第一次）；SYN、FIN 各丢第一次
        link.filter = raw -> {
            TcpSegment s = tcpOf(raw);
            if (s == null) {
                return 0L;
            }
            long id = s.seq * 2 + (s.sourcePort == 80 ? 1 : 0);
            boolean first = !dropped.contains(id);
            if (first && (s.has(TcpSegment.SYN) || s.has(TcpSegment.FIN) || (s.data.length > 0 && n.incrementAndGet() % 5 == 0))) {
                dropped.add(id);
                return null;
            }
            return 0L;
        };
        roundTrip(link, 50_000);
        assertTrue(dropped.size() > 5);
    }

    @Test
    void duplicatedPacketsAreHarmless() {
        TestLink link = new TestLink();
        NetStack client = link.host("fd00::a");
        NetStack server = link.host("fd00::b");
        List<Sink> serverSinks = echoServer(server);
        // 每个包都多送一份（晚 5 毫秒）
        link.filter = raw -> {
            NetStack dst = Ipv6Packet.destinationOf(raw).equals(A) ? client : server;
            link.loop.schedule(() -> dst.receive(raw), 6);
            return 0L;
        };
        Sink sink = new Sink();
        CompletableFuture<TcpConnection> f = client.connect(null, B, 80, sink);
        link.loop.advance(50);
        byte[] payload = pattern(20_000);
        get(f).write(payload);
        get(f).close();
        link.loop.advance(120_000);
        assertArrayEquals(payload, serverSinks.get(0).data.toByteArray());
        assertArrayEquals(payload, sink.data.toByteArray());
    }

    @Test
    void retransmissionRespectsTimer() {
        TestLink link = new TestLink();
        NetStack client = link.host("fd00::a");
        link.host("fd00::b");
        // 对方在，但什么都收不到
        link.filter = raw -> null;
        CompletableFuture<TcpConnection> f = client.connect(null, B, 80, null);
        link.loop.advance(999);
        assertEquals(1, link.sent.size());
        link.loop.advance(2);
        assertEquals(2, link.sent.size(), "1 秒后第一次重传 SYN");
        link.loop.advance(2000);
        assertEquals(3, link.sent.size(), "再过 2 秒（退避）第二次");
        link.loop.advance(200_000);
        assertEquals(NetworkException.Reason.TIMEOUT, failure(f).reason);
        assertEquals(1 + TcpConnection.MAX_SYN_RETRIES, link.sent.size());
        assertEquals(0, client.connectionCount());
    }

    @Test
    void closedPortRefuses() {
        TestLink link = new TestLink();
        NetStack client = link.host("fd00::a");
        link.host("fd00::b");
        CompletableFuture<TcpConnection> f = client.connect(null, B, 81, null);
        link.loop.advance(10);
        assertEquals(NetworkException.Reason.REFUSED, failure(f).reason);
        TcpSegment rst = tcpOf(link.sent.get(1));
        assertTrue(rst.has(TcpSegment.RST) && rst.has(TcpSegment.ACK));
        assertEquals(tcpOf(link.sent.get(0)).seq + 1, rst.ack);
    }

    @Test
    void abortResetsPeer() {
        TestLink link = new TestLink();
        NetStack client = link.host("fd00::a");
        NetStack server = link.host("fd00::b");
        List<Sink> sinks = echoServer(server);
        CompletableFuture<TcpConnection> f = client.connect(null, B, 80, new Sink());
        link.loop.advance(10);
        get(f).abort();
        link.loop.advance(10);
        assertEquals(1, sinks.get(0).closedCalls);
        assertEquals(NetworkException.Reason.RESET, sinks.get(0).error.reason);
        assertEquals(0, server.connectionCount());
    }

    @Test
    void sequenceNumbersWrapAround() {
        TestLink link = new TestLink();
        // 初始序号都在 2^32 前 100 处，传 10 KB 一定跨过回绕点
        Random nearWrap = new Random(1) {
            @Override
            public int nextInt() {
                return -100;
            }
        };
        NetStack client = link.host("fd00::a", nearWrap);
        NetStack server = link.host("fd00::b", nearWrap);
        List<Sink> sinks = echoServer(server);
        Sink sink = new Sink();
        CompletableFuture<TcpConnection> f = client.connect(null, B, 80, sink);
        link.loop.advance(10);
        byte[] payload = pattern(10_000);
        get(f).write(payload);
        get(f).close();
        link.loop.advance(120_000);
        assertArrayEquals(payload, sinks.get(0).data.toByteArray());
        assertArrayEquals(payload, sink.data.toByteArray());
        assertEquals(0xFFFFFF9CL, tcpOf(link.sent.get(0)).seq);
    }

    @Test
    void timeWaitHoldsTheTuple() {
        TestLink link = new TestLink();
        NetStack client = link.host("fd00::a");
        NetStack server = link.host("fd00::b");
        echoServer(server);
        Sink sink = new Sink();
        CompletableFuture<TcpConnection> f = client.connect(null, B, 80, sink);
        link.loop.advance(10);
        get(f).close();
        link.loop.advance(100);
        // 服务端先关完（收到 FIN 后回 FIN），客户端最后 ACK 后进 TIME_WAIT
        assertTrue(sink.ended);
        assertEquals(1, sink.closedCalls);
        assertEquals(TcpConnection.State.TIME_WAIT, get(f).state());
        assertEquals(1, client.connectionCount());
        link.loop.advance(TcpConnection.TIME_WAIT_MS);
        assertEquals(0, client.connectionCount());
        assertFalse(sink.error != null);
    }

    /**
     * 对方通告零窗口、一直回确认但不收（像 079 上的程序很久不读）：探测照退避一直发，不因为探测没确认而断开
     * （RFC 1122 第 4.2.2.17 节）；窗口开了以后探测的那 1 字节马上重发，不等重传计时器。对方由测试手工扮演（Java 栈自己不会通告 0）。
     */
    @Test
    void zeroWindowProbingKeepsTheConnectionAndResumesAtOnce() {
        TestLink link = new TestLink();
        NetStack client = link.host("fd00::a");
        long[] peer = {0, 0}; // 对方的 ISS + 1、确认到的序号
        int[] window = {0};
        List<TcpSegment> got = new ArrayList<>();
        link.filter = raw -> {
            TcpSegment seg = tcpOf(raw);
            if (seg == null || !Ipv6Packet.destinationOf(raw).equals(B)) {
                return 0L;
            }
            got.add(seg);
            TcpSegment reply;
            if (seg.has(TcpSegment.SYN)) {
                peer[0] = 5001;
                peer[1] = (seg.seq + 1) & 0xFFFFFFFFL;
                reply = new TcpSegment(80, seg.sourcePort, 5000, peer[1], TcpSegment.SYN | TcpSegment.ACK, window[0], 1220, new byte[0]);
            } else if (window[0] == 0) {
                // 零窗口：数据不收，回确认报窗口 0
                reply = new TcpSegment(80, seg.sourcePort, peer[0], peer[1], TcpSegment.ACK, 0, -1, new byte[0]);
            } else {
                if (seg.seq == peer[1]) {
                    peer[1] = (peer[1] + seg.data.length) & 0xFFFFFFFFL;
                }
                reply = new TcpSegment(80, seg.sourcePort, peer[0], peer[1], TcpSegment.ACK, window[0], -1, new byte[0]);
            }
            byte[] back = reply.toPacket(B, A).encode();
            link.loop.schedule(() -> client.receive(back), 1);
            return null;
        };
        CompletableFuture<TcpConnection> f = client.connect(null, B, 80, new Sink());
        link.loop.advance(50);
        TcpConnection c = get(f);
        byte[] payload = pattern(3000);
        c.write(payload);
        // 半小时，远超 MAX_RETRIES 次重传的时间
        link.loop.advance(30 * 60_000L);
        assertEquals(TcpConnection.State.ESTABLISHED, c.state(), "对方一直回确认，零窗口不该断开");
        long probes = got.stream().filter(g -> g.data.length == 1).count();
        assertTrue(probes > TcpConnection.MAX_RETRIES, "探测发了 " + probes + " 次");
        // 窗口开了：对方发一个窗口更新
        window[0] = 65535;
        got.clear();
        long una = peer[1];
        byte[] update = new TcpSegment(80, c.localPort(), peer[0], peer[1], TcpSegment.ACK, 65535, -1, new byte[0]).toPacket(B, A).encode();
        client.receive(update);
        link.loop.advance(1);
        assertFalse(got.isEmpty(), "窗口开了马上就发，不等重传计时器");
        assertEquals(una, got.get(0).seq, "先重发探测的那 1 字节（没确认的最早那段）");
        link.loop.advance(5000);
        assertEquals(3000, sentTotal(got), "窗口开了以后 3000 字节都发出去了");
        assertEquals((una + 3000) & 0xFFFFFFFFL, peer[1], "对方按序全收到了");
    }

    private static long sentTotal(List<TcpSegment> segs) {
        java.util.Set<Long> seen = new java.util.HashSet<>();
        long n = 0;
        for (TcpSegment g : segs) {
            if (g.data.length > 0 && seen.add(g.seq)) {
                n += g.data.length;
            }
        }
        return n;
    }
}
