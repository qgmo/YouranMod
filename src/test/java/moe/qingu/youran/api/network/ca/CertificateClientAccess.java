package moe.qingu.youran.api.network.ca;

import java.security.PrivateKey;
import java.security.PublicKey;
import java.security.cert.X509Certificate;
import java.util.List;

/** 测试用：别的包里的测试调 {@link CertificateClient} 包内的核对方法 */
public final class CertificateClientAccess {
    private CertificateClientAccess() {
    }

    public static String problem(CertificateStore.Saved saved, List<String> names, List<X509Certificate> issuer, long now) {
        return CertificateClient.problem(saved, names, issuer, now);
    }

    public static boolean keyMatches(PrivateKey key, PublicKey pub) {
        return CertificateClient.keyMatches(key, pub);
    }
}
