package moe.qingu.youran.api.network.ca;

import moe.qingu.youran.api.network.tls.Pem;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.security.KeyFactory;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.PublicKey;
import java.security.SecureRandom;
import java.security.spec.ECGenParameterSpec;
import java.security.spec.X509EncodedKeySpec;
import java.util.Arrays;
import java.util.Base64;
import java.util.Collections;
import java.util.LinkedHashSet;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 证书请求（PKCS#10）的读取、验签与生成。测试资源 {@code /csr/*.csr} 是用 OpenSSL 3.0 生成的（只放了请求与公钥，私钥没留）。
 * 自己生成的密钥用固定种子的随机数。
 */
class CertificateRequestTest {
    static String text(String resource) throws IOException {
        try (InputStream in = CertificateRequestTest.class.getResourceAsStream("/csr/" + resource)) {
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            byte[] buf = new byte[4096];
            int n;
            while ((n = in.read(buf)) > 0) {
                out.write(buf, 0, n);
            }
            return new String(out.toByteArray(), StandardCharsets.US_ASCII);
        }
    }

    /** 测试资源里的证书请求（DER） */
    static byte[] der(String resource) throws Exception {
        return CertificateRequest.decode(text(resource));
    }

    static PublicKey pub(String resource, String alg) throws Exception {
        byte[] spki = Pem.parse(text(resource)).get(0).der;
        return KeyFactory.getInstance(alg).generatePublic(new X509EncodedKeySpec(spki));
    }

    static CaException rejected(byte[] der) {
        return assertThrows(CaException.class, () -> CertificateRequest.parse(der));
    }

    static KeyPair ec(String curve, long seed) throws Exception {
        KeyPairGenerator g = KeyPairGenerator.getInstance("EC");
        g.initialize(new ECGenParameterSpec(curve), random(seed));
        return g.generateKeyPair();
    }

    static KeyPair rsa(int bits, long seed) throws Exception {
        KeyPairGenerator g = KeyPairGenerator.getInstance("RSA");
        g.initialize(bits, random(seed));
        return g.generateKeyPair();
    }

    /** 固定种子（SHA1PRNG 在先 setSeed 再取数时只用这个种子） */
    static SecureRandom random(long seed) throws Exception {
        SecureRandom r = SecureRandom.getInstance("SHA1PRNG");
        r.setSeed(seed);
        return r;
    }

    // ---- OpenSSL 生成的 ----

    @Test
    void readsOpensslEcP256() throws Exception {
        CertificateRequest r = CertificateRequest.parse(der("ec-p256.csr"));
        assertEquals("SHA256withECDSA", r.signatureAlgorithm);
        assertEquals("shop.yr", r.commonName);
        assertEquals(Arrays.asList("shop.yr", "www.shop.yr"), r.dnsNames);
        assertEquals(pub("ec-p256.pub.pem", "EC"), r.publicKey);
        assertArrayEquals(pub("ec-p256.pub.pem", "EC").getEncoded(), r.subjectPublicKeyInfo());
    }

    /**
     * 079 服务里加密卡（个人电脑 tls_pc，Go 的 crypto/x509）出的 CSR（2.3.1.0 第 4 期验收）：卡用固定的测试密钥出、名字两个，
     * 读得出、签名对、CN 是第一个名字、SAN 是全部名字、公钥就是卡的。资源与 079 服务 tlscard/testdata/interop 里的是同一份
     */
    @Test
    void readsTlsCardCsr() throws Exception {
        CertificateRequest r = CertificateRequest.parse(der("tlscard-p256.csr"));
        assertEquals("SHA256withECDSA", r.signatureAlgorithm);
        assertEquals("pc.test.yr", r.commonName);
        assertEquals(Arrays.asList("pc.test.yr", "www.pc.test.yr"), r.dnsNames);
        assertEquals(pub("tlscard-p256.pub.pem", "EC"), r.publicKey);
        assertArrayEquals(pub("tlscard-p256.pub.pem", "EC").getEncoded(), r.subjectPublicKeyInfo());
    }

    @Test
    void readsOpensslEcP384WithMixedCaseAndIdn() throws Exception {
        CertificateRequest r = CertificateRequest.parse(der("ec-p384.csr"));
        assertEquals("SHA384withECDSA", r.signatureAlgorithm);
        assertEquals("WWW.Shop.yr", r.commonName, "主题里还有 O，只取 CN");
        assertEquals("www.shop.yr", r.commonNameAscii());
        assertEquals(new LinkedHashSet<>(Arrays.asList("www.shop.yr", "shop.yr", "xn--0zwm56d.yr")), r.names());
        assertEquals(pub("ec-p384.pub.pem", "EC"), r.publicKey);
    }

    @Test
    void readsOpensslRsa() throws Exception {
        CertificateRequest r = CertificateRequest.parse(der("rsa-2048.csr"));
        assertEquals("SHA256withRSA", r.signatureAlgorithm);
        assertEquals(pub("rsa-2048.pub.pem", "RSA"), r.publicKey);
        assertEquals(Arrays.asList("shop.yr", "www.shop.yr"), r.dnsNames);
        CertificateRequest noCn = CertificateRequest.parse(der("rsa-2048-sha512-no-cn.csr"));
        assertEquals("SHA512withRSA", noCn.signatureAlgorithm);
        assertNull(noCn.commonName);
        assertEquals(Collections.singletonList("shop.yr"), noCn.dnsNames);
    }

    @Test
    void readsCsrWithoutSanOrWithIpSan() throws Exception {
        // 没有 SAN 能读（名字不一致由证书服务拒）；SAN 里有 IP 直接拒
        assertTrue(CertificateRequest.parse(der("no-san.csr")).dnsNames.isEmpty());
        CaException e = rejected(der("ip-san.csr"));
        assertEquals(CaException.Code.BAD_CSR, e.code);
        assertTrue(e.getMessage().contains("dNSName"), e.getMessage());
    }

    @Test
    void base64AndPemAreBothAccepted() throws Exception {
        byte[] d = der("ec-p256.csr");
        assertArrayEquals(d, CertificateRequest.decode(Base64.getEncoder().encodeToString(d)));
        assertArrayEquals(d, CertificateRequest.decode(Base64.getUrlEncoder().withoutPadding().encodeToString(d)));
        assertArrayEquals(d, CertificateRequest.decode(CertificateRequest.pem(d)));
        assertEquals(CaException.Code.BAD_CSR, assertThrows(CaException.class,
                () -> CertificateRequest.decode(text("ec-p256.pub.pem"))).code, "交公钥 PEM 不收");
        assertEquals(CaException.Code.BAD_CSR, assertThrows(CaException.class, () -> CertificateRequest.decode("不是 base64")).code);
    }

    // ---- 拒绝 ----

    @Test
    void unsupportedAlgorithmsAndKeysAreRejected() throws Exception {
        CaException sha1 = rejected(der("rsa-2048-sha1.csr"));
        assertEquals(CaException.Code.BAD_CSR, sha1.code);
        assertTrue(sha1.getMessage().contains("SHA-1"), sha1.getMessage());
        CaException rsa1024 = rejected(der("rsa-1024.csr"));
        assertEquals(CaException.Code.BAD_KEY, rsa1024.code);
        assertTrue(rsa1024.getMessage().contains("1024"), rsa1024.getMessage());
        CaException k1 = rejected(der("ec-secp256k1.csr"));
        assertEquals(CaException.Code.BAD_KEY, k1.code);
        assertTrue(k1.getMessage().contains("P-256"), k1.getMessage());
        // RSA 4096 以上
        KeyPair big = rsa(4104, 7);
        assertEquals(CaException.Code.BAD_KEY, rejected(CertificateRequest.create(big, Collections.singletonList("shop.yr"))).code);
    }

    @Test
    void brokenSignatureIsRejected() throws Exception {
        for (String f : new String[]{"ec-p256.csr", "ec-p384.csr", "rsa-2048.csr"}) {
            byte[] d = der(f);
            d[d.length - 1] ^= 0x01;
            CaException e = rejected(d);
            assertEquals(CaException.Code.BAD_CSR, e.code, f);
            assertTrue(e.getMessage().contains("签名不对"), f + "：" + e.getMessage());
        }
        // 改了请求内容（SAN 里的一个字母），签名就对不上
        byte[] d = der("ec-p256.csr");
        int at = indexOf(d, "www.shop.yr".getBytes(StandardCharsets.US_ASCII));
        d[at] = 'x';
        assertTrue(rejected(d).getMessage().contains("签名不对"));
    }

    @Test
    void anotherKeysSignatureIsRejected() throws Exception {
        // 用 A 的公钥、B 的私钥签：签名对不上 A 的公钥
        KeyPair a = ec("secp256r1", 1);
        KeyPair b = ec("secp256r1", 2);
        byte[] d = CertificateRequest.create(new KeyPair(a.getPublic(), b.getPrivate()), Collections.singletonList("shop.yr"));
        assertEquals(CaException.Code.BAD_CSR, rejected(d).code);
    }

    @Test
    void truncatedOrFlippedDerIsRejectedCleanly() throws Exception {
        byte[] d = der("ec-p256.csr");
        for (int n = 0; n < d.length; n++) {
            CaException e = rejected(Arrays.copyOf(d, n));
            assertEquals(CaException.Code.BAD_CSR, e.code, "截到 " + n + " 字节");
        }
        // 每个字节改一下：都要以 CaException 拒绝（不能读通，也不能抛别的异常）
        for (int i = 0; i < d.length; i++) {
            byte[] x = d.clone();
            x[i] ^= (byte) 0xFF;
            rejected(x);
        }
        // 后面多出东西
        byte[] longer = Arrays.copyOf(d, d.length + 2);
        assertTrue(rejected(longer).getMessage().contains("多出"));
    }

    @Test
    void malformedLengthsAreRejected() {
        // 长度越界
        assertTrue(rejected(new byte[]{0x30, 0x7F, 0x02, 0x01, 0x00}).getMessage().contains("越界"));
        // 不定长（BER）
        assertTrue(rejected(new byte[]{0x30, (byte) 0x80, 0x00, 0x00}).getMessage().contains("不定长"));
        // 长度不是最短写法
        assertTrue(rejected(new byte[]{0x30, (byte) 0x81, 0x03, 0x02, 0x01, 0x00}).getMessage().contains("最短"));
        // 长度字节太多（会溢出）
        assertTrue(rejected(new byte[]{0x30, (byte) 0x84, 0x7F, (byte) 0xFF, (byte) 0xFF, (byte) 0xFF}).getMessage().contains("太大"));
        // 多字节标签
        assertTrue(rejected(new byte[]{0x3F, 0x01, 0x00}).getMessage().contains("标签"));
        assertEquals(CaException.Code.BAD_CSR, rejected(new byte[0]).code);
        assertEquals(CaException.Code.BAD_CSR, rejected(null).code);
    }

    @Test
    void deepNestingIsRejected() {
        byte[] inner = new byte[]{0x05, 0x00};
        for (int i = 0; i < 40; i++) {
            inner = Asn1.sequence(inner);
        }
        CaException e = rejected(inner);
        assertEquals(CaException.Code.BAD_CSR, e.code);
        assertTrue(e.getMessage().contains("嵌套"), e.getMessage());
    }

    @Test
    void oversizedIsRejected() throws Exception {
        byte[] big = new byte[CertificateRequest.MAX_BYTES + 1];
        CaException e = rejected(big);
        assertTrue(e.getMessage().contains("太长"), e.getMessage());
        String b64 = Base64.getEncoder().encodeToString(big);
        assertEquals(CaException.Code.BAD_CSR, assertThrows(CaException.class, () -> CertificateRequest.decode(b64)).code);
        // 名字多到超过 8 KiB 的请求，自己生成得出来，但不收
        String[] many = new String[600];
        for (int i = 0; i < many.length; i++) {
            many[i] = "name" + i + ".shop.yr";
        }
        byte[] d = CertificateRequest.create(ec("secp256r1", 3), Arrays.asList(many));
        assertTrue(d.length > CertificateRequest.MAX_BYTES);
        assertTrue(rejected(d).getMessage().contains("太长"));
    }

    // ---- 自己生成的 ----

    @Test
    void ownRequestsReadBackAndVerify() throws Exception {
        KeyPair[] keys = {ec("secp256r1", 11), ec("secp384r1", 12), rsa(2048, 13)};
        String[] algs = {"SHA256withECDSA", "SHA384withECDSA", "SHA256withRSA"};
        for (int i = 0; i < keys.length; i++) {
            byte[] d = CertificateRequest.create(keys[i], Arrays.asList("Shop.yr", "测试.yr", "shop.yr"));
            CertificateRequest r = CertificateRequest.parse(d);
            assertEquals(algs[i], r.signatureAlgorithm);
            assertEquals(keys[i].getPublic(), r.publicKey);
            assertEquals("shop.yr", r.commonName, "CN 是第一个名字（A-label）");
            assertEquals(Arrays.asList("shop.yr", "xn--0zwm56d.yr"), r.dnsNames, "去重、转 A-label");
            // PEM 来回
            assertArrayEquals(d, CertificateRequest.decode(CertificateRequest.pem(d)));
        }
        assertThrows(IllegalArgumentException.class, () -> CertificateRequest.create(keys[0], Collections.<String>emptyList()));
    }

    static int indexOf(byte[] hay, byte[] needle) {
        outer:
        for (int i = 0; i + needle.length <= hay.length; i++) {
            for (int j = 0; j < needle.length; j++) {
                if (hay[i + j] != needle[j]) {
                    continue outer;
                }
            }
            return i;
        }
        throw new AssertionError("找不到");
    }
}
