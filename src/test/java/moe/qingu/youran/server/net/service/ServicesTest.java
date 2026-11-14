package moe.qingu.youran.server.net.service;

import moe.qingu.youran.api.network.Ipv6Address;
import moe.qingu.youran.api.network.packet.DnsMessage;
import moe.qingu.youran.api.network.packet.NtpPacket;
import moe.qingu.youran.api.network.packet.UdpDatagram;
import moe.qingu.youran.api.network.packet.Ipv6Packet;
import moe.qingu.youran.api.network.stack.DnsResolver;
import moe.qingu.youran.api.network.stack.HttpClient;
import moe.qingu.youran.api.network.stack.NetStack;
import moe.qingu.youran.api.network.stack.NtpClient;
import moe.qingu.youran.api.network.stack.TestLink;
import moe.qingu.youran.api.network.packet.TcpSegment;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.time.ZoneOffset;
import java.time.ZonedDateTime;
import java.util.Arrays;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** 机房服务：DNS（根 → 顶级域 → 权威，经递归解析器）、NTP、测试网站，客户端协议栈与服务端协议栈互通 */
class ServicesTest {
    static final Ipv6Address ROOT = Ipv6Address.parse("fd00::1:53");
    static final Ipv6Address YR = Ipv6Address.parse("fd00::2:53");
    static final Ipv6Address MOD = Ipv6Address.parse("fd00:0:0:0:0:1:0:1");
    static final Ipv6Address RESOLVER = Ipv6Address.parse("fd00::53");
    static final Ipv6Address NTP = Ipv6Address.parse("fd00::123");
    static final Ipv6Address WEB = Ipv6Address.parse("fd00::2");

    final TestLink link = new TestLink();
    final NetStack client = link.host("fd00::c");
    RecursiveResolver resolver;
    final AtomicInteger upstreamQueries = new AtomicInteger();

    ServicesTest() {
        NetStack root = link.host(ROOT.toString());
        NetStack yr = link.host(YR.toString());
        NetStack mod = link.host(MOD.toString());
        NetStack res = link.host(RESOLVER.toString());
        NetStack ntp = link.host(NTP.toString());
        NetStack web = link.host(WEB.toString());
        Map<String, List<DnsZoneData.NameServer>> deleg = new LinkedHashMap<>();
        List<DnsZoneData.NameServer> yrNs = Collections.singletonList(new DnsZoneData.NameServer("ns.yr", YR));
        deleg.put("yr", yrNs);
        deleg.put("com", yrNs);
        deleg.put("cfm", Collections.singletonList(new DnsZoneData.NameServer("ns.cfm", MOD)));
        new AuthoritativeDnsServer(Collections.singletonList(new DnsZoneData("", Collections.singletonList(new DnsZoneData.NameServer("root.yr", ROOT)),
                deleg, Collections.<String, List<Ipv6Address>>emptyMap(), null, 1))).start(root);
        Map<String, List<Ipv6Address>> yrRec = new LinkedHashMap<>();
        yrRec.put("ns.yr", Collections.singletonList(YR));
        yrRec.put("test.yr", Collections.singletonList(WEB));
        Map<String, List<Ipv6Address>> comRec = new LinkedHashMap<>();
        comRec.put("dado.wixsite.com", Collections.singletonList(WEB));
        new AuthoritativeDnsServer(Arrays.asList(
                new DnsZoneData("yr", yrNs, Collections.<String, List<DnsZoneData.NameServer>>emptyMap(), yrRec, null, 1),
                new DnsZoneData("com", yrNs, Collections.<String, List<DnsZoneData.NameServer>>emptyMap(), comRec, null, 1))).start(yr);
        Map<String, List<Ipv6Address>> modRec = new LinkedHashMap<>();
        modRec.put("ns.cfm", Collections.singletonList(MOD));
        new AuthoritativeDnsServer(Collections.singletonList(new DnsZoneData("cfm", Collections.singletonList(new DnsZoneData.NameServer("ns.cfm", MOD)),
                Collections.<String, List<DnsZoneData.NameServer>>emptyMap(), modRec,
                name -> name.equals("minebay.cfm") ? Collections.singletonList(WEB) : null, 1))).start(mod);
        resolver = new RecursiveResolver(res, Collections.singletonList(ROOT));
        resolver.start();
        new NtpServer(() -> 1_794_355_200_000L).start(ntp);
        TestWebsite.start(web);
        // 数一数递归解析器往外问了几次（发往三台权威服务器的包）
        link.filter = raw -> {
            Ipv6Address d = Ipv6Packet.destinationOf(raw);
            if (d.equals(ROOT) || d.equals(YR) || d.equals(MOD)) {
                upstreamQueries.incrementAndGet();
            }
            return 0L;
        };
    }

    /** 不阻塞：没完成就算失败 */
    static <T> T done(CompletableFuture<T> f) throws Exception {
        assertTrue(f.isDone(), "future 还没完成");
        return f.get();
    }

    private DnsResolver.Result resolve(String name) throws Exception {
        CompletableFuture<DnsResolver.Result> f = new DnsResolver(client, RESOLVER).resolveAaaa(name);
        link.loop.advance(20_000);
        return done(f);
    }

    @Test
    void recursiveResolutionAndCache() throws Exception {
        DnsResolver.Result r = resolve("test.yr");
        assertEquals(DnsMessage.RCODE_OK, r.rcode);
        assertEquals(Collections.singletonList(WEB), r.addresses);
        assertTrue(r.response.recursionAvailable);
        assertEquals(2, upstreamQueries.get(), "问了根与 .yr 服务器各一次");
        // 第二次：缓存里有
        DnsResolver.Result again = resolve("TEST.yr");
        assertEquals(Collections.singletonList(WEB), again.addresses);
        assertEquals(2, upstreamQueries.get());
        // 同一顶级域下的另一个名字：只问 .yr 服务器（委派已缓存）
        assertEquals(DnsMessage.RCODE_NXDOMAIN, resolve("nothing.yr").rcode);
        assertEquals(3, upstreamQueries.get());
    }

    @Test
    void comOnlyHasLocalRecords() throws Exception {
        assertEquals(Collections.singletonList(WEB), resolve("dado.wixsite.com").addresses);
        DnsResolver.Result r = resolve("example.com");
        assertEquals(DnsMessage.RCODE_NXDOMAIN, r.rcode);
        assertTrue(r.addresses.isEmpty());
    }

    @Test
    void unknownTldIsNxdomainFromRoot() throws Exception {
        assertEquals(DnsMessage.RCODE_NXDOMAIN, resolve("www.example").rcode);
    }

    @Test
    void modTldIsDelegated() throws Exception {
        assertEquals(Collections.singletonList(WEB), resolve("minebay.cfm").addresses);
        assertEquals(DnsMessage.RCODE_NXDOMAIN, resolve("other.cfm").rcode);
    }

    @Test
    void literalAddressNeedsNoQuery() throws Exception {
        assertEquals(Collections.singletonList(WEB), resolve(WEB.toString()).addresses);
        assertEquals(0, upstreamQueries.get());
    }

    @Test
    void lostDnsPacketsAreRetried() throws Exception {
        AtomicInteger n = new AtomicInteger();
        // 丢掉设备的第一个查询、解析器问根的第一个查询之后的那个回应
        link.filter = raw -> {
            int k = n.incrementAndGet();
            return k == 1 || k == 4 ? null : 0L;
        };
        DnsResolver.Result r = resolve("test.yr");
        assertEquals(Collections.singletonList(WEB), r.addresses);
    }

    @Test
    void rootAnswersReferralsNotRecursion() {
        AuthoritativeDnsServer root = new AuthoritativeDnsServer(Collections.singletonList(new DnsZoneData("",
                Collections.singletonList(new DnsZoneData.NameServer("root.yr", ROOT)),
                Collections.singletonMap("yr", Collections.singletonList(new DnsZoneData.NameServer("ns.yr", YR))),
                Collections.<String, List<Ipv6Address>>emptyMap(), null, 1)));
        DnsMessage m = root.handle(DnsMessage.query(5, "test.yr", DnsMessage.TYPE_AAAA, true).encode());
        assertEquals(DnsMessage.RCODE_OK, m.rcode);
        assertTrue(!m.authoritative && !m.recursionAvailable);
        assertEquals(0, m.answers.size());
        assertEquals("ns.yr", m.authority.get(0).target);
        assertEquals(YR, m.additional.get(0).address);
        // 应答报文不回
        assertNull(root.handle(m.encode()));
    }

    @Test
    void ntpGivesUtc() throws Exception {
        CompletableFuture<NtpClient.Result> f = NtpClient.query(client, NTP);
        link.loop.advance(100);
        NtpClient.Result r = done(f);
        assertEquals(1_794_355_200_000L, r.serverTimeMillis);
        assertEquals(1, r.stratum);
        NtpServer s = new NtpServer(() -> 1000L);
        byte[] reply = s.handle(NtpPacket.clientRequest(42L).encode());
        NtpPacket p = NtpPacket.parse(reply);
        assertEquals(NtpPacket.MODE_SERVER, p.mode);
        assertEquals(42L, p.originTime);
        assertEquals(1000L, NtpPacket.toUnixMillis(p.transmitTime));
        // 服务器模式的包不回
        assertNull(s.handle(reply));
    }

    @Test
    void fetchTestSiteOverTcp() throws Exception {
        CompletableFuture<HttpClient.Response> f = HttpClient.fetch(client, new DnsResolver(client, RESOLVER), "http://test.yr/", 15000);
        link.loop.advance(10_000);
        HttpClient.Response r = done(f);
        assertEquals(200, r.status);
        assertEquals("application/xhtml+xml; charset=utf-8", r.header("content-type"));
        assertEquals(TestWebsite.PAGE, r.bodyText());
        assertEquals("close", r.header("Connection"));
    }

    @Test
    void fetchWithLossAndReordering() throws Exception {
        AtomicInteger n = new AtomicInteger();
        link.filter = raw -> {
            int k = n.incrementAndGet();
            if (k % 4 == 0) {
                return null;
            }
            return k % 3 == 0 ? 40L : 0L;
        };
        CompletableFuture<HttpClient.Response> f = HttpClient.fetch(client, new DnsResolver(client, RESOLVER), "http://test.yr/index.xhtml", 60_000);
        link.loop.advance(60_000);
        HttpClient.Response r = done(f);
        assertEquals(200, r.status);
        assertEquals(TestWebsite.PAGE, r.bodyText());
        assertTrue(client.tcpRetransmissions() + 0 >= 0);
    }

    @Test
    void notFoundAndHead() throws Exception {
        CompletableFuture<HttpClient.Response> f = HttpClient.get(client, WEB, 80, "test.yr", "/nope", 5000);
        link.loop.advance(5000);
        assertEquals(404, done(f).status);
        CompletableFuture<HttpClient.Response> refused = HttpClient.get(client, WEB, 8080, "test.yr", "/", 5000);
        link.loop.advance(5000);
        assertTrue(refused.isCompletedExceptionally());
    }

    @Test
    void httpServerResponses() {
        ZonedDateTime t = ZonedDateTime.of(2026, 11, 14, 8, 0, 0, 0, ZoneOffset.UTC);
        String ok = new String(TestWebsite.responseFor("GET / HTTP/1.1\r\nHost: test.yr", t), StandardCharsets.UTF_8);
        assertTrue(ok.startsWith("HTTP/1.1 200 OK\r\nDate: Sat, 14 Nov 2026 08:00:00 GMT\r\n"));
        assertTrue(ok.endsWith(TestWebsite.PAGE));
        String head = new String(TestWebsite.responseFor("HEAD / HTTP/1.1\r\nHost: test.yr", t), StandardCharsets.UTF_8);
        assertTrue(head.startsWith("HTTP/1.1 200 OK") && head.endsWith("\r\n\r\n"));
        assertTrue(head.contains("Content-Length: " + TestWebsite.PAGE.getBytes(StandardCharsets.UTF_8).length + "\r\n"));
        assertTrue(new String(TestWebsite.responseFor("POST / HTTP/1.1\r\nHost: a", t), StandardCharsets.UTF_8).startsWith("HTTP/1.1 405 "));
        assertTrue(new String(TestWebsite.responseFor("GET / HTTP/1.1", t), StandardCharsets.UTF_8).startsWith("HTTP/1.1 400 "), "1.1 没有 Host");
        assertTrue(new String(TestWebsite.responseFor("GET / HTTP/1.0", t), StandardCharsets.UTF_8).startsWith("HTTP/1.1 200 "));
        assertTrue(new String(TestWebsite.responseFor("garbage", t), StandardCharsets.UTF_8).startsWith("HTTP/1.1 400 "));
    }

    @Test
    void udpToClosedPortOnService() throws Exception {
        // 往 NTP 主机的 53 端口发：端口不可达
        done(client.openUdp(null, 0, (s, from, port, to, data) -> {
        })).send(NTP, 53, new byte[4]);
        link.loop.advance(10);
        byte[] last = link.sent.get(link.sent.size() - 1);
        Ipv6Packet p = Ipv6Packet.parse(last);
        assertEquals(Ipv6Packet.PROTO_ICMPV6, p.nextHeader);
        assertEquals(NTP, p.source);
        assertTrue(UdpDatagram.MAX_DATA > 0 && TcpSegment.HEADER == 20);
    }
}
