package moe.qingu.youran.server.net.tls;

import moe.qingu.youran.api.network.tls.Pem;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.GeneralSecurityException;
import java.security.KeyFactory;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.PrivateKey;
import java.security.SecureRandom;
import java.security.Signature;
import java.security.cert.X509Certificate;
import java.security.spec.ECGenParameterSpec;
import java.security.spec.PKCS8EncodedKeySpec;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Date;
import java.util.List;

/**
 * 服务器上的中间 CA（由 QinguMoe CA 签，私钥在服务端配置里）：给游戏内网站现场签叶子证书。
 * 叶子证书：EC P-256 新密钥、主题 CN 与 SAN 都是网站的域名、用途 serverAuth、有效期 {@value #LEAF_DAYS} 天
 * （生效时间往前拨 1 小时，容忍客户端的钟慢一点）。
 */
public final class IntermediateCa {
    static final int LEAF_DAYS = 90;
    static final long DAY_MS = 24L * 3600 * 1000;
    static final long BACKDATE_MS = 3600L * 1000;

    /** 中间 CA 自己的证书，及配置文件里跟在它后面的上级证书（不含根）：握手时跟在叶子后面发出去 */
    final List<X509Certificate> chain;
    final PrivateKey key;
    private final SecureRandom random = new SecureRandom();

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

    /** 签一张给 {@code name} 用的叶子证书 */
    public Issued issue(String name, Date now) throws GeneralSecurityException {
        KeyPairGenerator g = KeyPairGenerator.getInstance("EC");
        g.initialize(new ECGenParameterSpec("secp256r1"), random);
        KeyPair kp = g.generateKeyPair();
        Date from = new Date(now.getTime() - BACKDATE_MS);
        Date to = new Date(now.getTime() + LEAF_DAYS * DAY_MS);
        // 不超过中间 CA 自己的有效期
        if (to.after(certificate().getNotAfter())) {
            to = certificate().getNotAfter();
        }
        X509Certificate leaf = new CertificateBuilder()
                .subject(name)
                .publicKey(kp.getPublic())
                .issuer(certificate(), key)
                .validity(from, to)
                .dnsName(name)
                .serverAuth()
                .build();
        List<X509Certificate> full = new ArrayList<>();
        full.add(leaf);
        full.addAll(chain);
        return new Issued(name, kp.getPrivate(), full);
    }

    /** 签好的一张叶子证书（连同发给客户端的链） */
    public static final class Issued {
        public final String name;
        public final PrivateKey key;
        /** 叶子在前，后面是中间 CA（不含根） */
        public final List<X509Certificate> chain;

        Issued(String name, PrivateKey key, List<X509Certificate> chain) {
            this.name = name;
            this.key = key;
            this.chain = Collections.unmodifiableList(chain);
        }

        public X509Certificate leaf() {
            return chain.get(0);
        }
    }
}
