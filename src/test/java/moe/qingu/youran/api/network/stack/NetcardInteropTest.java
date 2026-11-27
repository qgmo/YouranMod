package moe.qingu.youran.api.network.stack;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import moe.qingu.youran.api.network.Ipv6Address;
import moe.qingu.youran.api.network.packet.DnsMessage;
import moe.qingu.youran.api.network.packet.Icmpv6Message;
import moe.qingu.youran.api.network.packet.Ipv6Packet;
import moe.qingu.youran.api.network.packet.MalformedPacketException;
import moe.qingu.youran.api.network.packet.TcpSegment;
import moe.qingu.youran.api.network.packet.UdpDatagram;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.TreeMap;

import static moe.qingu.youran.api.network.stack.NetcardInterop.Recorder;
import static moe.qingu.youran.api.network.stack.NetcardInterop.Scenario;
import static moe.qingu.youran.api.network.stack.NetcardInterop.bytes;
import static moe.qingu.youran.api.network.stack.NetcardInterop.text;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

/**
 * 与 079 服务的网卡固件（Go 的 netcard）互通（2.3.1.0 第 1 期，计划验收“互通”；格式与比法见 {@link NetcardInterop}）：
 * <ul>
 *     <li>Java 造的：握手、数据、FIN、RST（没人听、对方断）、零窗口、ICMPv6（回音、端口不可达）、DNS 查询，记成
 *     {@code src/test/resources/interop/netcard/java-made.json}；这里每次重新跑一遍，和提交着的逐字节一样（改了 Java 栈要更新时加
 *     环境变量 {@code INTEROP_UPDATE=1}，再把文件抄一份到 079 服务那边 netcard 的 {@code testdata/interop/}，Go 那边的测试回放它）；</li>
 *     <li>Go 造的（{@code go-made.json}，从 079 服务那边抄来）：每个包 Java 都解得开（校验和、首部），Java 栈扮演 subject 时按同样的顺序回应。</li>
 * </ul>
 * 不靠真实时间：手动事件循环；随机数固定种子。
 */
class NetcardInteropTest {
    static final Ipv6Address A = Ipv6Address.parse("fd00:1::a");
    static final Ipv6Address B = Ipv6Address.parse("fd00:2::b");
    static final Ipv6Address NAME = Ipv6Address.parse("fd00:1::80");
    static final String REQUEST = "GET / HTTP/1.0\r\nHost: b\r\n\r\n";
    static final String RESPONSE = "HTTP/1.0 200 OK\r\nContent-Type: text/plain\r\n\r\nhello from b\r\n";

    static class Sink implements TcpConnection.Listener {
        final StringBuilder data = new StringBuilder();
        boolean ended;
        NetworkException error;
        boolean closed;

        @Override
        public void onData(TcpConnection c, byte[] d) {
            data.append(text(d));
        }

        @Override
        public void onEnd(TcpConnection c) {
            ended = true;
        }

        @Override
        public void onClosed(TcpConnection c, NetworkException e) {
            closed = true;
            error = e;
        }
    }

    // ---------------------------------------------------------------- Java 造的

    /** 握手、请求、回应、b 先关（FIN）、a 再关 */
    static Scenario tcpServer() {
        Scenario sc = new Scenario("tcp_server", A, B, "b", 4096);
        Recorder r = new Recorder(sc);
        NetStack a = r.stack(A, 1);
        NetStack b = r.stack(B, 2);
        r.act("listen", "port", 80);
        b.listen(null, 80, c -> c.setListener(new TcpConnection.Listener() {
            @Override
            public void onData(TcpConnection conn, byte[] d) {
                r.act("read", "data", text(d));
                r.act("send", "data", RESPONSE);
                conn.write(bytes(RESPONSE));
                r.act("close");
                conn.close();
            }
        }));
        Sink s = new Sink() {
            @Override
            public void onEnd(TcpConnection c) {
                super.onEnd(c);
                c.close();
            }
        };
        a.connect(null, B, 80, s).thenAccept(c -> c.write(bytes(REQUEST)));
        r.run(1000);
        assertEquals(RESPONSE, s.data.toString());
        return sc;
    }

    /** b 主动连 a、发、收、a 先关、b 再关 */
    static Scenario tcpClient() {
        Scenario sc = new Scenario("tcp_client", A, B, "b", 4096);
        Recorder r = new Recorder(sc);
        NetStack a = r.stack(A, 3);
        NetStack b = r.stack(B, 4);
        a.listen(null, 80, c -> c.setListener(new TcpConnection.Listener() {
            @Override
            public void onData(TcpConnection conn, byte[] d) {
                conn.write(bytes("pong:" + text(d)));
                conn.close();
            }
        }));
        r.act("connect", "addr", A, "port", 80);
        b.connect(null, A, 80, new TcpConnection.Listener() {
            @Override
            public void onData(TcpConnection c, byte[] d) {
                r.act("read", "data", text(d));
            }

            @Override
            public void onEnd(TcpConnection c) {
                r.act("close");
                c.close();
            }
        }).thenAccept(c -> {
            r.act("send", "data", "ping");
            c.write(bytes("ping"));
        });
        r.run(1000);
        return sc;
    }

    /** a 连 b 上没人听的口：b 回 RST */
    static Scenario tcpRstClosed() {
        Scenario sc = new Scenario("tcp_rst_closed", A, B, "b", 4096);
        Recorder r = new Recorder(sc);
        NetStack a = r.stack(A, 5);
        r.stack(B, 6);
        boolean[] refused = {false};
        a.connect(null, B, 81, null).whenComplete((c, e) -> refused[0] = e != null);
        r.run(1000);
        assertTrue(refused[0]);
        return sc;
    }

    /** 连上、发一点、a 断（RST）：b 不回，程序知道被重置了 */
    static Scenario tcpRstAbort() {
        Scenario sc = new Scenario("tcp_rst_abort", A, B, "b", 4096);
        Recorder r = new Recorder(sc);
        NetStack a = r.stack(A, 7);
        NetStack b = r.stack(B, 8);
        r.act("listen", "port", 80);
        b.listen(null, 80, c -> c.setListener(new TcpConnection.Listener() {
            @Override
            public void onData(TcpConnection conn, byte[] d) {
                r.act("read", "data", text(d));
            }

            @Override
            public void onClosed(TcpConnection conn, NetworkException e) {
                if (e != null) {
                    r.act("expect_reset");
                }
            }
        }));
        a.connect(null, B, 80, null).thenAccept(c -> {
            c.write(bytes("x"));
            r.loop.schedule(c::abort, 20);
        });
        r.run(1000);
        return sc;
    }

    /**
     * 零窗口：a（Java 栈）往 b 发 300 字节；b 的收缓冲 256 字节、程序先不读，窗口到 0。Java 栈的接收窗口固定 65535、不会通告 0，所以 b 用 Java 的
     * {@link TcpSegment} 编码照 RFC 9293 手工回（窗口为 0 时收到的数据不收、回 ACK；乱序的先存着、回重复 ACK）。
     * Java 栈的零窗口探测是 1 字节新数据，窗口打开后从探测之后接着发，探测的那 1 字节等重传计时器补上（这一段记的就是这个样子）。
     */
    static Scenario tcpZeroWindow() {
        Scenario sc = new Scenario("tcp_zero_window", A, B, "b", 256);
        Recorder r = new Recorder(sc);
        NetStack a = r.stack(A, 9);
        ZeroWindowReceiver b = new ZeroWindowReceiver(r, 256, 300);
        r.host(B, b);
        r.act("listen", "port", 80);
        byte[] data = new byte[300];
        for (int i = 0; i < data.length; i++) {
            data[i] = (byte) ('A' + i % 26);
        }
        a.connect(null, B, 80, null).thenAccept(c -> c.write(data));
        r.run(5000);
        assertEquals(text(data), b.read.toString(), "300 字节都到了");
        return sc;
    }

    /** b：收缓冲 n 字节，程序收到第一个零窗口探测后读走已收到的（窗口打开），之后的不再读 */
    static final class ZeroWindowReceiver implements java.util.function.Consumer<byte[]> {
        final Recorder r;
        final int size;
        long rcvNxt;
        long sndNxt = 7_000_000;
        int aPort;
        final StringBuilder buffered = new StringBuilder();
        final StringBuilder read = new StringBuilder();
        final TreeMap<Long, byte[]> ooo = new TreeMap<>();
        boolean readOnce;
        boolean finalRead;
        final int total;

        ZeroWindowReceiver(Recorder r, int size, int total) {
            this.r = r;
            this.size = size;
            this.total = total;
        }

        int free() {
            return size - buffered.length();
        }

        void send(int flags, int mss) {
            TcpSegment s = new TcpSegment(80, aPort, sndNxt, rcvNxt, flags, free(), mss, new byte[0]);
            r.send(B, s.toPacket(B, A).encode());
        }

        @Override
        public void accept(byte[] raw) {
            TcpSegment s;
            try {
                s = TcpSegment.parse(Ipv6Packet.parse(raw));
            } catch (MalformedPacketException e) {
                throw new AssertionError(e);
            }
            if (s.has(TcpSegment.SYN)) {
                aPort = s.sourcePort;
                rcvNxt = (s.seq + 1) & 0xFFFFFFFFL;
                send(TcpSegment.SYN | TcpSegment.ACK, TcpConnection.MSS);
                sndNxt++;
                return;
            }
            if (s.data.length == 0) {
                return;
            }
            boolean probe = free() == 0 && s.seq == rcvNxt && s.data.length == 1;
            if (s.seq == rcvNxt && free() > 0) {
                int n = Math.min(free(), s.data.length);
                buffered.append(text(Arrays.copyOf(s.data, n)));
                rcvNxt = (rcvNxt + n) & 0xFFFFFFFFL;
                for (java.util.Map.Entry<Long, byte[]> e; (e = ooo.firstEntry()) != null && e.getKey() <= rcvNxt; ) {
                    ooo.remove(e.getKey());
                    int skip = (int) (rcvNxt - e.getKey());
                    if (skip < e.getValue().length) {
                        byte[] rest = Arrays.copyOfRange(e.getValue(), skip, e.getValue().length);
                        buffered.append(text(rest));
                        rcvNxt = (rcvNxt + rest.length) & 0xFFFFFFFFL;
                    }
                }
            } else if (s.seq > rcvNxt && s.seq - rcvNxt < free()) {
                ooo.put(s.seq, s.data);
            }
            send(TcpSegment.ACK, -1);
            if (probe && !readOnce) {
                readOnce = true;
                r.loop.schedule(() -> {
                    r.act("read", "data", buffered.toString());
                    read.append(buffered);
                    buffered.setLength(0);
                    send(TcpSegment.ACK, -1);
                }, 10);
            }
            if (readOnce && !finalRead && ooo.isEmpty() && read.length() + buffered.length() == total) {
                // 全到了：程序把剩下的读走（窗口开得不多，不用通告）
                finalRead = true;
                r.loop.schedule(() -> {
                    r.act("read", "data", buffered.toString());
                    read.append(buffered);
                    buffered.setLength(0);
                }, 10);
            }
        }
    }

    /** a ping b（b 回）；b ping a（a 回） */
    static Scenario icmpEcho() {
        Scenario sc = new Scenario("icmp_echo", A, B, "b", 4096);
        Recorder r = new Recorder(sc);
        NetStack a = r.stack(A, 10);
        NetStack b = r.stack(B, 11);
        a.ping(null, B, 8, 5000);
        r.run(100);
        r.act("ping", "addr", A, "seq", 1);
        b.ping(null, A, 8, 5000).thenAccept(res -> {
            if (res.status == PingResult.Status.REPLY) {
                r.act("expect_ping", "seq", 1);
            }
        });
        r.run(1000);
        return sc;
    }

    /** a 往 b 没人听的 UDP 口发：b 回 ICMPv6 端口不可达 */
    static Scenario udpPortUnreachable() {
        Scenario sc = new Scenario("udp_port_unreachable", A, B, "b", 4096);
        Recorder r = new Recorder(sc);
        NetStack a = r.stack(A, 12);
        r.stack(B, 13);
        a.openUdp(null, 40000, (s, from, port, to, d) -> {
        }).thenAccept(s -> s.send(B, 9999, bytes("hello?")));
        r.run(1000);
        return sc;
    }

    /** b 经递归解析器 a 查 test.yr 的 AAAA（a 用 Java 的 DnsMessage 回） */
    static Scenario dnsLookup() {
        Scenario sc = new Scenario("dns_lookup", A, B, "b", 4096);
        Recorder r = new Recorder(sc);
        NetStack a = r.stack(A, 14);
        NetStack b = r.stack(B, 15);
        a.openUdp(null, DnsMessage.PORT, (s, from, port, to, d) -> {
            try {
                DnsMessage q = DnsMessage.parse(d);
                DnsMessage ans = DnsMessage.reply(q, false, true, DnsMessage.RCODE_OK,
                        Collections.singletonList(DnsMessage.Record.aaaa(q.question().name, 60, NAME)), Collections.emptyList(), Collections.emptyList());
                s.send(to, from, port, ans.encode());
            } catch (MalformedPacketException e) {
                throw new AssertionError(e);
            }
        });
        r.run(10);
        r.act("lookup", "name", "test.yr");
        new DnsResolver(b, A).resolveAaaa("test.yr").thenAccept(res -> r.act("expect_lookup", "addr", res.addresses.get(0)));
        r.run(1000);
        return sc;
    }

    static List<Scenario> javaMade() {
        return Arrays.asList(tcpServer(), tcpClient(), tcpRstClosed(), tcpRstAbort(), tcpZeroWindow(), icmpEcho(), udpPortUnreachable(), dnsLookup());
    }

    static final String JAVA_NOTE = "YouranMod Java 栈造的（NetcardInteropTest 生成；零窗口那段的接收方用 Java 的 TcpSegment 编码手工回）。"
            + "079 的 netcard 扮演 subject 回放。";

    static String resource(String name) throws IOException {
        try (InputStream in = NetcardInteropTest.class.getResourceAsStream("/interop/netcard/" + name)) {
            if (in == null) {
                return null;
            }
            java.io.ByteArrayOutputStream b = new java.io.ByteArrayOutputStream();
            byte[] buf = new byte[8192];
            for (int n; (n = in.read(buf)) > 0; ) {
                b.write(buf, 0, n);
            }
            return new String(b.toByteArray(), StandardCharsets.UTF_8);
        }
    }

    /** Java 造的向量：重新跑一遍和提交着的一样（Java 栈改了就要更新、抄给 Go 那边） */
    @Test
    void javaMadeVectorsAreCurrent() throws IOException {
        String now = NetcardInterop.file("youranmod-java", JAVA_NOTE, javaMade());
        if ("1".equals(System.getenv("INTEROP_UPDATE"))) {
            Path p = Paths.get("src", "test", "resources", "interop", "netcard", "java-made.json");
            Files.createDirectories(p.getParent());
            Files.write(p, now.getBytes(StandardCharsets.UTF_8));
            return;
        }
        String committed = resource("java-made.json");
        assertEquals(committed, now, "Java 栈造的向量变了：确认是有意改的以后用环境变量 INTEROP_UPDATE=1 重新生成，并抄一份给 079 服务那边 netcard 的 testdata/interop/");
    }

    /** Java 造的向量自己回放（Java 栈扮演 subject）也对得上：比法本身没问题 */
    @Test
    void javaMadeVectorsReplayOnJava() {
        for (Scenario sc : javaMade()) {
            if (sc.name.equals("tcp_zero_window")) {
                continue; // 接收方是手工造的（Java 栈不会通告 0 窗口），Java 栈扮演不了
            }
            List<String> problems = new NetcardInterop.Replayer(sc).run();
            assertTrue(problems.isEmpty(), sc.name + "：" + problems);
        }
    }

    private static List<Scenario> goMade() throws IOException {
        String text = resource("go-made.json");
        if (text == null) {
            fail("没有 go-made.json（从 079 服务那边 netcard 的 testdata/interop/ 抄来）");
        }
        return NetcardInterop.parse(text);
    }

    /** Go 造的每个包 Java 都解得开：IPv6 首部、上层校验和、TCP 选项、ICMPv6、UDP、DNS 报文（含 EDNS 的 OPT） */
    @Test
    void goMadePacketsParse() throws IOException {
        int n = 0;
        for (Scenario sc : goMade()) {
            for (JsonElement e : sc.steps) {
                JsonObject st = e.getAsJsonObject();
                if (!st.has("hex")) {
                    continue;
                }
                byte[] raw = NetcardInterop.unhex(st.get("hex").getAsString());
                try {
                    Ipv6Packet p = Ipv6Packet.parse(raw);
                    switch (p.nextHeader) {
                        case Ipv6Packet.PROTO_TCP:
                            TcpSegment.parse(p);
                            break;
                        case Ipv6Packet.PROTO_ICMPV6:
                            Icmpv6Message.parse(p);
                            break;
                        case Ipv6Packet.PROTO_UDP: {
                            UdpDatagram u = UdpDatagram.parse(p);
                            if (u.sourcePort == DnsMessage.PORT || u.destinationPort == DnsMessage.PORT) {
                                DnsMessage.parse(u.data);
                            }
                            break;
                        }
                        default:
                            fail(sc.name + "：不认得的下一首部 " + p.nextHeader);
                    }
                } catch (MalformedPacketException ex) {
                    fail(sc.name + " 第 " + n + " 个包解不开：" + ex.getMessage());
                }
                n++;
            }
        }
        assertTrue(n > 40, "包太少：" + n);
    }

    /** Go 造的对话：Java 栈扮演 subject，按同样的顺序回应 */
    @Test
    void goMadeVectorsReplayOnJava() throws IOException {
        List<String> all = new ArrayList<>();
        for (Scenario sc : goMade()) {
            NetcardInterop.Replayer r = new NetcardInterop.Replayer(sc);
            for (String p : r.run()) {
                all.add(sc.name + "：" + p);
            }
            // 已知差异只该出现在零窗口那一段（Java 栈的零窗口探测是 1 字节新数据，见 Replayer#knownDifference）
            if (!r.known.isEmpty() && !sc.name.equals("tcp_zero_window")) {
                all.add(sc.name + "：不该有的已知差异 " + r.known);
            }
            if (sc.name.equals("tcp_zero_window")) {
                assertTrue(!r.known.isEmpty(), "Java 栈的零窗口探测改了？已知差异没出现：更新 Replayer#knownDifference 与实现记录");
            }
        }
        assertTrue(all.isEmpty(), String.join("\n", all));
    }
}
