package moe.qingu.youran.server.net.tls;

import moe.qingu.youran.api.network.Ipv6Address;

import java.io.ByteArrayInputStream;
import java.math.BigInteger;
import java.security.GeneralSecurityException;
import java.security.MessageDigest;
import java.security.PrivateKey;
import java.security.PublicKey;
import java.security.SecureRandom;
import java.security.Signature;
import java.security.cert.CertificateFactory;
import java.security.cert.X509Certificate;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Date;
import java.util.List;

/**
 * 签一张 X.509 v3 证书（RFC 5280），自己写 DER，不依赖 JDK 内部类。签发者的密钥是 RSA 时用 SHA256withRSA，EC 时用 SHA256withECDSA。
 * 服务端用它给网站签叶子证书；单元测试也用它造测试用的根证书、中间 CA。
 */
public final class CertificateBuilder {
    static final String OID_CN = "2.5.4.3";
    static final String OID_BASIC_CONSTRAINTS = "2.5.29.19";
    static final String OID_KEY_USAGE = "2.5.29.15";
    static final String OID_EXT_KEY_USAGE = "2.5.29.37";
    static final String OID_SAN = "2.5.29.17";
    static final String OID_SKI = "2.5.29.14";
    static final String OID_AKI = "2.5.29.35";
    static final String OID_NAME_CONSTRAINTS = "2.5.29.30";
    static final String OID_SERVER_AUTH = "1.3.6.1.5.5.7.3.1";

    private byte[] subject;
    private PublicKey publicKey;
    private byte[] issuer;
    private PrivateKey issuerKey;
    private byte[] issuerKeyId;
    private BigInteger serial;
    private Date notBefore;
    private Date notAfter;
    private boolean ca;
    private int pathLen = -1;
    private final List<String> dnsNames = new ArrayList<>();
    private final List<Ipv6Address> ipNames = new ArrayList<>();
    private final List<String> permittedDns = new ArrayList<>();
    private boolean excludeAllIp;
    private boolean serverAuth;

    /** 主题只有一个 CN */
    public CertificateBuilder subject(String commonName) {
        this.subject = name(commonName);
        return this;
    }

    public CertificateBuilder publicKey(PublicKey key) {
        this.publicKey = key;
        return this;
    }

    /** 签发者是一张已有的证书（取它的主题、主题密钥标识） */
    public CertificateBuilder issuer(X509Certificate issuerCert, PrivateKey key) {
        this.issuer = issuerCert.getSubjectX500Principal().getEncoded();
        this.issuerKey = key;
        this.issuerKeyId = keyIdentifierOf(issuerCert);
        return this;
    }

    /** 自签（根证书）：签发者就是主题 */
    public CertificateBuilder selfSigned(PrivateKey key) {
        this.issuer = null;
        this.issuerKey = key;
        this.issuerKeyId = null;
        return this;
    }

    public CertificateBuilder validity(Date from, Date to) {
        this.notBefore = from;
        this.notAfter = to;
        return this;
    }

    public CertificateBuilder serial(BigInteger serial) {
        this.serial = serial;
        return this;
    }

    /** CA 证书；pathLen 为 -1 时不限 */
    public CertificateBuilder ca(int pathLen) {
        this.ca = true;
        this.pathLen = pathLen;
        return this;
    }

    public CertificateBuilder dnsName(String name) {
        this.dnsNames.add(name);
        return this;
    }

    public CertificateBuilder ipName(Ipv6Address address) {
        this.ipNames.add(address);
        return this;
    }

    /** 域名约束：只许这些 DNS 名字（及其下级） */
    public CertificateBuilder permitDns(String... names) {
        this.permittedDns.addAll(Arrays.asList(names));
        return this;
    }

    /** 域名约束：不许任何 IP 地址名字 */
    public CertificateBuilder excludeAllIp() {
        this.excludeAllIp = true;
        return this;
    }

    /** 扩展用途：服务端认证（网站） */
    public CertificateBuilder serverAuth() {
        this.serverAuth = true;
        return this;
    }

    public X509Certificate build() throws GeneralSecurityException {
        if (subject == null || publicKey == null || issuerKey == null || notBefore == null || notAfter == null) {
            throw new IllegalStateException("证书缺少必要的字段");
        }
        BigInteger sn = serial != null ? serial : new BigInteger(127, new SecureRandom()).setBit(126);
        byte[] issuerName = issuer != null ? issuer : subject;
        byte[] spki = publicKey.getEncoded();
        byte[] ownKeyId = keyIdentifier(spki);
        String sigAlg;
        byte[] sigAlgId;
        if (issuerKey.getAlgorithm().equals("RSA")) {
            sigAlg = "SHA256withRSA";
            sigAlgId = Der.sequence(Der.oid("1.2.840.113549.1.1.11"), Der.nul());
        } else if (issuerKey.getAlgorithm().equals("EC")) {
            sigAlg = "SHA256withECDSA";
            sigAlgId = Der.sequence(Der.oid("1.2.840.10045.4.3.2"));
        } else {
            throw new GeneralSecurityException("不支持的签发者密钥：" + issuerKey.getAlgorithm());
        }

        List<byte[]> ext = new ArrayList<>();
        ext.add(extension(OID_BASIC_CONSTRAINTS, true, ca
                ? (pathLen >= 0 ? Der.sequence(Der.bool(true), Der.integer(pathLen)) : Der.sequence(Der.bool(true)))
                : Der.sequence()));
        if (ca) {
            // keyCertSign、cRLSign
            ext.add(extension(OID_KEY_USAGE, true, Der.namedBits(false, false, false, false, false, true, true)));
        } else if (publicKey.getAlgorithm().equals("RSA")) {
            // digitalSignature、keyEncipherment
            ext.add(extension(OID_KEY_USAGE, true, Der.namedBits(true, false, true)));
        } else {
            ext.add(extension(OID_KEY_USAGE, true, Der.namedBits(true)));
        }
        if (serverAuth) {
            ext.add(extension(OID_EXT_KEY_USAGE, false, Der.sequence(Der.oid(OID_SERVER_AUTH))));
        }
        if (!dnsNames.isEmpty() || !ipNames.isEmpty()) {
            List<byte[]> names = new ArrayList<>();
            for (String n : dnsNames) {
                names.add(Der.implicitPrimitive(2, n.getBytes(java.nio.charset.StandardCharsets.US_ASCII)));
            }
            for (Ipv6Address a : ipNames) {
                names.add(Der.implicitPrimitive(7, a.toBytes()));
            }
            ext.add(extension(OID_SAN, false, Der.sequence(names.toArray(new byte[0][]))));
        }
        if (!permittedDns.isEmpty() || excludeAllIp) {
            List<byte[]> parts = new ArrayList<>();
            if (!permittedDns.isEmpty()) {
                List<byte[]> subtrees = new ArrayList<>();
                for (String n : permittedDns) {
                    subtrees.add(Der.sequence(Der.implicitPrimitive(2, n.getBytes(java.nio.charset.StandardCharsets.US_ASCII))));
                }
                parts.add(Der.implicitConstructed(0, Der.concat(subtrees.toArray(new byte[0][]))));
            }
            if (excludeAllIp) {
                parts.add(Der.implicitConstructed(1, Der.concat(
                        Der.sequence(Der.implicitPrimitive(7, new byte[8])),
                        Der.sequence(Der.implicitPrimitive(7, new byte[32])))));
            }
            ext.add(extension(OID_NAME_CONSTRAINTS, true, Der.sequence(parts.toArray(new byte[0][]))));
        }
        ext.add(extension(OID_SKI, false, Der.octets(ownKeyId)));
        byte[] aki = issuer == null ? ownKeyId : issuerKeyId;
        if (aki != null) {
            ext.add(extension(OID_AKI, false, Der.sequence(Der.implicitPrimitive(0, aki))));
        }

        byte[] tbs = Der.sequence(
                Der.explicit(0, Der.integer(2)),
                Der.integer(sn),
                sigAlgId,
                issuerName,
                Der.sequence(Der.time(notBefore), Der.time(notAfter)),
                subject,
                spki,
                Der.explicit(3, Der.sequence(ext.toArray(new byte[0][]))));
        Signature sig = Signature.getInstance(sigAlg);
        sig.initSign(issuerKey);
        sig.update(tbs);
        byte[] cert = Der.sequence(tbs, sigAlgId, Der.bits(sig.sign()));
        return (X509Certificate) CertificateFactory.getInstance("X.509").generateCertificate(new ByteArrayInputStream(cert));
    }

    static byte[] name(String commonName) {
        return Der.sequence(Der.set(Der.sequence(Der.oid(OID_CN), Der.utf8(commonName))));
    }

    private static byte[] extension(String oid, boolean critical, byte[] value) {
        return critical ? Der.sequence(Der.oid(oid), Der.bool(true), Der.octets(value))
                : Der.sequence(Der.oid(oid), Der.octets(value));
    }

    /** 主题密钥标识：整个 SubjectPublicKeyInfo 的 SHA-1（RFC 7093 允许的做法之一，只要上下级一致即可） */
    static byte[] keyIdentifier(byte[] spki) {
        try {
            return MessageDigest.getInstance("SHA-1").digest(spki);
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException(e);
        }
    }

    /** 证书的主题密钥标识扩展；没有时返回 null */
    static byte[] keyIdentifierOf(X509Certificate c) {
        byte[] v = c.getExtensionValue(OID_SKI);
        if (v == null) {
            return null;
        }
        // 外层 OCTET STRING（扩展值）里是 OCTET STRING（标识）
        try {
            byte[] inner = unwrapOctets(v);
            return unwrapOctets(inner);
        } catch (RuntimeException e) {
            return null;
        }
    }

    private static byte[] unwrapOctets(byte[] der) {
        if (der.length < 2 || der[0] != 0x04) {
            throw new IllegalArgumentException("不是 OCTET STRING");
        }
        int len = der[1] & 0xFF;
        int off = 2;
        if (len >= 0x80) {
            int n = len & 0x7F;
            len = 0;
            for (int i = 0; i < n; i++) {
                len = (len << 8) | (der[2 + i] & 0xFF);
            }
            off = 2 + n;
        }
        return Arrays.copyOfRange(der, off, off + len);
    }
}
