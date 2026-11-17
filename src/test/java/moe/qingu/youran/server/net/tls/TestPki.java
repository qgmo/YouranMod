package moe.qingu.youran.server.net.tls;

import moe.qingu.youran.api.network.tls.CertificateTlsContext;

import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.cert.X509Certificate;
import java.security.spec.ECGenParameterSpec;
import java.util.Arrays;
import java.util.Date;

/** 测试用的 PKI：一张“测试根”（冒充 QinguMoe CA）、一张中间 CA（pathlen 0、只许 .yr）。只在测试里用 */
public final class TestPki {
    public static final long DAY = 24L * 3600 * 1000;

    public final KeyPair rootKey;
    public final X509Certificate root;
    public final KeyPair caKey;
    public final X509Certificate ca;

    public TestPki() throws Exception {
        this("测试根 CA", "yr");
    }

    public TestPki(String rootName, String... permitted) throws Exception {
        this(System.currentTimeMillis(), rootName, permitted);
    }

    /** 以 {@code now} 为现在：根前后 10 年、中间 CA 从前一天起一年（假时钟的测试用） */
    public TestPki(long now, String rootName, String... permitted) throws Exception {
        rootKey = rsa();
        root = new CertificateBuilder().subject(rootName).publicKey(rootKey.getPublic()).selfSigned(rootKey.getPrivate())
                .validity(new Date(now - DAY), new Date(now + 3650 * DAY)).ca(-1).build();
        caKey = ec();
        CertificateBuilder b = new CertificateBuilder().subject("测试中间 CA").publicKey(caKey.getPublic()).issuer(root, rootKey.getPrivate())
                .validity(new Date(now - DAY), new Date(now + 365 * DAY)).ca(0).excludeAllIp();
        if (permitted.length > 0) {
            b.permitDns(permitted);
        }
        ca = b.build();
    }

    public IntermediateCa intermediate() throws Exception {
        return new IntermediateCa(Arrays.asList(ca, root), caKey.getPrivate());
    }

    /** 中间 CA 在 {@code now} 给 {@code names} 签一张证书（新的 EC 密钥），放进一个网站用的 TLS */
    public CertificateTlsContext tls(long now, String... names) throws Exception {
        KeyPair kp = ec();
        CertificateTlsContext ctx = new CertificateTlsContext();
        ctx.set(kp.getPrivate(), intermediate().issue(Arrays.asList(names), kp.getPublic(), new Date(now)));
        return ctx;
    }

    public static KeyPair rsa() throws Exception {
        KeyPairGenerator g = KeyPairGenerator.getInstance("RSA");
        g.initialize(2048);
        return g.generateKeyPair();
    }

    public static KeyPair ec() throws Exception {
        KeyPairGenerator g = KeyPairGenerator.getInstance("EC");
        g.initialize(new ECGenParameterSpec("secp256r1"));
        return g.generateKeyPair();
    }
}
