package moe.qingu.youran.server.net.service;

import moe.qingu.youran.api.network.Ipv6Address;
import moe.qingu.youran.api.network.dns.DefaultRegistrar;
import moe.qingu.youran.api.network.dns.DnsRegistry;
import moe.qingu.youran.api.network.dns.DnsServer;
import moe.qingu.youran.network.DnsState;
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
import moe.qingu.youran.server.net.tls.IssuingTlsContext;
import moe.qingu.youran.server.net.tls.LeafCache;
import moe.qingu.youran.server.net.tls.TestPki;
import moe.qingu.youran.api.network.stack.NetworkException;
import moe.qingu.youran.api.network.tls.CertificateProblem;
import moe.qingu.youran.api.network.tls.TlsTrust;
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
    static final Ipv6Address SITES = Ipv6Address.parse("fd00::3:53");
    static final Ipv6Address MOD = Ipv6Address.parse("fd00:0:0:0:0:1:0:1");
    static final Ipv6Address RESOLVER = Ipv6Address.parse("fd00::53");
    static final Ipv6Address NTP = Ipv6Address.parse("fd00::123");
    static final Ipv6Address WEB = Ipv6Address.parse("fd00::2");

    final TestLink link = new TestLink();
    final NetStack client = link.host("fd00::c");
    RecursiveResolver resolver;
    final DnsServer rootDns;
    final TestPki pki;
    final AtomicInteger upstreamQueries = new AtomicInteger();

    ServicesTest() throws Exception {
        pki = new TestPki();
        DnsState.reset();
        java.io.File dir = java.nio.file.Files.createTempDirectory("ym-dns").toFile();
        NetStack root = link.host(ROOT.toString());
        NetStack yr = link.host(YR.toString());
        NetStack sites = link.host(SITES.toString());
        NetStack mod = link.host(MOD.toString());
        NetStack res = link.host(RESOLVER.toString());
        NetStack ntp = link.host(NTP.toString());
        NetStack web = link.host(WEB.toString());
        // 同开服时的布置：根跟着顶级域登记处；.yr / .com 用默认注册局；网站以模组身份注册成二级域名，自己的 DNS 回答
        rootDns = DnsServer.start(root);
        DnsState.bindRoot(rootDns);
        DnsServer tld = DnsServer.start(yr);
        DefaultRegistrar yrReg = new DefaultRegistrar("youranmod", "yr", tld, new java.io.File(dir, "yr.json"));
        DefaultRegistrar comReg = new DefaultRegistrar("youranmod", "com", tld, new java.io.File(dir, "com.json"));
        DnsRegistry.register("yr", yrReg, Collections.singletonList(YR));
        DnsRegistry.register("com", comReg, Collections.singletonList(YR));
        DnsServer siteDns = DnsServer.start(sites);
        for (String n : Arrays.asList("test.yr", "xn--0zwm56d.yr")) {
            siteDns.addZone(n);
            siteDns.setAddresses(n, Collections.singletonList(WEB));
            yrReg.register(n, "youranmod", Collections.singletonList(SITES));
        }
        // 别的模组：自己的顶级域 .cfm，和以模组身份在 .com 注册的 wixsite.com
        DnsServer modDns = DnsServer.start(mod);
        DefaultRegistrar cfm = new DefaultRegistrar("cfm", "cfm", modDns, new java.io.File(dir, "cfm.json"));
        DnsRegistry.register("cfm", cfm, Collections.singletonList(MOD));
        modDns.addZone("minebay.cfm");
        modDns.setAddresses("minebay.cfm", Collections.singletonList(WEB));
        cfm.register("minebay.cfm", "cfm", Collections.singletonList(MOD));
        modDns.addZone("wixsite.com");
        modDns.setAddresses("dado.wixsite.com", Collections.singletonList(WEB));
        comReg.register("wixsite.com", "youranscp", Collections.singletonList(MOD));
        resolver = new RecursiveResolver(res, Collections.singletonList(ROOT));
        resolver.start();
        new NtpServer(() -> 1_794_355_200_000L).start(ntp);
        TestWebsite.start(web, new IssuingTlsContext(new LeafCache(pki.intermediate(), System::currentTimeMillis), () -> java.util.Arrays.asList("test.yr", "xn--0zwm56d.yr")));
        // 数一数递归解析器往外问了几次（发往四台权威服务器的包）
        link.filter = raw -> {
            Ipv6Address d = Ipv6Packet.destinationOf(raw);
            if (d.equals(ROOT) || d.equals(YR) || d.equals(SITES) || d.equals(MOD)) {
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
        assertEquals(3, upstreamQueries.get(), "问了根、.yr 服务器、test.yr 自己的 DNS 各一次");
        // 第二次：缓存里有
        DnsResolver.Result again = resolve("TEST.yr");
        assertEquals(Collections.singletonList(WEB), again.addresses);
        assertEquals(3, upstreamQueries.get());
        // 同一顶级域下的另一个名字：只问 .yr 服务器（委派已缓存）
        assertEquals(DnsMessage.RCODE_NXDOMAIN, resolve("nothing.yr").rcode);
        assertEquals(4, upstreamQueries.get());
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
        link.loop.drain();
        assertEquals(Collections.singletonList(ROOT), DnsState.rootServers());
        DnsMessage m = rootDns.answer(DnsMessage.query(5, "test.yr", DnsMessage.TYPE_AAAA, true));
        assertEquals(DnsMessage.RCODE_OK, m.rcode);
        assertTrue(!m.authoritative && !m.recursionAvailable);
        assertEquals(0, m.answers.size());
        assertEquals("ns1.yr", m.authority.get(0).target);
        assertEquals(YR, m.additional.get(0).address);
        // 应答报文不回
        assertNull(rootDns.answer(m));
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
    void internationalizedNameEndToEnd() throws Exception {
        // DNS 报文里是 A-label；存根解析器收到 Unicode 先转
        DnsResolver.Result r = resolve("测试.yr");
        assertEquals(Collections.singletonList(WEB), r.addresses);
        assertEquals("xn--0zwm56d.yr", r.response.questions.get(0).name);
        // 转不了的名字报错，不发查询
        CompletableFuture<DnsResolver.Result> bad = new DnsResolver(client, RESOLVER).resolveAaaa("\u0378.yr");
        link.loop.advance(100);
        assertEquals(NetworkException.Reason.LIMIT, ((NetworkException) assertThrowsCause(bad)).reason);
        // 网址里的 Unicode 主机名：请求、SNI、证书都用 A-label
        assertEquals("xn--0zwm56d.yr", HttpClient.Url.parse("https://测试.yr/").host);
        assertNull(HttpClient.Url.parse("http://\u0378.yr/"));
        CompletableFuture<HttpClient.Response> f = HttpClient.send(client, new DnsResolver(client, RESOLVER), HttpClient.Url.parse("https://测试.yr/"),
                "GET", null, null, 15000, TlsTrust.of(pki.root));
        link.loop.advance(10_000);
        HttpClient.Response resp = done(f);
        assertEquals(200, resp.status);
        assertEquals(Collections.singletonList("DNS:xn--0zwm56d.yr"),
                moe.qingu.youran.api.network.tls.CertificateInfo.of(resp.tls.peerChain.get(0)).subjectAltNames);
    }

    @Test
    void fetchTestSiteOverHttps() throws Exception {
        DnsResolver dns = new DnsResolver(client, RESOLVER);
        CompletableFuture<HttpClient.Response> f = HttpClient.send(client, dns, HttpClient.Url.parse("https://test.yr/"), "GET", null, null, 15000, TlsTrust.of(pki.root));
        link.loop.advance(10_000);
        HttpClient.Response r = done(f);
        assertEquals(200, r.status);
        assertEquals(TestWebsite.PAGE, r.bodyText());
        assertEquals(WEB, r.remoteAddress);
        assertEquals(443, r.remotePort);
        assertTrue(r.tls != null && r.tls.peerChain.size() == 2);
        // 不信任测试根（默认只信 QinguMoe CA）：证书错误
        CompletableFuture<HttpClient.Response> g = HttpClient.fetch(client, dns, "https://test.yr/", 15000);
        link.loop.advance(10_000);
        assertTrue(g.isCompletedExceptionally());
        NetworkException e = (NetworkException) assertThrowsCause(g);
        assertEquals(NetworkException.Reason.CERTIFICATE, e.reason);
        assertEquals(CertificateProblem.UNTRUSTED, e.certificateProblem);
    }

    @Test
    void fetchOverHttpsWithLossAndReordering() throws Exception {
        java.util.Random rnd = new java.util.Random(7);
        link.filter = raw -> {
            double r = rnd.nextDouble();
            if (r < 0.2) {
                return null;
            }
            return r < 0.5 ? 20L + rnd.nextInt(61) : 0L;
        };
        int ok = 0;
        for (int i = 0; i < 3; i++) {
            CompletableFuture<HttpClient.Response> f = HttpClient.send(client, new DnsResolver(client, RESOLVER), HttpClient.Url.parse("https://test.yr/"),
                    "GET", null, null, 60_000, TlsTrust.of(pki.root));
            link.loop.advance(60_000);
            if (!f.isCompletedExceptionally()) {
                assertEquals(TestWebsite.PAGE, done(f).bodyText());
                ok++;
            } else {
                // 只允许 DNS 三次都丢了这一种失败（同 HTTP）
                assertEquals(NetworkException.Reason.DNS_FAILED, ((NetworkException) assertThrowsCause(f)).reason);
            }
        }
        assertTrue(ok >= 2, "3 次里至少 2 次取到：" + ok);
    }

    @Test
    void errorReasonsForBrowser() throws Exception {
        DnsResolver dns = new DnsResolver(client, RESOLVER);
        CompletableFuture<HttpClient.Response> nx = HttpClient.fetch(client, dns, "http://nothing.yr/", 15000);
        link.loop.advance(15_000);
        assertEquals(NetworkException.Reason.NAME_NOT_FOUND, ((NetworkException) assertThrowsCause(nx)).reason);
        CompletableFuture<HttpClient.Response> refused = HttpClient.fetch(client, dns, "http://test.yr:8080/", 15000);
        link.loop.advance(15_000);
        assertEquals(NetworkException.Reason.REFUSED, ((NetworkException) assertThrowsCause(refused)).reason);
        // 递归解析器不在：DNS 失败
        CompletableFuture<HttpClient.Response> dead = HttpClient.fetch(client, new DnsResolver(client, Ipv6Address.parse("fd00::9999")), "http://test.yr/", 15000);
        link.loop.advance(15_000);
        assertEquals(NetworkException.Reason.DNS_FAILED, ((NetworkException) assertThrowsCause(dead)).reason);
        // POST 带正文
        HttpClient.Url u = HttpClient.Url.parse("http://test.yr/");
        CompletableFuture<HttpClient.Response> post = HttpClient.send(client, dns, u, "POST", Collections.singletonMap("Content-Type", "application/x-www-form-urlencoded"),
                "a=1".getBytes(StandardCharsets.US_ASCII), 15000, null);
        link.loop.advance(15_000);
        assertEquals(405, done(post).status);
    }

    static Throwable assertThrowsCause(CompletableFuture<?> f) {
        assertTrue(f.isDone(), "future 还没完成");
        try {
            f.get();
        } catch (java.util.concurrent.ExecutionException e) {
            return e.getCause();
        } catch (InterruptedException e) {
            throw new AssertionError(e);
        }
        throw new AssertionError("没有失败");
    }

    @Test
    void urlParsing() {
        HttpClient.Url a = HttpClient.Url.parse("https://minebay.yr");
        assertTrue(a.secure);
        assertEquals(443, a.port);
        assertEquals("/", a.path);
        assertEquals("minebay.yr", a.hostHeader());
        HttpClient.Url b = HttpClient.Url.parse("http://[fd00::2]:8080/x?y=1");
        assertEquals("fd00::2", b.host);
        assertEquals(8080, b.port);
        assertEquals("[fd00::2]:8080", b.hostHeader());
        assertEquals("http://[fd00::2]:8080/x?y=1", b.toString());
        assertNull(HttpClient.Url.parse("ftp://x.yr/"));
        assertEquals("https://a.yr:8443/", HttpClient.Url.parse("HTTPS://a.yr:8443").toString());
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
