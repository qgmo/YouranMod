package moe.qingu.youran.server.net.ca;

import moe.qingu.youran.api.network.Ipv6Address;
import moe.qingu.youran.api.network.ca.CaException;
import moe.qingu.youran.api.network.ca.CertificateClient;
import moe.qingu.youran.api.network.ca.CertificateOrder;
import moe.qingu.youran.api.network.ca.CertificateRequest;
import moe.qingu.youran.api.network.ca.Challenge;
import moe.qingu.youran.api.network.ca.TxtRecords;
import moe.qingu.youran.api.network.dns.DefaultRegistrar;
import moe.qingu.youran.api.network.dns.DnsRegistry;
import moe.qingu.youran.api.network.dns.DnsServer;
import moe.qingu.youran.api.network.dns.Registration;
import moe.qingu.youran.api.network.http.HttpResponse;
import moe.qingu.youran.api.network.http.HttpServer;
import moe.qingu.youran.api.network.packet.DnsMessage;
import moe.qingu.youran.api.network.packet.Ipv6Packet;
import moe.qingu.youran.api.network.stack.DnsResolver;
import moe.qingu.youran.api.network.stack.HttpClient;
import moe.qingu.youran.api.network.stack.NetStack;
import moe.qingu.youran.api.network.stack.TestLink;
import moe.qingu.youran.api.network.tls.CertificateInfo;
import moe.qingu.youran.api.network.tls.CertificateTlsContext;
import moe.qingu.youran.api.network.tls.TlsServerContext;
import moe.qingu.youran.api.network.tls.TlsTrust;
import moe.qingu.youran.network.DnsState;
import moe.qingu.youran.server.net.service.RecursiveResolver;
import moe.qingu.youran.server.net.tls.TestPki;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.security.KeyPair;
import java.security.cert.X509Certificate;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Random;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.function.LongSupplier;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 证书签发服务：经测试网络（根 → .yr → 网站自己的 DNS，递归解析器）做两种认证的成功与失败、域名约束、没配中间 CA、限频、
 * 客户端助手（拿证书、TLS 握手、剩 30 天续期）。时间全由手动事件循环拨（证书时间 = 起点 + 循环里的时间），随机数固定种子。
 */
class CaServiceTest {
    static final Ipv6Address ROOT = Ipv6Address.parse("fd00::1:53");
    static final Ipv6Address TLD = Ipv6Address.parse("fd00::2:53");
    static final Ipv6Address SITE_DNS = Ipv6Address.parse("fd00::3:53");
    static final Ipv6Address RESOLVER = Ipv6Address.parse("fd00::53");
    static final Ipv6Address CA_HOST = Ipv6Address.parse("fd00::ca");
    static final Ipv6Address WEB = Ipv6Address.parse("fd00::80");
    static final long DAY = 24L * 3600 * 1000;

    @TempDir
    File dir;
    TestLink link;
    /** 证书时间的起点（真实时间只当起点用：TLS 客户端按真实时间核对有效期） */
    long base;
    LongSupplier clock;
    TestPki pki;
    CaService ca;
    NetStack caStack;
    NetStack web;
    NetStack client;
    DnsServer siteDns;
    /** 网站 80 口的回答（null 时回 200 “hello”） */
    volatile HttpServer.Handler webPage;

    @BeforeEach
    void setUp() throws Exception {
        DnsState.reset();
        link = new TestLink();
        base = System.currentTimeMillis();
        clock = () -> base + link.loop.nowMillis();
        pki = new TestPki(base, "测试根 CA", "yr");
        DnsState.bindRoot(DnsServer.start(link.host(ROOT.toString())));
        DnsServer tld = DnsServer.start(link.host(TLD.toString()));
        DefaultRegistrar yr = new DefaultRegistrar("youranmod", "yr", tld, new File(dir, "yr.json"));
        DnsRegistry.register("yr", yr, Collections.singletonList(TLD));
        siteDns = DnsServer.start(link.host(SITE_DNS.toString()));
        for (String zone : Arrays.asList("shop.yr", "ca.yr", "empty.yr")) {
            siteDns.addZone(zone);
            Registration r = yr.register(zone, "shopmod", Collections.singletonList(SITE_DNS));
            assertEquals(Registration.Status.SUCCESS, r.status());
        }
        siteDns.setAddresses("shop.yr", Collections.singletonList(WEB));
        siteDns.setAddresses("www.shop.yr", Collections.singletonList(WEB));
        siteDns.setAddresses("ca.yr", Collections.singletonList(CA_HOST));
        new RecursiveResolver(link.host(RESOLVER.toString()), Collections.singletonList(ROOT)).start();
        caStack = link.host(CA_HOST.toString());
        ca = new CaService(pki.intermediate(), null, link.loop, clock, new Random(1));
        ca.setValidator(new NetworkValidator(caStack, RESOLVER));
        web = link.host(WEB.toString());
        HttpServer.listen(web, 80, null, null, req -> {
            HttpServer.Handler h = webPage;
            return h != null ? h.handle(req) : CompletableFuture.completedFuture(HttpResponse.text(200, "hello"));
        });
        client = link.host("fd00::c");
        link.loop.drain();
    }

    static <T> T done(CompletableFuture<T> f) throws Exception {
        assertTrue(f.isDone(), "future 还没完成");
        return f.get();
    }

    static CaException failure(CompletableFuture<?> f) {
        assertTrue(f.isDone(), "future 还没完成");
        ExecutionException e = assertThrows(ExecutionException.class, f::get);
        return (CaException) e.getCause();
    }

    /** 新密钥（EC P-256）给这些名字的证书请求 */
    static byte[] csr(String... names) throws Exception {
        return CertificateRequest.create(TestPki.ec(), Arrays.asList(names));
    }

    /** 测试资源里 OpenSSL 生成的证书请求、公钥（PEM 文字） */
    static String resource(String name) throws Exception {
        try (java.io.InputStream in = CaServiceTest.class.getResourceAsStream("/csr/" + name)) {
            java.io.ByteArrayOutputStream out = new java.io.ByteArrayOutputStream();
            byte[] buf = new byte[4096];
            int n;
            while ((n = in.read(buf)) > 0) {
                out.write(buf, 0, n);
            }
            return new String(out.toByteArray(), StandardCharsets.US_ASCII);
        }
    }

    static java.security.PublicKey resourceKey(String name) throws Exception {
        byte[] spki = moe.qingu.youran.api.network.tls.Pem.parse(resource(name)).get(0).der;
        return java.security.KeyFactory.getInstance("EC").generatePublic(new java.security.spec.X509EncodedKeySpec(spki));
    }

    CertificateOrder order(String... names) throws Exception {
        CompletableFuture<CertificateOrder> f = ca.newOrder(WEB, Arrays.asList(names), csr(names));
        link.loop.drain();
        return done(f);
    }

    CertificateOrder readyAndWait(CertificateOrder o, Challenge.Method m) throws Exception {
        CompletableFuture<CertificateOrder> f = ca.ready(o.id, m);
        link.loop.advance(60_000);
        return done(f);
    }

    // ---- 两种认证 ----

    @Test
    void httpChallengeThroughTheNetwork() throws Exception {
        CertificateOrder o = order("shop.yr", "WWW.Shop.yr");
        assertEquals(CertificateOrder.Status.PENDING, o.status);
        assertEquals(Arrays.asList("shop.yr", "www.shop.yr"), o.names);
        Challenge c = o.challenges.get(0);
        assertEquals("http://shop.yr/.well-known/acme-challenge/" + c.token, c.httpUrl);
        assertEquals("_acme-challenge.shop.yr", c.dnsName);
        assertFalse(c.token.equals(o.challenges.get(1).token), "每个名字的令牌不一样");
        // 网站照着回（自己写的处理器，模拟手工布置）
        webPage = req -> {
            for (Challenge x : o.challenges) {
                if (req.path.equals(Challenge.HTTP_PATH + x.token)) {
                    return CompletableFuture.completedFuture(HttpResponse.text(200, x.httpContent + "\n"));
                }
            }
            return CompletableFuture.completedFuture(HttpResponse.text(404, "no"));
        };
        CertificateOrder r = readyAndWait(o, Challenge.Method.HTTP);
        assertEquals(CertificateOrder.Status.VALID, r.status, r.error);
        X509Certificate leaf = r.certificate.get(0);
        assertEquals(Arrays.asList("DNS:shop.yr", "DNS:www.shop.yr"), CertificateInfo.of(leaf).subjectAltNames);
        assertEquals(2, r.certificate.size(), "叶子 + 中间 CA");
        assertNull(TlsTrust.of(pki.root).check(r.certificate, "www.shop.yr", new java.util.Date(clock.getAsLong())));
        assertEquals(1, done(ca.issued()).size());
        assertEquals("http", done(ca.issued()).get(0).method);
    }

    @Test
    void httpChallengeWrongContent() throws Exception {
        CertificateOrder o = order("shop.yr");
        webPage = req -> CompletableFuture.completedFuture(HttpResponse.text(200, "别的内容"));
        CertificateOrder r = readyAndWait(o, Challenge.Method.HTTP);
        assertEquals(CertificateOrder.Status.INVALID, r.status);
        assertTrue(r.error.startsWith("shop.yr：") && r.error.contains("内容不对"), r.error);
        assertTrue(r.certificate.isEmpty());
        assertTrue(done(ca.issued()).isEmpty());
        // 404 也不行
        CertificateOrder o2 = order("shop.yr");
        webPage = req -> CompletableFuture.completedFuture(HttpResponse.text(404, "no"));
        CertificateOrder r2 = readyAndWait(o2, Challenge.Method.HTTP);
        assertTrue(r2.error.contains("HTTP 404"), r2.error);
    }

    @Test
    void httpChallengeNameDoesNotResolve() throws Exception {
        // empty.yr 注册了、有 DNS，但没有地址；nothing.yr 根本没注册
        for (String name : new String[]{"empty.yr", "nothing.yr"}) {
            CertificateOrder r = readyAndWait(order(name), Challenge.Method.HTTP);
            assertEquals(CertificateOrder.Status.INVALID, r.status);
            assertTrue(r.error.contains("找不到 " + name + " 的地址"), r.error);
        }
    }

    @Test
    void httpChallengeTimesOut() throws Exception {
        CertificateOrder o = order("shop.yr");
        // 网站收了请求一直不回
        webPage = req -> new CompletableFuture<>();
        CompletableFuture<CertificateOrder> f = ca.ready(o.id, Challenge.Method.HTTP);
        link.loop.advance(NetworkValidator.HTTP_TIMEOUT_MS - 1);
        assertFalse(f.isDone(), "10 秒内还在等");
        assertEquals(CertificateOrder.Status.PROCESSING, done(ca.order(o.id)).status);
        link.loop.advance(1_000);
        CertificateOrder r = done(f);
        assertEquals(CertificateOrder.Status.INVALID, r.status);
        assertTrue(r.error.contains("超时"), r.error);
    }

    @Test
    void dnsChallengeThroughTheNetwork() throws Exception {
        CertificateOrder o = order("shop.yr");
        Challenge c = o.challenges.get(0);
        TxtRecords txt = TxtRecords.of(siteDns);
        txt.add(c.dnsName, c.dnsValue);
        link.loop.drain();
        CertificateOrder r = readyAndWait(o, Challenge.Method.DNS);
        assertEquals(CertificateOrder.Status.VALID, r.status, r.error);
        assertEquals(Challenge.Method.DNS, r.method);
        assertEquals("dns", done(ca.issued()).get(0).method);
    }

    @Test
    void dnsChallengeWrongTxt() throws Exception {
        CertificateOrder o = order("shop.yr");
        siteDns.setTexts(o.challenges.get(0).dnsName, Collections.singletonList("不是这个值"));
        link.loop.drain();
        CertificateOrder r = readyAndWait(o, Challenge.Method.DNS);
        assertEquals(CertificateOrder.Status.INVALID, r.status);
        assertTrue(r.error.contains("TXT 记录不对"), r.error);
    }

    @Test
    void dnsChallengeMissingAndUnresolvable() throws Exception {
        // 没加 TXT：shop.yr 的 DNS 回查无此名
        CertificateOrder r = readyAndWait(order("shop.yr"), Challenge.Method.DNS);
        assertTrue(r.error.contains("没有 _acme-challenge.shop.yr 这个名字"), r.error);
        // 没注册的名字：.yr 的 DNS 回查无此名
        CertificateOrder r2 = readyAndWait(order("nothing.yr"), Challenge.Method.DNS);
        assertTrue(r2.error.contains("没有 _acme-challenge.nothing.yr 这个名字"), r2.error);
    }

    @Test
    void dnsChallengeTimesOut() throws Exception {
        CertificateOrder o = order("shop.yr");
        // 递归解析器不回（包丢掉）：存根解析器问 3 次、每次等 2 秒
        link.filter = raw -> Ipv6Packet.destinationOf(raw).equals(RESOLVER) ? null : 0L;
        CertificateOrder r = readyAndWait(o, Challenge.Method.DNS);
        assertEquals(CertificateOrder.Status.INVALID, r.status);
        assertTrue(r.error.contains("超时"), r.error);
    }

    // ---- 拒绝 ----

    @Test
    void namesOutsideTheConstraintsAreRejected() throws Exception {
        CaException e = failure(ca.newOrder(WEB, Collections.singletonList("dado.wixsite.com"), csr("dado.wixsite.com")));
        assertEquals(CaException.Code.REJECTED_NAME, e.code);
        assertTrue(e.getMessage().contains("只许 yr"), e.getMessage());
        // 有一个在约束外整张都拒
        assertEquals(CaException.Code.REJECTED_NAME,
                failure(ca.newOrder(WEB, Arrays.asList("shop.yr", "example.com"), csr("shop.yr", "example.com"))).code);
        assertEquals(CaException.Code.BAD_REQUEST, failure(ca.newOrder(WEB, Collections.singletonList("*.shop.yr"), csr("shop.yr"))).code);
        assertEquals(CaException.Code.BAD_REQUEST, failure(ca.newOrder(WEB, Collections.<String>emptyList(), csr("shop.yr"))).code);
        assertTrue(done(ca.orders()).isEmpty(), "拒绝的不留订单");
        // 以点开头的约束只管下级
        TestPki dotted = new TestPki(base, "测试根 CA", ".yr");
        CaService d = new CaService(dotted.intermediate(), null, link.loop, clock, new Random(2));
        assertEquals(CaException.Code.REJECTED_NAME, failure(d.newOrder(WEB, Collections.singletonList("yr.shop"), csr("yr.shop"))).code);
        assertEquals(CertificateOrder.Status.PENDING, done(d.newOrder(WEB, Collections.singletonList("a.yr"), csr("a.yr"))).status);
    }

    @Test
    void weakOrUnknownKeysAreRejected() throws Exception {
        java.security.KeyPairGenerator g = java.security.KeyPairGenerator.getInstance("RSA");
        g.initialize(1024, new java.security.SecureRandom(new byte[]{1}));
        assertEquals(CaException.Code.BAD_KEY, failure(ca.newOrder(WEB, Collections.singletonList("shop.yr"), CertificateRequest.create(g.generateKeyPair(), Collections.singletonList("shop.yr")))).code);
        assertEquals(CertificateOrder.Status.PENDING, done(ca.newOrder(WEB, Collections.singletonList("shop.yr"), CertificateRequest.create(TestPki.rsa(), Collections.singletonList("shop.yr")))).status);
    }

    @Test
    void csrMustMatchTheNames() throws Exception {
        byte[] p256 = CertificateRequest.decode(resource("ec-p256.csr"));
        // SAN 是 shop.yr、www.shop.yr：少一个、多一个都不行
        CaException fewer = failure(ca.newOrder(WEB, Collections.singletonList("shop.yr"), p256));
        assertEquals(CaException.Code.BAD_CSR, fewer.code);
        assertTrue(fewer.getMessage().contains("多了 [www.shop.yr]"), fewer.getMessage());
        CaException more = failure(ca.newOrder(WEB, Arrays.asList("shop.yr", "www.shop.yr", "empty.yr"), p256));
        assertTrue(more.getMessage().contains("少了 [empty.yr]"), more.getMessage());
        // 不分顺序、大小写；Unicode 名字按 A-label 比
        assertEquals(CertificateOrder.Status.PENDING, done(ca.newOrder(WEB, Arrays.asList("WWW.shop.yr", "Shop.yr"), p256)).status);
        assertEquals(CertificateOrder.Status.PENDING, done(ca.newOrder(WEB, Arrays.asList("shop.yr", "www.shop.yr", "测试.yr"),
                CertificateRequest.decode(resource("ec-p384.csr")))).status);
        // CN 不在名字里、没有 SAN
        CaException cn = failure(ca.newOrder(WEB, Collections.singletonList("shop.yr"), CertificateRequest.decode(resource("cn-not-in-san.csr"))));
        assertEquals(CaException.Code.BAD_CSR, cn.code);
        assertTrue(cn.getMessage().contains("CN（other.yr）"), cn.getMessage());
        assertEquals(CaException.Code.BAD_CSR,
                failure(ca.newOrder(WEB, Collections.singletonList("shop.yr"), CertificateRequest.decode(resource("no-san.csr")))).code);
        // 没有 CN 可以
        assertEquals(CertificateOrder.Status.PENDING, done(ca.newOrder(WEB, Collections.singletonList("shop.yr"),
                CertificateRequest.decode(resource("rsa-2048-sha512-no-cn.csr")))).status);
        // 签名改坏、算法不收、弱钥、截断
        byte[] broken = p256.clone();
        broken[broken.length - 1] ^= 1;
        assertEquals(CaException.Code.BAD_CSR, failure(ca.newOrder(WEB, Arrays.asList("shop.yr", "www.shop.yr"), broken)).code);
        assertEquals(CaException.Code.BAD_CSR,
                failure(ca.newOrder(WEB, Collections.singletonList("shop.yr"), CertificateRequest.decode(resource("rsa-2048-sha1.csr")))).code);
        assertEquals(CaException.Code.BAD_KEY,
                failure(ca.newOrder(WEB, Collections.singletonList("shop.yr"), CertificateRequest.decode(resource("rsa-1024.csr")))).code);
        assertEquals(CaException.Code.BAD_CSR, failure(ca.newOrder(WEB, Collections.singletonList("shop.yr"), Arrays.copyOf(p256, 100))).code);
        assertEquals(3, done(ca.orders()).size(), "拒绝的不留订单");
    }

    @Test
    void challengeIsBoundToTheCsrKey() throws Exception {
        CertificateOrder o = done(ca.newOrder(WEB, Arrays.asList("shop.yr", "www.shop.yr"), CertificateRequest.decode(resource("ec-p256.csr"))));
        String thumb = CaService.b64(CaService.sha256(resourceKey("ec-p256.pub.pem").getEncoded()));
        for (Challenge c : o.challenges) {
            assertEquals(c.token + "." + thumb, c.httpContent);
        }
    }

    @Test
    void withoutIntermediateCaTheServiceIsOff() throws Exception {
        CaService off = new CaService(null, "服务器没配中间 CA（network.tlsCaCertificate / tlsCaKey）", link.loop, clock, new Random(3));
        assertNotNull(off.unavailableReason());
        CaException e = failure(off.newOrder(WEB, Collections.singletonList("shop.yr"), csr("shop.yr")));
        assertEquals(CaException.Code.UNAVAILABLE, e.code);
        assertTrue(e.getMessage().contains("没配中间 CA"), e.getMessage());
        // 客户端助手：没有 TLS，只开 HTTP，也不去申请
        CertificateClient c = CertificateClient.start(web, Collections.singletonList("shop.yr"), null, off, clock);
        assertNull(c.tls());
        link.loop.advance(60_000);
        assertTrue(c.certificate().isEmpty());
    }

    @Test
    void newOrdersAreRateLimitedPerSource() throws Exception {
        Ipv6Address other = Ipv6Address.parse("fd00::99");
        for (int i = 0; i < CaService.NEW_ORDER_BURST; i++) {
            assertEquals(CertificateOrder.Status.PENDING, done(ca.newOrder(WEB, Collections.singletonList("shop.yr"), csr("shop.yr"))).status);
        }
        assertEquals(CaException.Code.RATE_LIMITED, failure(ca.newOrder(WEB, Collections.singletonList("shop.yr"), csr("shop.yr"))).code);
        // 别的来源不受影响
        assertEquals(CertificateOrder.Status.PENDING, done(ca.newOrder(other, Collections.singletonList("shop.yr"), csr("shop.yr"))).status);
        // 6 分钟后补一张
        link.loop.advance(CaService.NEW_ORDER_EVERY_SECONDS * 1000L);
        assertEquals(CertificateOrder.Status.PENDING, done(ca.newOrder(WEB, Collections.singletonList("shop.yr"), csr("shop.yr"))).status);
        assertEquals(CaException.Code.RATE_LIMITED, failure(ca.newOrder(WEB, Collections.singletonList("shop.yr"), csr("shop.yr"))).code);
    }

    @Test
    void orderStates() throws Exception {
        CertificateOrder o = order("shop.yr");
        assertEquals(CaException.Code.NOT_FOUND, failure(ca.order("nope")).code);
        assertEquals(CaException.Code.NOT_FOUND, failure(ca.ready("nope", Challenge.Method.HTTP)).code);
        CompletableFuture<CertificateOrder> f = ca.ready(o.id, Challenge.Method.HTTP);
        assertEquals(CaException.Code.WRONG_STATE, failure(ca.ready(o.id, Challenge.Method.HTTP)).code, "只能通知一次");
        link.loop.advance(60_000);
        assertEquals(CertificateOrder.Status.INVALID, done(f).status);
        // 订单 1 小时后过期
        link.loop.advance(CaService.ORDER_TTL_MS);
        assertEquals(CaException.Code.NOT_FOUND, failure(ca.order(o.id)).code);
    }

    @Test
    void tokensFollowTheSeed() throws Exception {
        CertificateOrder a = order("shop.yr");
        CaService again = new CaService(pki.intermediate(), null, link.loop, clock, new Random(1));
        CertificateOrder b = done(again.newOrder(WEB, Collections.singletonList("shop.yr"), csr("shop.yr")));
        assertEquals(a.id, b.id, "同一个种子：同样的订单号");
        assertEquals(a.challenges.get(0).token, b.challenges.get(0).token);
        assertFalse(a.challenges.get(0).httpContent.equals(b.challenges.get(0).httpContent), "内容带着公钥指纹");
    }

    // ---- 客户端助手 ----

    /** 证书服务签出去几张（每个测试只有一个助手在申请） */
    int issuedCount() throws Exception {
        return done(ca.issued()).size();
    }

    /** 网站 443 口用助手的 TLS；返回助手 */
    CertificateClient siteWithHttps(TxtRecords txt) {
        CertificateClient c = CertificateClient.start(web, Arrays.asList("shop.yr", "www.shop.yr"), txt, ca, clock);
        TlsServerContext tls = c.tls();
        assertNotNull(tls);
        HttpServer.listen(web, 443, tls, null, req -> CompletableFuture.completedFuture(HttpResponse.text(200, "secure " + req.secure)));
        return c;
    }

    HttpClient.Response httpsGet(String url) throws Exception {
        CompletableFuture<HttpClient.Response> f = HttpClient.send(client, new DnsResolver(client, RESOLVER), HttpClient.Url.parse(url), "GET",
                null, null, 15_000, TlsTrust.of(pki.root));
        link.loop.advance(20_000);
        return done(f);
    }

    @Test
    void clientGetsCertificateByHttpAndTlsWorks() throws Exception {
        CertificateClient c = siteWithHttps(null);
        assertTrue(c.certificate().isEmpty(), "第一次申请排在之后");
        link.loop.advance(30_000);
        assertNull(c.lastError(), c.lastError());
        assertEquals(1, issuedCount());
        assertEquals("http", done(ca.issued()).get(0).method, "80 口有网站：HTTP 认证（YouranMod 的 HttpServer 自动回）");
        assertEquals(WEB.toString(), done(ca.issued()).get(0).requester);
        HttpClient.Response r = httpsGet("https://www.shop.yr/");
        assertEquals(200, r.status);
        assertEquals("secure true", r.bodyText());
        assertEquals(c.certificate().get(0), r.tls.peerChain.get(0));
        // 认证完了：认证网址不再回内容
        CertificateOrder last = done(ca.orders()).get(0).order;
        assertEquals(CertificateOrder.Status.VALID, last.status);
        HttpClient.Response gone = done(fetch(last.challenges.get(0).httpUrl));
        assertEquals("hello", gone.bodyText(), "认证完以后交给网站自己");
    }

    CompletableFuture<HttpClient.Response> fetch(String url) {
        CompletableFuture<HttpClient.Response> f = HttpClient.fetch(client, new DnsResolver(client, RESOLVER), url, 15_000);
        link.loop.advance(20_000);
        return f;
    }

    @Test
    void clientGetsCertificateByDns() throws Exception {
        CertificateClient c = siteWithHttps(TxtRecords.of(siteDns));
        link.loop.advance(30_000);
        assertNull(c.lastError(), c.lastError());
        assertEquals("dns", done(ca.issued()).get(0).method, "给了加 TXT 的办法：DNS 认证");
        assertEquals(200, httpsGet("https://shop.yr/").status);
        // 认证完删掉了 TXT（查无此名会被解析器缓存，所以直接问 shop.yr 的 DNS）
        DnsMessage m = siteDns.answer(DnsMessage.query(1, "_acme-challenge.shop.yr", DnsMessage.TYPE_TXT, false));
        assertTrue(m.answers.isEmpty());
    }

    @Test
    void clientWithoutWebsiteOrTxtRetriesLater() throws Exception {
        NetStack bare = link.host("fd00::81");
        CertificateClient c = CertificateClient.start(bare, Collections.singletonList("shop.yr"), null, ca, clock);
        link.loop.advance(1_000);
        assertNotNull(c.lastError());
        assertTrue(c.lastError().contains("80 口没有网站"), c.lastError());
        assertTrue(done(ca.orders()).isEmpty(), "没去申请");
        // 之后开了网站：下次重试时申请到（第一次重试在 6 分钟后）
        HttpServer.listen(bare, 80, null, null, req -> CompletableFuture.completedFuture(HttpResponse.text(404, "no")));
        siteDns.setAddresses("shop.yr", Collections.singletonList(Ipv6Address.parse("fd00::81")));
        link.loop.advance(6 * 60_000 + 30_000);
        assertNull(c.lastError(), c.lastError());
        assertEquals(1, issuedCount());
    }

    @Test
    void clientRetriesAfterFailureAndKeepsTrying() throws Exception {
        // 先把发往网站的包都丢掉：第一次认证超时
        int[] blocked = {1};
        NetStack site = link.host("fd00::82");
        siteDns.setAddresses("shop.yr", Collections.singletonList(Ipv6Address.parse("fd00::82")));
        HttpServer.listen(site, 80, null, null, req -> CompletableFuture.completedFuture(HttpResponse.text(200, "hello")));
        link.filter = raw -> {
            if (blocked[0] > 0 && Ipv6Packet.destinationOf(raw).equals(Ipv6Address.parse("fd00::82"))) {
                return null;
            }
            return 0L;
        };
        CertificateClient c = CertificateClient.start(site, Collections.singletonList("shop.yr"), null, ca, clock);
        link.loop.advance(60_000);
        assertNotNull(c.lastError());
        assertTrue(c.lastError().contains("超时"), c.lastError());
        blocked[0] = 0;
        link.loop.advance(6 * 60_000);
        assertNull(c.lastError(), c.lastError());
        assertEquals(1, issuedCount());
    }

    @Test
    void clientRenewsWhenThirtyDaysAreLeft() throws Exception {
        CertificateClient c = siteWithHttps(null);
        link.loop.advance(30_000);
        assertEquals(1, issuedCount());
        X509Certificate first = c.certificate().get(0);
        long notAfter = first.getNotAfter().getTime();
        // 剩 30 天零 1 分钟：还没续
        link.loop.runUntil(notAfter - 30 * DAY - 60_000 - base);
        assertEquals(1, issuedCount());
        assertEquals(first, c.certificate().get(0));
        // 到剩 30 天：续了，新证书接着用
        link.loop.advance(2 * 60_000);
        assertEquals(2, issuedCount());
        X509Certificate second = c.certificate().get(0);
        assertFalse(first.equals(second));
        long left = notAfter - clock.getAsLong();
        assertTrue(left <= 30 * DAY && left > 30 * DAY - 5 * 60_000, "在剩 30 天时续：" + left);
        assertTrue(second.getNotAfter().getTime() > notAfter);
        assertFalse(first.getPublicKey().equals(second.getPublicKey()), "续期换新密钥");
    }

    @Test
    void testWebsiteAnswersChallengesToo() throws Exception {
        // test.yr 那种自己写 HTTP 的网站（TestWebsite）也回认证
        NetStack t = link.host("fd00::83");
        siteDns.setAddresses("shop.yr", Collections.singletonList(Ipv6Address.parse("fd00::83")));
        CertificateClient c = CertificateClient.start(t, Collections.singletonList("shop.yr"), null, ca, clock);
        moe.qingu.youran.server.net.service.TestWebsite.start(t, c.tls());
        link.loop.advance(30_000);
        assertNull(c.lastError(), c.lastError());
        assertEquals(1, issuedCount());
        assertEquals(200, httpsGet("https://shop.yr/").status);
    }

    @Test
    void caSignsItsOwnCertificate() throws Exception {
        CertificateTlsContext tls = new CertificateTlsContext();
        ca.selfCertificate(Collections.singletonList("ca.yr"), tls);
        assertEquals(Collections.singletonList("DNS:ca.yr"), CertificateInfo.of(tls.chain().get(0)).subjectAltNames);
        assertEquals("证书服务自己", done(ca.issued()).get(0).requester);
        assertTrue(done(ca.orders()).isEmpty(), "不走订单");
        // 剩 30 天时自己再签
        X509Certificate first = tls.chain().get(0);
        link.loop.runUntil(first.getNotAfter().getTime() - 30 * DAY + 60_000 - base);
        assertFalse(first.equals(tls.chain().get(0)));
    }

    // ---- HTTP 接口 ----

    HttpClient.Response api(String method, String path, String body) throws Exception {
        CompletableFuture<HttpClient.Response> f = HttpClient.send(client, new DnsResolver(client, RESOLVER), HttpClient.Url.parse("http://ca.yr" + path),
                method, Collections.singletonMap("Content-Type", "application/json"), body == null ? null : body.getBytes(StandardCharsets.UTF_8),
                15_000, null);
        link.loop.advance(20_000);
        return done(f);
    }

    static com.google.gson.JsonObject json(HttpClient.Response r) {
        return new com.google.gson.JsonParser().parse(r.bodyText()).getAsJsonObject();
    }

    @Test
    void httpApiWalkthroughWithDnsChallenge() throws Exception {
        new CaHttp(ca, clock).listen(caStack, null);
        // OpenSSL 生成的证书请求（两个名字）
        String pem = resource("ec-p256.csr").replace("\n", "\\n");
        // 1. 申请
        HttpClient.Response r = api("POST", "/orders", "{\"names\": [\"shop.yr\", \"www.shop.yr\"], \"csr\": \"" + pem + "\"}");
        assertEquals(201, r.status, r.bodyText());
        assertTrue(r.header("Content-Type").startsWith("application/json"));
        com.google.gson.JsonObject o = json(r);
        String id = o.get("id").getAsString();
        assertEquals("pending", o.get("status").getAsString());
        // 2. 取认证要求
        com.google.gson.JsonObject got = json(api("GET", "/orders/" + id, null));
        assertEquals(2, got.getAsJsonArray("challenges").size());
        assertEquals("_acme-challenge.shop.yr", got.getAsJsonArray("challenges").get(0).getAsJsonObject().getAsJsonObject("dns").get("name").getAsString());
        assertEquals(409, api("GET", "/orders/" + id + "/certificate", null).status, "还没签好");
        for (com.google.gson.JsonElement c : got.getAsJsonArray("challenges")) {
            com.google.gson.JsonObject dns = c.getAsJsonObject().getAsJsonObject("dns");
            siteDns.setTexts(dns.get("name").getAsString(), Collections.singletonList(dns.get("value").getAsString()));
        }
        // 3. 通知已就绪
        HttpClient.Response ready = api("POST", "/orders/" + id + "/ready", "{\"method\": \"dns\"}");
        assertEquals(202, ready.status, ready.bodyText());
        com.google.gson.JsonObject after = json(api("GET", "/orders/" + id, null));
        assertEquals("valid", after.get("status").getAsString(), after.toString());
        assertEquals("dns", after.get("method").getAsString());
        assertEquals("/orders/" + id + "/certificate", after.get("certificate").getAsString());
        assertEquals(409, api("POST", "/orders/" + id + "/ready", "{\"method\": \"dns\"}").status, "只能通知一次");
        // 4. 取证书
        com.google.gson.JsonObject cert = json(api("GET", "/orders/" + id + "/certificate", null));
        List<X509Certificate> chain = moe.qingu.youran.api.network.tls.Pem.certificates(cert.get("certificate").getAsString());
        assertEquals(2, chain.size());
        assertEquals(resourceKey("ec-p256.pub.pem"), chain.get(0).getPublicKey(), "证书里是证书请求里的公钥");
        assertNull(TlsTrust.of(pki.root).check(chain, "shop.yr", new java.util.Date(clock.getAsLong())));
        assertNull(TlsTrust.of(pki.root).check(chain, "www.shop.yr", new java.util.Date(clock.getAsLong())));
        assertEquals(chain.get(0).getSerialNumber().toString(16), cert.get("serial").getAsString());
    }

    @Test
    void httpApiErrors() throws Exception {
        new CaHttp(ca, clock).listen(caStack, null);
        String key = java.util.Base64.getEncoder().encodeToString(csr("shop.yr"));
        assertError(api("POST", "/orders", "不是 JSON"), 400, "badRequest");
        assertError(api("POST", "/orders", "{\"names\": [\"shop.yr\"]}"), 400, "badRequest");
        assertError(api("POST", "/orders", "{\"names\": [\"shop.yr\"], \"csr\": \"AAAA\"}"), 400, "badCsr");
        // 交公钥（旧写法）不收
        String pub = moe.qingu.youran.api.network.tls.Pem.encode("PUBLIC KEY", TestPki.ec().getPublic().getEncoded()).replace("\n", "\\n");
        assertError(api("POST", "/orders", "{\"names\": [\"shop.yr\"], \"publicKey\": \"" + pub + "\"}"), 400, "badRequest");
        assertError(api("POST", "/orders", "{\"names\": [\"shop.yr\"], \"csr\": \"" + pub + "\"}"), 400, "badCsr");
        // 名字不一致、签名改坏
        assertError(api("POST", "/orders", "{\"names\": [\"www.shop.yr\"], \"csr\": \"" + key + "\"}"), 400, "badCsr");
        byte[] broken = csr("shop.yr");
        broken[broken.length - 1] ^= 1;
        assertError(api("POST", "/orders", "{\"names\": [\"shop.yr\"], \"csr\": \"" + java.util.Base64.getEncoder().encodeToString(broken) + "\"}"),
                400, "badCsr");
        assertError(api("POST", "/orders", "{\"names\": [\"dado.wixsite.com\"], \"csr\": \"" + key + "\"}"), 403, "rejectedName");
        assertError(api("GET", "/orders/nope", null), 404, "notFound");
        assertError(api("GET", "/orders", null), 405, "httpError");
        HttpClient.Response ok = api("POST", "/orders", "{\"names\": [\"shop.yr\"], \"csr\": \"" + key + "\"}");
        assertEquals(201, ok.status, ok.bodyText());
        String id = json(ok).get("id").getAsString();
        assertError(api("POST", "/orders/" + id + "/ready", "{\"method\": \"ftp\"}"), 400, "badRequest");
        // 同一个来源申请太多：429
        for (int i = 1; i < CaService.NEW_ORDER_BURST; i++) {
            assertEquals(201, api("POST", "/orders", "{\"names\": [\"shop.yr\"], \"csr\": \"" + key + "\"}").status);
        }
        assertError(api("POST", "/orders", "{\"names\": [\"shop.yr\"], \"csr\": \"" + key + "\"}"), 429, "rateLimited");
        // 服务没开：503
        CaService off = new CaService(null, "服务器没配中间 CA", link.loop, clock, new Random(4));
        NetStack offStack = link.host("fd00::cb");
        new CaHttp(off, clock).listen(offStack, null);
        CompletableFuture<HttpClient.Response> f = HttpClient.send(client, new DnsResolver(client, RESOLVER), HttpClient.Url.parse("http://[fd00::cb]/orders"),
                "POST", null, ("{\"names\": [\"shop.yr\"], \"csr\": \"" + key + "\"}").getBytes(StandardCharsets.UTF_8), 15_000, null);
        link.loop.advance(20_000);
        HttpClient.Response r = done(f);
        assertError(r, 503, "unavailable");
        assertTrue(json(r).get("message").getAsString().contains("没配中间 CA"));
    }

    static void assertError(HttpClient.Response r, int status, String code) {
        assertEquals(status, r.status, r.bodyText());
        assertEquals(code, json(r).get("error").getAsString(), r.bodyText());
    }
}
