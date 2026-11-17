package moe.qingu.youran.server.net.tls;

import moe.qingu.youran.api.network.tls.Pem;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.GeneralSecurityException;
import java.security.KeyFactory;
import java.security.PrivateKey;
import java.security.PublicKey;
import java.security.Signature;
import java.security.cert.X509Certificate;
import java.security.spec.PKCS8EncodedKeySpec;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Date;
import java.util.List;

/**
 * 服务器上的中间 CA（由 QinguMoe CA 签，私钥在服务端配置里）：证书签发服务（{@code server.net.ca.CaService}）用它
 * 给通过认证的网站签证书。叶子证书：公钥是申请者的、主题 CN 是第一个名字、SAN 是全部名字、用途 serverAuth、
 * 有效期 {@value #LEAF_DAYS} 天（生效时间往前拨 1 小时，容忍客户端的钟慢一点）。
 */
public final class IntermediateCa {
    public static final int LEAF_DAYS = 90;
    public static final long DAY_MS = 24L * 3600 * 1000;
    static final long BACKDATE_MS = 3600L * 1000;

    /** 中间 CA 自己的证书，及配置文件里跟在它后面的上级证书（不含根）：握手时跟在叶子后面发出去 */
    final List<X509Certificate> chain;
    final PrivateKey key;

    public IntermediateCa(List<X509Certificate> chain, PrivateKey key) throws GeneralSecurityException {
        if (chain.isEmpty()) {
            throw new GeneralSecurityException("证书文件里没有证书");
        }
        X509Certificate ca = chain.get(0);
        if (ca.getBasicConstraints() < 0) {
            throw new GeneralSecurityException("证书不是 CA 证书（basicConstraints 没有 CA:TRUE）：" + ca.getSubjectX500Principal());
        }
        boolean[] ku = ca.getKeyUsage();
        if (ku != null && !ku[5]) {
            throw new GeneralSecurityException("证书的 keyUsage 没有 keyCertSign，不能签证书");
        }
        // 私钥和证书要对得上：签一段数据，用证书里的公钥验
        String alg = key.getAlgorithm().equals("RSA") ? "SHA256withRSA" : "SHA256withECDSA";
        Signature s = Signature.getInstance(alg);
        s.initSign(key);
        byte[] probe = "youranmod".getBytes(StandardCharsets.US_ASCII);
        s.update(probe);
        byte[] sig = s.sign();
        Signature v = Signature.getInstance(alg);
        v.initVerify(ca.getPublicKey());
        v.update(probe);
        if (!v.verify(sig)) {
            throw new GeneralSecurityException("私钥和证书对不上");
        }
        List<X509Certificate> c = new ArrayList<>();
        for (X509Certificate x : chain) {
            // 自签的根不用发
            if (!x.getSubjectX500Principal().equals(x.getIssuerX500Principal())) {
                c.add(x);
            }
        }
        if (c.isEmpty()) {
            throw new GeneralSecurityException("证书文件里只有自签的根证书，要的是中间 CA");
        }
        this.chain = Collections.unmodifiableList(c);
        this.key = key;
    }

    /** 从配置的两个 PEM 文件读（证书可以带上级，私钥要 PKCS#8 不加密） */
    public static IntermediateCa load(Path certificateFile, Path keyFile) throws IOException, GeneralSecurityException {
        List<X509Certificate> certs = Pem.certificates(Pem.text(Files.readAllBytes(certificateFile)));
        PrivateKey key = null;
        for (Pem.Block b : Pem.parse(Pem.text(Files.readAllBytes(keyFile)))) {
            if (b.label.equals("PRIVATE KEY")) {
                key = parsePkcs8(b.der);
                break;
            }
            if (b.label.equals("ENCRYPTED PRIVATE KEY") || b.label.endsWith(" PRIVATE KEY")) {
                throw new GeneralSecurityException("私钥要不加密的 PKCS#8（-----BEGIN PRIVATE KEY-----），现在是 " + b.label
                        + "；可以用 openssl pkcs8 -topk8 -nocrypt -in 原文件 -out 新文件 转换");
            }
        }
        if (key == null) {
            throw new GeneralSecurityException("私钥文件里没有 -----BEGIN PRIVATE KEY-----");
        }
        return new IntermediateCa(certs, key);
    }

    static PrivateKey parsePkcs8(byte[] der) throws GeneralSecurityException {
        PKCS8EncodedKeySpec spec = new PKCS8EncodedKeySpec(der);
        for (String alg : new String[]{"EC", "RSA"}) {
            try {
                return KeyFactory.getInstance(alg).generatePrivate(spec);
            } catch (GeneralSecurityException ignored) {
                // 试下一种
            }
        }
        throw new GeneralSecurityException("私钥既不是 EC 也不是 RSA");
    }

    public X509Certificate certificate() {
        return chain.get(0);
    }

    /** 中间 CA 自己的证书及跟在后面的上级证书（不含根），握手时跟在叶子后面发出去 */
    public List<X509Certificate> chain() {
        return chain;
    }

    /**
     * 给 {@code names}（A-label，至少一个）签一张网站证书，公钥是申请者给的 {@code key}（EC 或 RSA）。主题 CN 是第一个名字，
     * SAN 是全部名字；有效期 {@value #LEAF_DAYS} 天（生效时间往前拨 1 小时），不超过中间 CA 自己的有效期。
     *
     * @return 叶子在前，后面是 {@link #chain()}
     */
    public List<X509Certificate> issue(List<String> names, PublicKey key, Date now) throws GeneralSecurityException {
        if (names.isEmpty()) {
            throw new IllegalArgumentException("没有名字");
        }
        Date from = new Date(now.getTime() - BACKDATE_MS);
        Date to = new Date(now.getTime() + LEAF_DAYS * DAY_MS);
        if (to.after(certificate().getNotAfter())) {
            to = certificate().getNotAfter();
        }
        CertificateBuilder b = new CertificateBuilder()
                .subject(names.get(0))
                .publicKey(key)
                .issuer(certificate(), this.key)
                .validity(from, to)
                .serverAuth();
        for (String n : names) {
            b.dnsName(n);
        }
        List<X509Certificate> full = new ArrayList<>();
        full.add(b.build());
        full.addAll(chain);
        return Collections.unmodifiableList(full);
    }
}
