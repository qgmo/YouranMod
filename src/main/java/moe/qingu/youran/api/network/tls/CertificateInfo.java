package moe.qingu.youran.api.network.tls;

import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.cert.CertificateEncodingException;
import java.security.cert.CertificateParsingException;
import java.security.cert.X509Certificate;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.Date;
import java.util.List;
import java.util.Locale;

/** 一张证书给人看的摘要（浏览器“连接信息”里每一级显示的内容） */
public final class CertificateInfo {
    public final String subject;
    public final String issuer;
    public final Date notBefore;
    public final Date notAfter;
    /** SHA-256 指纹，大写十六进制，冒号分隔 */
    public final String sha256;
    /** 主题备用名（DNS 名、IP 地址），没有为空表 */
    public final List<String> subjectAltNames;

    public CertificateInfo(String subject, String issuer, Date notBefore, Date notAfter, String sha256, List<String> subjectAltNames) {
        this.subject = subject;
        this.issuer = issuer;
        this.notBefore = new Date(notBefore.getTime());
        this.notAfter = new Date(notAfter.getTime());
        this.sha256 = sha256;
        this.subjectAltNames = Collections.unmodifiableList(new ArrayList<>(subjectAltNames));
    }

    public static CertificateInfo of(X509Certificate c) {
        String fp;
        try {
            fp = fingerprint(MessageDigest.getInstance("SHA-256").digest(c.getEncoded()));
        } catch (NoSuchAlgorithmException | CertificateEncodingException e) {
            fp = "?";
        }
        return new CertificateInfo(c.getSubjectX500Principal().getName(), c.getIssuerX500Principal().getName(),
                c.getNotBefore(), c.getNotAfter(), fp, altNames(c));
    }

    public static List<CertificateInfo> of(List<X509Certificate> chain) {
        List<CertificateInfo> out = new ArrayList<>();
        for (X509Certificate c : chain) {
            out.add(of(c));
        }
        return out;
    }

    static List<String> altNames(X509Certificate c) {
        List<String> out = new ArrayList<>();
        Collection<List<?>> names;
        try {
            names = c.getSubjectAlternativeNames();
        } catch (CertificateParsingException e) {
            return out;
        }
        if (names == null) {
            return out;
        }
        for (List<?> n : names) {
            if (n.size() < 2) {
                continue;
            }
            int type = (Integer) n.get(0);
            if (type == 2) {
                out.add("DNS:" + n.get(1));
            } else if (type == 7) {
                out.add("IP:" + n.get(1));
            }
        }
        return out;
    }

    static String fingerprint(byte[] digest) {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < digest.length; i++) {
            if (i > 0) {
                sb.append(':');
            }
            sb.append(String.format(Locale.ROOT, "%02X", digest[i] & 0xFF));
        }
        return sb.toString();
    }
}
