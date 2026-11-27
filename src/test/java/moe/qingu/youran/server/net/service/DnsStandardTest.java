package moe.qingu.youran.server.net.service;

import moe.qingu.youran.api.network.Ipv6Address;
import moe.qingu.youran.api.network.dns.DefaultRegistrar;
import moe.qingu.youran.api.network.dns.DnsRegistry;
import moe.qingu.youran.api.network.dns.DnsServer;
import moe.qingu.youran.api.network.packet.DnsMessage;
import moe.qingu.youran.api.network.packet.Ipv6Packet;
import moe.qingu.youran.api.network.packet.MalformedPacketException;
import moe.qingu.youran.api.network.packet.UdpDatagram;
import moe.qingu.youran.api.network.stack.DnsResolver;
import moe.qingu.youran.api.network.stack.NetStack;
import moe.qingu.youran.api.network.stack.TcpConnection;
import moe.qingu.youran.api.network.stack.TestLink;
import moe.qingu.youran.api.network.stack.UdpSocket;
import moe.qingu.youran.network.DnsState;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Random;
import java.util.concurrent.CompletableFuture;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * DNS 的标准做法（服主 11-27）：权威服务器、递归解析器都听 TCP 53（两字节长度前缀，一条连接连续问）；超过上限的 UDP 应答截断（TC），
 * 存根与递归都改用 TCP 重问同一台、截断的不用不缓存；EDNS(0) 的 OPT（载荷 1232）；递归解析器每个查询用随机的源端口。
 * 用直连的测试网（{@link TestLink}）、手动事件循环、固定种子。
 */
class DnsStandardTest {
    static final Ipv6Address ROOT = Ipv6Address.parse("fd00::1:53");
    static final Ipv6Address TLD = Ipv6Address.parse("fd00::2:53");
    static final Ipv6Address SITE = Ipv6Address.parse("fd00::c:53");
    static final Ipv6Address RESOLVER = Ipv6Address.parse("fd00::53");
    static final Ipv6Address WEB = Ipv6Address.parse("fd00::80");
    /** 两段各 1000 字节的 TXT：应答超过 1232 字节 */
    static final String BIG_A = repeat('a', 1000);
    static final String BIG_B = repeat('b', 1000);

    @TempDir
    File dir;
    TestLink link;
    NetStack client;
    NetStack resolverStack;
    RecursiveResolver resolver;
    DnsServer site;
    /** 发给权威服务器的：UDP 还是 TCP */
    final List<String> upstream = new ArrayList<>();

    static String repeat(char c, int n) {
        char[] a = new char[n];
        Arrays.fill(a, c);
        return new String(a);
    }

    @BeforeEach
    void setUp() {
        DnsState.reset();
        link = new TestLink();
        client = link.host("fd00::c");
        DnsState.bindRoot(DnsServer.start(link.host(ROOT.toString())));
        DnsServer tld = DnsServer.start(link.host(TLD.toString()));
        DefaultRegistrar yr = new DefaultRegistrar("youranmod", "yr", tld, new File(dir, "yr.json"));
        DnsRegistry.register("yr", yr, Collections.singletonList(TLD));
        site = DnsServer.start(link.host(SITE.toString()));
        site.addZone("big.yr");
        site.setAddresses("big.yr", Collections.singletonList(WEB));
        site.setTexts("big.yr", Arrays.asList(BIG_A, BIG_B));
        site.setTexts("small.big.yr", Collections.singletonList("hi"));
        yr.register("big.yr", "youranmod", Collections.singletonList(SITE));
        resolverStack = link.host(RESOLVER.toString(), new Random(77));
        resolver = new RecursiveResolver(resolverStack, DnsState.rootServers(), new Random(78));
        resolver.start();
        link.filter = raw -> {
            Ipv6Address d = Ipv6Packet.destinationOf(raw);
            if (d.equals(SITE) && Ipv6Packet.sourceOf(raw).equals(RESOLVER)) {
                upstream.add((raw[6] & 0xFF) == Ipv6Packet.PROTO_TCP ? "TCP" : "UDP");
            }
            return 0L;
        };
        link.loop.drain();
    }

    static <T> T done(CompletableFuture<T> f) throws Exception {
        assertTrue(f.isDone(), "future 还没完成");
        return f.get();
    }

    static List<String> texts(DnsMessage m) {
        List<String> out = new ArrayList<>();
        for (DnsMessage.Record r : m.answers) {
            if (r.type == DnsMessage.TYPE_TXT) {
                out.add(r.text());
            }
        }
        return out;
    }

    /** 直接发一个 UDP 查询给 {@code server}，收原样的应答 */
    DnsMessage udpQuery(Ipv6Address server, DnsMessage q) throws Exception {
        CompletableFuture<DnsMessage> f = new CompletableFuture<>();
        UdpSocket s = client.openUdp(null, 0, (socket, from, fromPort, to, data) -> {
            try {
                f.complete(DnsMessage.parse(data));
            } catch (MalformedPacketException e) {
                f.completeExceptionally(e);
            }
        }).get();
        s.send(server, 53, q.encode());
        link.loop.advance(5000);
        return done(f);
    }

    @Test
    void bigAnswersAreTruncatedOverUdpAndFetchedOverTcp() throws Exception {
        // 权威服务器：带 EDNS 问，UDP 上超过 1232 字节，截断（只留问题与 OPT），置 TC
        DnsMessage truncated = udpQuery(SITE, DnsMessage.query(1, "big.yr", DnsMessage.TYPE_TXT, false).withEdns(DnsMessage.Edns.standard()));
        assertTrue(truncated.truncated);
        assertTrue(truncated.answers.isEmpty());
        assertNotNull(truncated.edns, "回答带 OPT");
        assertEquals(DnsMessage.EDNS_PAYLOAD, truncated.edns.payloadSize);
        // 存根解析器经递归解析器问：递归往权威服务器先 UDP（截断）再 TCP；递归回给存根的 UDP 也截断，存根再改用 TCP
        DnsResolver stub = new DnsResolver(client, RESOLVER);
        CompletableFuture<DnsMessage> f = stub.query("big.yr", DnsMessage.TYPE_TXT);
        link.loop.advance(20_000);
        DnsMessage m = done(f);
        assertEquals(DnsMessage.RCODE_OK, m.rcode);
        assertFalse(m.truncated);
        assertEquals(new HashSet<>(Arrays.asList(BIG_A, BIG_B)), new HashSet<>(texts(m)), "拿到完整的应答");
        assertEquals(1, stub.tcpFallbacks(), "存根收到截断，改用 TCP 问同一台递归解析器");
        assertTrue(stub.lastOverTcp());
        assertEquals(1, resolver.tcpRetries, "递归解析器收到截断，改用 TCP 问同一台权威服务器");
        assertTrue(upstream.contains("UDP") && upstream.contains("TCP"), String.valueOf(upstream));
        // 再问一次：用的是缓存里完整的那份（从 TCP 拿到的），不再往外问
        int before = upstream.size();
        f = stub.query("big.yr", DnsMessage.TYPE_TXT);
        link.loop.advance(20_000);
        assertEquals(2, texts(done(f)).size());
        assertEquals(before, upstream.size(), "缓存命中");
    }

    @Test
    void truncatedAnswersAreNotUsedOrCached() throws Exception {
        // 一台只会回截断应答、不听 TCP 的“权威服务器”
        NetStack liar = link.host("fd00::d:53");
        liar.openUdp(null, 53, (socket, from, fromPort, to, data) -> {
            try {
                DnsMessage q = DnsMessage.parse(data);
                DnsMessage r = new DnsMessage(q.id, true, 0, true, true, false, false, DnsMessage.RCODE_OK, q.questions,
                        Collections.singletonList(DnsMessage.Record.aaaa(q.question().name, 300, WEB)), Collections.<DnsMessage.Record>emptyList(),
                        Collections.<DnsMessage.Record>emptyList());
                socket.send(to, from, fromPort, r.encode());
            } catch (MalformedPacketException e) {
                // 不回
            }
        });
        DefaultRegistrar yr = (DefaultRegistrar) DnsRegistry.registrar("yr");
        yr.register("liar.yr", "youranmod", Collections.singletonList(Ipv6Address.parse("fd00::d:53")));
        link.loop.drain();
        DnsResolver stub = new DnsResolver(client, RESOLVER);
        CompletableFuture<DnsResolver.Result> f = stub.resolveAaaa("liar.yr");
        link.loop.advance(30_000);
        DnsResolver.Result r = done(f);
        assertEquals(DnsMessage.RCODE_SERVFAIL, r.rcode, "截断的应答（虽然带了一条 AAAA）不用；TCP 问不到，回 SERVFAIL");
        assertTrue(r.addresses.isEmpty());
        assertFalse(resolver.isCached("liar.yr", DnsMessage.TYPE_AAAA), "截断的不进缓存");
        // 再问：照样往外问（没有缓存住截断的那份）
        int[] asked = new int[1];
        link.filter = raw -> {
            if (Ipv6Packet.destinationOf(raw).equals(Ipv6Address.parse("fd00::d:53")) && (raw[6] & 0xFF) == Ipv6Packet.PROTO_UDP) {
                asked[0]++;
            }
            return 0L;
        };
        f = stub.resolveAaaa("liar.yr");
        link.loop.advance(30_000);
        assertEquals(DnsMessage.RCODE_SERVFAIL, done(f).rcode);
        assertTrue(asked[0] >= 1, "又去问了");
    }

    @Test
    void oneTcpConnectionCarriesSeveralQueries() throws Exception {
        ByteArrayOutputStream got = new ByteArrayOutputStream();
        CompletableFuture<TcpConnection> c = client.connect(null, SITE, 53, (conn, data) -> got.write(data, 0, data.length));
        link.loop.advance(100);
        TcpConnection conn = done(c);
        // 连着发三个（流水线），再隔一会儿发第四个
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        for (int i = 1; i <= 3; i++) {
            byte[] f = DnsMessage.tcpFrame(DnsMessage.query(i, i == 2 ? "big.yr" : "small.big.yr", DnsMessage.TYPE_TXT, false).encode());
            out.write(f, 0, f.length);
        }
        conn.write(out.toByteArray());
        link.loop.advance(500);
        conn.write(DnsMessage.tcpFrame(DnsMessage.query(4, "big.yr", DnsMessage.TYPE_AAAA, false).encode()));
        link.loop.advance(500);
        List<DnsMessage> answers = frames(got.toByteArray());
        assertEquals(4, answers.size(), "一条连接上问了四次，都回了");
        assertEquals(Arrays.asList(1, 2, 3, 4), Arrays.asList(answers.get(0).id, answers.get(1).id, answers.get(2).id, answers.get(3).id));
        assertFalse(answers.get(1).truncated, "TCP 上不截断");
        assertEquals(2, texts(answers.get(1)).size());
        assertEquals(TcpConnection.State.ESTABLISHED, conn.state(), "回完还连着，可以接着问");
        // 空闲十秒：服务器关掉
        link.loop.advance(11_000);
        assertTrue(conn.state() != TcpConnection.State.ESTABLISHED);
    }

    static List<DnsMessage> frames(byte[] b) throws MalformedPacketException {
        List<DnsMessage> out = new ArrayList<>();
        int i = 0;
        while (i + 2 <= b.length) {
            int len = ((b[i] & 0xFF) << 8) | (b[i + 1] & 0xFF);
            out.add(DnsMessage.parse(Arrays.copyOfRange(b, i + 2, i + 2 + len)));
            i += 2 + len;
        }
        return out;
    }

    @Test
    void ednsIsEchoedAndLimitsTheUdpSize() throws Exception {
        // 不带 EDNS：回答也不带，上限 512（这个回答 600 多字节以上就截断）
        DnsMessage plain = udpQuery(SITE, DnsMessage.query(5, "big.yr", DnsMessage.TYPE_TXT, false));
        assertNull(plain.edns);
        assertTrue(plain.truncated);
        DnsMessage small = udpQuery(SITE, DnsMessage.query(6, "small.big.yr", DnsMessage.TYPE_TXT, false));
        assertNull(small.edns);
        assertFalse(small.truncated);
        // 带 EDNS：回答带 OPT，载荷 1232
        DnsMessage e = udpQuery(SITE, DnsMessage.query(7, "small.big.yr", DnsMessage.TYPE_TXT, false).withEdns(DnsMessage.Edns.standard()));
        assertNotNull(e.edns);
        assertEquals(1232, e.edns.payloadSize);
        assertEquals(0, e.edns.version);
        // 版本不是 0：BADVERS（扩展响应码 16）
        DnsMessage bad = udpQuery(SITE, DnsMessage.query(8, "small.big.yr", DnsMessage.TYPE_TXT, false)
                .withEdns(new DnsMessage.Edns(1232, 0, 1, false, null)));
        assertEquals(DnsMessage.RCODE_BADVERS, bad.fullRcode());
        // 递归解析器同样
        DnsMessage viaResolver = udpQuery(RESOLVER, DnsMessage.query(9, "small.big.yr", DnsMessage.TYPE_TXT, true).withEdns(DnsMessage.Edns.standard()));
        assertNotNull(viaResolver.edns);
        assertEquals(Collections.singletonList("hi"), texts(viaResolver));
        // 存根发的查询带 OPT
        List<byte[]> sent = new ArrayList<>();
        link.filter = raw -> {
            if (Ipv6Packet.destinationOf(raw).equals(RESOLVER) && (raw[6] & 0xFF) == Ipv6Packet.PROTO_UDP) {
                sent.add(raw);
            }
            return 0L;
        };
        new DnsResolver(client, RESOLVER).query("small.big.yr", DnsMessage.TYPE_TXT);
        link.loop.advance(1000);
        DnsMessage q = DnsMessage.parse(UdpDatagram.parse(Ipv6Packet.parse(sent.get(0))).data);
        assertNotNull(q.edns);
        assertEquals(1232, q.edns.payloadSize);
    }

    @Test
    void resolverUsesARandomSourcePortPerQuery() throws Exception {
        for (String n : Arrays.asList("a.big.yr", "b.big.yr", "c.big.yr", "d.big.yr", "e.big.yr")) {
            site.setTexts(n, Collections.singletonList(n));
        }
        link.loop.drain();
        for (String n : Arrays.asList("a.big.yr", "b.big.yr", "c.big.yr", "d.big.yr", "e.big.yr")) {
            new DnsResolver(client, RESOLVER).query(n, DnsMessage.TYPE_TXT);
        }
        link.loop.advance(20_000);
        List<Integer> ports = new ArrayList<>(resolver.recentPorts);
        assertTrue(ports.size() >= 7, "根、顶级域、五个名字：" + ports);
        assertEquals(ports.size(), new HashSet<>(ports).size(), "每个查询一个新的随机端口：" + ports);
        for (int p : ports) {
            assertTrue(p >= 49152 && p <= 65535);
        }
    }
}
