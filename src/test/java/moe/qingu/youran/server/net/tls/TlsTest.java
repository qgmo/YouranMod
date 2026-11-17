package moe.qingu.youran.server.net.tls;

import moe.qingu.youran.api.network.Ipv6Address;
import moe.qingu.youran.api.network.stack.NetStack;
import moe.qingu.youran.api.network.stack.NetworkException;
import moe.qingu.youran.api.network.stack.TestLink;
import moe.qingu.youran.api.network.tls.CertificateInfo;
import moe.qingu.youran.api.network.tls.CertificateTlsContext;
import moe.qingu.youran.api.network.tls.CertificateProblem;
import moe.qingu.youran.api.network.tls.Pem;
import moe.qingu.youran.api.network.tls.TlsConnection;
import moe.qingu.youran.api.network.tls.TlsTrust;
import moe.qingu.youran.api.network.tls.TlsTrustAccess;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.KeyPair;
import java.security.cert.X509Certificate;
import java.util.Arrays;
import java.util.Collections;
import java.util.Date;
import java.util.List;
import java.util.Random;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** TLS：握手、证书链校验（主机名、过期、不是测试根签的、域名约束）、一张证书多个名字、换证书、丢包乱序 */
class TlsTest {
    static final Ipv6Address SERVER = Ipv6Address.parse("fd00::1:2");

    final TestLink link = new TestLink();
    final NetStack client = link.host("fd00::c");
    final NetStack server = link.host(SERVER.toString());

    /** 中间 CA 在 {@code now} 给 {@code names} 签一张证书（新的 EC 密钥），放进一个 TLS */
    static CertificateTlsContext issued(IntermediateCa ca, long now, String... names) throws Exception {
        KeyPair kp = TestPki.ec();
        CertificateTlsContext ctx = new CertificateTlsContext();
        ctx.set(kp.getPrivate(), ca.issue(Arrays.asList(names), kp.getPublic(), new Date(now)));
        return ctx;
    }

    /** 签一张证书，返回链 */
    static List<X509Certificate> chainFor(IntermediateCa ca, String name, Date now) throws Exception {
        return ca.issue(Collections.singletonList(name), TestPki.ec().getPublic(), now);
    }

    void echoServer(IntermediateCa ca, String... names) throws Exception {
        echoServer(issued(ca, System.currentTimeMillis(), names));
    }

    /** 服务端：收到什么回什么，对方发完就关 */
    void echoServer(CertificateTlsContext ctx) {
        server.listen(null, 443, c -> TlsConnection.accept(c, ctx, new TlsConnection.Listener() {
            @Override
            public void onData(TlsConnection connection, byte[] data) {
                connection.write(data);
            }

            @Override
            public void onEnd(TlsConnection connection) {
                connection.close();
            }
        }));
    }

    static final class Collect implements TlsConnection.Listener {
        final ByteArrayOutputStream got = new ByteArrayOutputStream();
        boolean ended;
        NetworkException closedWith;
        boolean closed;

        @Override
        public void onData(TlsConnection connection, byte[] data) {
            got.write(data, 0, data.length);
        }

        @Override
        public void onEnd(TlsConnection connection) {
            ended = true;
        }

        @Override
        public void onClosed(TlsConnection connection, NetworkException error) {
            closed = true;
            closedWith = error;
        }
    }

    static NetworkException failure(CompletableFuture<?> f) {
        assertTrue(f.isDone(), "future 还没完成");
        ExecutionException e = assertThrows(ExecutionException.class, f::get);
        return (NetworkException) e.getCause();
    }

    @Test
    void handshakeAndEcho() throws Exception {
        TestPki pki = new TestPki();
        echoServer(pki.intermediate(), "minebay.yr");
        Collect col = new Collect();
        CompletableFuture<TlsConnection> f = TlsConnection.connect(client, SERVER, 443, "minebay.yr", TlsTrust.of(pki.root), col);
        link.loop.advance(5000);
        TlsConnection c = f.get();
        assertNotNull(c.info());
        assertTrue(c.info().protocol.equals("TLSv1.3") || c.info().protocol.equals("TLSv1.2"), c.info().protocol);
        assertEquals(SERVER, c.info().remoteAddress);
        assertEquals(443, c.info().remotePort);
        assertEquals(2, c.info().peerChain.size(), "叶子 + 中间 CA（不发根）");
        CertificateInfo leaf = CertificateInfo.of(c.info().peerChain.get(0));
        assertEquals(Collections.singletonList("DNS:minebay.yr"), leaf.subjectAltNames);
        assertEquals(95, leaf.sha256.length());
        // 大于一条 TLS 记录的数据
        StringBuilder big = new StringBuilder();
        for (int i = 0; i < 4000; i++) {
            big.append("数据").append(i);
        }
        c.write(big.toString().getBytes(StandardCharsets.UTF_8));
        link.loop.advance(5000);
        c.close();
        link.loop.advance(5000);
        assertEquals(big.toString(), new String(col.got.toByteArray(), StandardCharsets.UTF_8));
        assertTrue(col.ended);
        assertTrue(col.closed);
        assertNull(col.closedWith);
    }

    @Test
    void tls12ClientStillWorks() throws Exception {
        TestPki pki = new TestPki();
        echoServer(pki.intermediate(), "minebay.yr");
        TlsTrustAccess.onlyProtocols(Collections.singletonList("TLSv1.2"));
        try {
            Collect col = new Collect();
            CompletableFuture<TlsConnection> f = TlsConnection.connect(client, SERVER, 443, "minebay.yr", TlsTrust.of(pki.root), col);
            link.loop.advance(5000);
            TlsConnection c = f.get();
            assertEquals("TLSv1.2", c.info().protocol);
            assertTrue(c.info().cipherSuite.startsWith("TLS_ECDHE_ECDSA_"), c.info().cipherSuite);
            c.write("你好".getBytes(StandardCharsets.UTF_8));
            link.loop.advance(5000);
            assertEquals("你好", new String(col.got.toByteArray(), StandardCharsets.UTF_8));
        } finally {
            TlsTrustAccess.onlyProtocols(null);
        }
    }

    @Test
    void hostnameMismatch() throws Exception {
        TestPki pki = new TestPki();
        echoServer(pki.intermediate(), "minebay.yr");
        // 名字不归这个网站：服务端给默认名字的证书，客户端核对主机名不过
        CompletableFuture<TlsConnection> f = TlsConnection.connect(client, SERVER, 443, "other.yr", TlsTrust.of(pki.root), new Collect());
        link.loop.advance(5000);
        NetworkException e = failure(f);
        assertEquals(NetworkException.Reason.CERTIFICATE, e.reason);
        assertEquals(CertificateProblem.HOSTNAME_MISMATCH, e.certificateProblem);
        // 按 IP 访问也不行（证书里只有域名）
        CompletableFuture<TlsConnection> g = TlsConnection.connect(client, SERVER, 443, SERVER.toString(), TlsTrust.of(pki.root), new Collect());
        link.loop.advance(5000);
        assertEquals(CertificateProblem.HOSTNAME_MISMATCH, failure(g).certificateProblem);
    }

    @Test
    void expiredLeaf() throws Exception {
        TestPki pki = new TestPki();
        // 叶子在 100 天前签（90 天有效），现在已过期；中间 CA 本身有效
        long past = System.currentTimeMillis() - 100 * TestPki.DAY;
        echoServer(issued(pki.intermediate(), past, "minebay.yr"));
        CompletableFuture<TlsConnection> f = TlsConnection.connect(client, SERVER, 443, "minebay.yr", TlsTrust.of(pki.root), new Collect());
        link.loop.advance(5000);
        NetworkException e = failure(f);
        assertEquals(CertificateProblem.EXPIRED, e.certificateProblem);
    }

    @Test
    void notSignedByTrustedRoot() throws Exception {
        TestPki pki = new TestPki();
        echoServer(pki.intermediate(), "minebay.yr");
        // 另一张根（名字都一样，密钥不同），以及名字不同的根
        TestPki sameName = new TestPki("测试根 CA", "yr");
        TestPki other = new TestPki("别人的根", "yr");
        for (X509Certificate root : new X509Certificate[]{sameName.root, other.root}) {
            CompletableFuture<TlsConnection> f = TlsConnection.connect(client, SERVER, 443, "minebay.yr", TlsTrust.of(root), new Collect());
            link.loop.advance(5000);
            NetworkException e = failure(f);
            assertEquals(NetworkException.Reason.CERTIFICATE, e.reason);
            assertEquals(CertificateProblem.UNTRUSTED, e.certificateProblem);
        }
    }

    @Test
    void nameConstraintsAreEnforced() throws Exception {
        TestPki pki = new TestPki();
        IntermediateCa ca = pki.intermediate();
        Date now = new Date();
        TlsTrust trust = TlsTrust.of(pki.root);
        assertNull(trust.check(chainFor(ca, "minebay.yr", now), "minebay.yr", now));
        assertNull(trust.check(chainFor(ca, "a.b.yr", now), "A.B.YR.", now), "不分大小写、结尾点");
        assertEquals(CertificateProblem.NAME_NOT_PERMITTED, trust.check(chainFor(ca, "dado.wixsite.com", now), "dado.wixsite.com", now));
        assertEquals(CertificateProblem.NAME_NOT_PERMITTED, trust.check(chainFor(ca, "notyr", now), "notyr", now));
    }

    @Test
    void internationalizedNamesUseALabels() throws Exception {
        // 测试.yr 的 A-label；中间 CA 的约束 DNS:yr 也管 A-label 的子域
        String alabel = moe.qingu.youran.api.network.DomainNames.toAscii("测试.yr");
        assertEquals("xn--0zwm56d.yr", alabel);
        TestPki pki = new TestPki();
        IntermediateCa ca = pki.intermediate();
        Date now = new Date();
        TlsTrust trust = TlsTrust.of(pki.root);
        List<X509Certificate> chain = chainFor(ca, alabel, now);
        assertEquals(Collections.singletonList("DNS:" + alabel), CertificateInfo.of(chain.get(0)).subjectAltNames, "SAN 里是 A-label");
        assertNull(trust.check(chain, alabel, now));
        assertNull(trust.check(chain, "测试.yr", now), "要连的名字写成 Unicode 也按 A-label 比");
        assertEquals(CertificateProblem.HOSTNAME_MISMATCH, trust.check(chain, "测验.yr", now));
        // 握手：SNI 用 A-label
        echoServer(ca, alabel);
        CompletableFuture<TlsConnection> f = TlsConnection.connect(client, SERVER, 443, alabel, trust, new Collect());
        link.loop.advance(5000);
        TlsConnection c = f.get();
        assertEquals(Collections.singletonList("DNS:" + alabel), CertificateInfo.of(c.info().peerChain.get(0)).subjectAltNames);
        c.close();
        link.loop.advance(1000);
    }

    @Test
    void chainChecks() throws Exception {
        TestPki pki = new TestPki();
        IntermediateCa ca = pki.intermediate();
        Date now = new Date();
        TlsTrust trust = TlsTrust.of(pki.root);
        List<X509Certificate> chain = chainFor(ca, "minebay.yr", now);
        assertEquals(CertificateProblem.NOT_YET_VALID, trust.check(chain, "minebay.yr", new Date(now.getTime() - 2 * TestPki.DAY)));
        assertEquals(CertificateProblem.EXPIRED, trust.check(chain, "minebay.yr", new Date(now.getTime() + 100 * TestPki.DAY)));
        // 链不完整（缺中间 CA）
        assertEquals(CertificateProblem.UNTRUSTED, trust.check(chain.subList(0, 1), "minebay.yr", now));
        assertEquals(CertificateProblem.NO_CERTIFICATE, trust.check(Collections.<X509Certificate>emptyList(), "minebay.yr", now));
        // 中间 CA 本身不能当网站证书
        assertEquals(CertificateProblem.WRONG_USAGE, trust.check(Collections.singletonList(pki.ca), "minebay.yr", now));
        // 对方把根也发来：去掉后照样通过
        List<X509Certificate> withRoot = new java.util.ArrayList<>(chain);
        withRoot.add(pki.root);
        assertNull(trust.check(withRoot, "minebay.yr", now));
    }

    @Test
    void oneCertificateForSeveralNames() throws Exception {
        TestPki pki = new TestPki();
        echoServer(pki.intermediate(), "minebay.yr", "shop.yr");
        for (String host : new String[]{"minebay.yr", "shop.yr"}) {
            CompletableFuture<TlsConnection> f = TlsConnection.connect(client, SERVER, 443, host, TlsTrust.of(pki.root), new Collect());
            link.loop.advance(5000);
            TlsConnection c = f.get();
            assertEquals(Arrays.asList("DNS:minebay.yr", "DNS:shop.yr"), CertificateInfo.of(c.info().peerChain.get(0)).subjectAltNames);
            c.close();
            link.loop.advance(1000);
        }
    }

    @Test
    void certificateCanBeReplaced() throws Exception {
        TestPki pki = new TestPki();
        CertificateTlsContext ctx = new CertificateTlsContext();
        echoServer(ctx);
        // 还没放证书：握手失败
        CompletableFuture<TlsConnection> none = TlsConnection.connect(client, SERVER, 443, "minebay.yr", TlsTrust.of(pki.root), new Collect());
        link.loop.advance(5000);
        assertTrue(none.isCompletedExceptionally());
        X509Certificate before = null;
        for (int i = 0; i < 2; i++) {
            KeyPair kp = TestPki.ec();
            ctx.set(kp.getPrivate(), pki.intermediate().issue(Collections.singletonList("minebay.yr"), kp.getPublic(), new Date()));
            CompletableFuture<TlsConnection> f = TlsConnection.connect(client, SERVER, 443, "minebay.yr", TlsTrust.of(pki.root), new Collect());
            link.loop.advance(5000);
            X509Certificate got = f.get().info().peerChain.get(0);
            assertEquals(ctx.chain().get(0), got, "握手用的是现在放着的证书");
            if (before != null) {
                assertFalse(before.equals(got), "换了证书");
            }
            before = got;
            f.get().close();
            link.loop.advance(1000);
        }
    }

    @Test
    void lossAndReorderingStillWork() throws Exception {
        TestPki pki = new TestPki();
        echoServer(pki.intermediate(), "minebay.yr");
        Random rnd = new Random(42);
        // 丢 15%，另有 30% 推迟 20–80 毫秒（乱序），同 /ynet sim
        link.filter = raw -> {
            double r = rnd.nextDouble();
            if (r < 0.15) {
                return null;
            }
            return r < 0.45 ? 20L + rnd.nextInt(61) : 0L;
        };
        Collect col = new Collect();
        CompletableFuture<TlsConnection> f = TlsConnection.connect(client, SERVER, 443, "minebay.yr", TlsTrust.of(pki.root), col);
        link.loop.advance(60_000);
        TlsConnection c = f.get();
        byte[] data = new byte[20_000];
        new Random(1).nextBytes(data);
        String text = Base64Text.of(data);
        c.write(text.getBytes(StandardCharsets.US_ASCII));
        link.loop.advance(120_000);
        assertEquals(text, new String(col.got.toByteArray(), StandardCharsets.US_ASCII));
        assertTrue(client.tcpRetransmissions() > 0, "确实丢过包");
    }

    @Test
    void loadsIntermediateFromPemFiles(@org.junit.jupiter.api.io.TempDir Path dir) throws Exception {
        TestPki pki = new TestPki();
        Path cert = dir.resolve("ca.crt");
        Path key = dir.resolve("ca.key");
        Files.write(cert, (Pem.encode("CERTIFICATE", pki.ca.getEncoded()) + Pem.encode("CERTIFICATE", pki.root.getEncoded())).getBytes(StandardCharsets.US_ASCII));
        Files.write(key, Pem.encode("PRIVATE KEY", pki.caKey.getPrivate().getEncoded()).getBytes(StandardCharsets.US_ASCII));
        IntermediateCa ca = IntermediateCa.load(cert, key);
        assertEquals(1, ca.chain().size(), "根证书不发");
        // 私钥对不上
        Files.write(key, Pem.encode("PRIVATE KEY", TestPki.ec().getPrivate().getEncoded()).getBytes(StandardCharsets.US_ASCII));
        assertThrows(java.security.GeneralSecurityException.class, () -> IntermediateCa.load(cert, key));
        // 加密的私钥：提示怎么转换
        Files.write(key, Pem.encode("ENCRYPTED PRIVATE KEY", new byte[]{1, 2, 3}).getBytes(StandardCharsets.US_ASCII));
        assertTrue(assertThrows(java.security.GeneralSecurityException.class, () -> IntermediateCa.load(cert, key)).getMessage().contains("pkcs8"));
        // 不是 CA
        KeyPair kp = TestPki.ec();
        List<X509Certificate> leaf = ca.issue(Collections.singletonList("minebay.yr"), kp.getPublic(), new Date());
        assertThrows(java.security.GeneralSecurityException.class, () -> new IntermediateCa(leaf.subList(0, 1), kp.getPrivate()));
    }

    @Test
    void bundledRootIsQinguMoeCa() {
        TlsTrust t = TlsTrust.qinguMoe();
        assertEquals(1, t.roots().size());
        X509Certificate root = t.roots().get(0);
        assertTrue(root.getSubjectX500Principal().getName().contains("CN=QinguMoe CA"));
        assertEquals("A9:3D:FE:97:60:3D:DA:55:32:E2:95:05:53:44:8E:91:EB:49:1B:45:14:A8:99:45:51:02:6A:2E:3F:E4:40:1D",
                CertificateInfo.of(root).sha256);
    }

    @Test
    void hostnameMatching() {
        assertTrue(TlsTrustAccess.dnsMatches("minebay.yr", "minebay.yr"));
        assertTrue(TlsTrustAccess.dnsMatches("*.shop.yr", "a.shop.yr"));
        assertFalse(TlsTrustAccess.dnsMatches("*.shop.yr", "a.b.shop.yr"));
        assertFalse(TlsTrustAccess.dnsMatches("*.shop.yr", "shop.yr"));
        assertFalse(TlsTrustAccess.dnsMatches("*.yr", "minebay.yr"), "通配符后缀至少两级");
        assertFalse(TlsTrustAccess.dnsMatches("minebay.yr", "minebay.yr.evil"));
        assertArrayEquals(new byte[0], new byte[0]);
    }

    /** 测试数据转成可读文本 */
    static final class Base64Text {
        static String of(byte[] b) {
            return java.util.Base64.getEncoder().encodeToString(b);
        }
    }
}
