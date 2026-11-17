package moe.qingu.youran.api.network.ca;

import moe.qingu.youran.api.network.DomainNames;
import moe.qingu.youran.api.network.tls.Pem;

import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.KeyFactory;
import java.security.KeyPair;
import java.security.PublicKey;
import java.security.Signature;
import java.security.interfaces.ECPublicKey;
import java.security.interfaces.RSAPublicKey;
import java.security.spec.X509EncodedKeySpec;
import java.util.ArrayList;
import java.util.Base64;
import java.util.Collection;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.List;

/**
 * 证书请求（CSR，PKCS#10 / RFC 2986）：向证书签发服务申请时交它。里面有公钥、主题（CN）、要的名字（subjectAltName 的 dNSName），
 * 并用对应的私钥签了名，证明申请者握着这把私钥。
 * <p>
 * {@link #create} 生成一份（{@link CertificateClient} 用它）；{@link #parse} 读出来并核对签名（证书服务用它）。
 * 只收这些：签名 SHA256withECDSA、SHA384withECDSA（公钥 EC P-256 / P-384），SHA256withRSA、SHA384withRSA、SHA512withRSA
 * （RSA 2048–4096 位）；DER 最多 {@value #MAX_BYTES} 字节。自己读写 DER，不用第三方库、不用 JDK 内部类。
 */
public final class CertificateRequest {
    /** DER 最多这么多字节 */
    public static final int MAX_BYTES = 8 * 1024;

    static final String OID_CN = "2.5.4.3";
    static final String OID_EXTENSION_REQUEST = "1.2.840.113549.1.9.14";
    static final String OID_SAN = "2.5.29.17";
    static final String OID_EC_KEY = "1.2.840.10045.2.1";
    static final String OID_RSA_KEY = "1.2.840.113549.1.1.1";
    static final String OID_P256 = "1.2.840.10045.3.1.7";
    static final String OID_P384 = "1.3.132.0.34";
    static final String OID_ECDSA_SHA256 = "1.2.840.10045.4.3.2";
    static final String OID_ECDSA_SHA384 = "1.2.840.10045.4.3.3";
    static final String OID_RSA_SHA256 = "1.2.840.113549.1.1.11";
    static final String OID_RSA_SHA384 = "1.2.840.113549.1.1.12";
    static final String OID_RSA_SHA512 = "1.2.840.113549.1.1.13";
    static final String OID_RSA_SHA1 = "1.2.840.113549.1.1.5";
    static final String OID_ECDSA_SHA1 = "1.2.840.10045.4.1";
    /** SAN 里的 dNSName：[2] IMPLICIT IA5String */
    static final int TAG_DNS_NAME = 0x82;

    /** 请求里的公钥（签发的证书就用它） */
    public final PublicKey publicKey;
    /** 公钥的 SubjectPublicKeyInfo（DER，照请求里的原样） */
    private final byte[] subjectPublicKeyInfo;
    /** 主题里的 CN（原样）；没有为 null */
    public final String commonName;
    /** subjectAltName 里的 dNSName（原样、按出现顺序）；没有 SAN 时为空表 */
    public final List<String> dnsNames;
    /** 签名算法（JCA 的名字，如 {@code SHA256withECDSA}） */
    public final String signatureAlgorithm;

    private CertificateRequest(PublicKey publicKey, byte[] spki, String commonName, List<String> dnsNames, String signatureAlgorithm) {
        this.publicKey = publicKey;
        this.subjectPublicKeyInfo = spki;
        this.commonName = commonName;
        this.dnsNames = dnsNames;
        this.signatureAlgorithm = signatureAlgorithm;
    }

    /** 公钥的 SubjectPublicKeyInfo（DER） */
    public byte[] subjectPublicKeyInfo() {
        return subjectPublicKeyInfo.clone();
    }

    // ---- 生成 ----

    /**
     * 生成一份证书请求（DER）：CN 是第一个名字，SAN 是全部名字（都转成 A-label），用 {@code keys} 的私钥签名。
     * EC P-256 用 SHA256withECDSA，P-384 用 SHA384withECDSA，RSA 用 SHA256withRSA。
     *
     * @throws IllegalArgumentException 没有名字、名字转不了 A-label
     */
    public static byte[] create(KeyPair keys, Collection<String> names) throws GeneralSecurityException {
        LinkedHashSet<String> set = new LinkedHashSet<>();
        for (String n : names) {
            set.add(DomainNames.toAscii(n));
        }
        if (set.isEmpty()) {
            throw new IllegalArgumentException("至少要一个名字");
        }
        PublicKey pub = keys.getPublic();
        String alg;
        String algOid;
        if (pub instanceof ECPublicKey) {
            boolean p384 = ((ECPublicKey) pub).getParams().getCurve().getField().getFieldSize() == 384;
            alg = p384 ? "SHA384withECDSA" : "SHA256withECDSA";
            algOid = p384 ? OID_ECDSA_SHA384 : OID_ECDSA_SHA256;
        } else if (pub instanceof RSAPublicKey) {
            alg = "SHA256withRSA";
            algOid = OID_RSA_SHA256;
        } else {
            throw new GeneralSecurityException("只会用 EC、RSA 的密钥生成证书请求");
        }
        List<String> list = new ArrayList<>(set);
        byte[] subject = Asn1.sequence(Asn1.set(Asn1.sequence(Asn1.oid(OID_CN),
                Asn1.tlv(Asn1.UTF8_STRING, list.get(0).getBytes(StandardCharsets.UTF_8)))));
        byte[][] general = new byte[list.size()][];
        for (int i = 0; i < list.size(); i++) {
            general[i] = Asn1.tlv(TAG_DNS_NAME, list.get(i).getBytes(StandardCharsets.US_ASCII));
        }
        byte[] san = Asn1.sequence(Asn1.oid(OID_SAN), Asn1.tlv(Asn1.OCTET_STRING, Asn1.sequence(general)));
        byte[] attributes = Asn1.tlv(0xA0, Asn1.sequence(Asn1.oid(OID_EXTENSION_REQUEST), Asn1.set(Asn1.sequence(san))));
        byte[] info = Asn1.sequence(Asn1.tlv(Asn1.INTEGER, new byte[]{0}), subject, pub.getEncoded(), attributes);
        Signature s = Signature.getInstance(alg);
        s.initSign(keys.getPrivate());
        s.update(info);
        byte[] sig = s.sign();
        byte[] algId = alg.endsWith("RSA") ? Asn1.sequence(Asn1.oid(algOid), new byte[]{Asn1.NULL, 0}) : Asn1.sequence(Asn1.oid(algOid));
        byte[] bits = new byte[sig.length + 1];
        System.arraycopy(sig, 0, bits, 1, sig.length);
        return Asn1.sequence(info, algId, Asn1.tlv(Asn1.BIT_STRING, bits));
    }

    /** PEM（{@code -----BEGIN CERTIFICATE REQUEST-----}） */
    public static String pem(byte[] der) {
        return Pem.encode("CERTIFICATE REQUEST", der);
    }

    // ---- 读 ----

    /**
     * 把 HTTP 接口里交来的文字转成 DER：PEM（{@code -----BEGIN CERTIFICATE REQUEST-----}，{@code openssl req} 的输出），
     * 或 DER 的 base64（标准或 URL 安全的写法都行）。
     *
     * @throws CaException {@link CaException.Code#BAD_CSR}：不是 PEM 也不是 base64、PEM 里没有证书请求、太长
     */
    public static byte[] decode(String text) throws CaException {
        if (text.length() > MAX_BYTES * 2) {
            throw bad("证书请求太长（DER 最多 " + MAX_BYTES + " 字节）");
        }
        byte[] der = null;
        if (text.contains("-----BEGIN")) {
            try {
                for (Pem.Block b : Pem.parse(text)) {
                    if (b.label.equals("CERTIFICATE REQUEST") || b.label.equals("NEW CERTIFICATE REQUEST")) {
                        der = b.der;
                        break;
                    }
                }
            } catch (IllegalArgumentException e) {
                throw bad("PEM 里的 base64 写得不对");
            }
            if (der == null) {
                throw bad("PEM 里没有 -----BEGIN CERTIFICATE REQUEST-----（要的是证书请求，不是公钥、证书或私钥）");
            }
        } else {
            String s = text.replaceAll("\\s", "");
            try {
                der = s.contains("-") || s.contains("_") ? Base64.getUrlDecoder().decode(s) : Base64.getDecoder().decode(s);
            } catch (IllegalArgumentException e) {
                throw bad("csr 不是 PEM，也不是 base64");
            }
        }
        if (der.length > MAX_BYTES) {
            throw bad("证书请求太长（DER 最多 " + MAX_BYTES + " 字节）");
        }
        return der;
    }

    /**
     * 读一份证书请求（DER）并核对签名。签名、算法、公钥、结构有一样不对就拒绝；名字是否与申请的一致由调用方比（{@link #names}）。
     *
     * @throws CaException {@link CaException.Code#BAD_CSR}（读不了、签名不对、签名算法不收）或
     *                     {@link CaException.Code#BAD_KEY}（公钥类型、曲线、长度不收）
     */
    public static CertificateRequest parse(byte[] der) throws CaException {
        if (der == null || der.length == 0) {
            throw bad("没有证书请求");
        }
        if (der.length > MAX_BYTES) {
            throw bad("证书请求太长（DER 最多 " + MAX_BYTES + " 字节）");
        }
        try {
            Asn1.Tlv outer = Asn1.Reader.single(der).expect(Asn1.SEQUENCE, "证书请求");
            Asn1.checkStructure(outer);
            Asn1.Reader top = outer.children();
            Asn1.Tlv info = top.next().expect(Asn1.SEQUENCE, "CertificationRequestInfo ");
            Asn1.Tlv sigAlg = top.next().expect(Asn1.SEQUENCE, "签名算法");
            byte[] signature = top.next().bitString();
            top.end("证书请求");

            Asn1.Reader r = info.children();
            if (r.next().smallInt() != 0) {
                throw new Asn1.Malformed("版本要是 v1（0）");
            }
            String cn = commonName(r.next().expect(Asn1.SEQUENCE, "主题"));
            Asn1.Tlv spki = r.next().expect(Asn1.SEQUENCE, "SubjectPublicKeyInfo ");
            Asn1.Tlv attrs = r.next().expect(0xA0, "属性");
            r.end("CertificationRequestInfo ");
            List<String> dns = dnsNames(attrs);

            String keyType = keyType(spki);
            PublicKey key = publicKey(spki, keyType);
            String alg = signatureAlgorithm(sigAlg, keyType);
            boolean ok;
            try {
                Signature s = Signature.getInstance(alg);
                s.initVerify(key);
                s.update(der, info.start, info.end - info.start);
                ok = s.verify(signature);
            } catch (GeneralSecurityException | RuntimeException e) {
                ok = false;
            }
            if (!ok) {
                throw bad("证书请求的签名不对（要用请求里公钥对应的私钥签）");
            }
            return new CertificateRequest(key, spki.encoded(), cn, Collections.unmodifiableList(dns), alg);
        } catch (Asn1.Malformed e) {
            throw bad("读不了证书请求（DER）：" + e.getMessage());
        }
    }

    /**
     * 请求里的名字（SAN 的 dNSName），转成小写 A-label、去重。
     *
     * @throws CaException {@link CaException.Code#BAD_CSR}：有名字转不了
     */
    public LinkedHashSet<String> names() throws CaException {
        LinkedHashSet<String> out = new LinkedHashSet<>();
        for (String n : dnsNames) {
            out.add(ascii(n, "subjectAltName 里的名字"));
        }
        return out;
    }

    /** CN 转成小写 A-label；没有 CN 为 null */
    public String commonNameAscii() throws CaException {
        return commonName == null ? null : ascii(commonName, "CN");
    }

    private static String ascii(String n, String what) throws CaException {
        try {
            return DomainNames.toAscii(n);
        } catch (IllegalArgumentException e) {
            throw bad(what + "写得不对：" + e.getMessage());
        }
    }

    /** 主题里的 CN；最多一个 */
    private static String commonName(Asn1.Tlv name) throws Asn1.Malformed, CaException {
        String cn = null;
        Asn1.Reader rdns = name.children();
        while (rdns.hasNext()) {
            Asn1.Reader rdn = rdns.next().expect(Asn1.SET, "主题的 RDN ").children();
            while (rdn.hasNext()) {
                Asn1.Reader atv = rdn.next().expect(Asn1.SEQUENCE, "主题的属性").children();
                String type = atv.next().oid();
                Asn1.Tlv value = atv.next();
                atv.end("主题的属性");
                if (type.equals(OID_CN)) {
                    if (cn != null) {
                        throw bad("主题里只能有一个 CN");
                    }
                    cn = value.string();
                }
            }
        }
        return cn;
    }

    /** 属性里 extensionRequest 的 subjectAltName 的 dNSName */
    private static List<String> dnsNames(Asn1.Tlv attrs) throws Asn1.Malformed, CaException {
        List<String> out = new ArrayList<>();
        boolean seenExt = false;
        boolean seenSan = false;
        Asn1.Reader as = attrs.children();
        while (as.hasNext()) {
            Asn1.Reader a = as.next().expect(Asn1.SEQUENCE, "属性").children();
            String type = a.next().oid();
            Asn1.Tlv values = a.next().expect(Asn1.SET, "属性值");
            a.end("属性");
            if (!type.equals(OID_EXTENSION_REQUEST)) {
                continue;
            }
            if (seenExt) {
                throw bad("extensionRequest 只能有一个");
            }
            seenExt = true;
            Asn1.Reader vs = values.children();
            Asn1.Tlv exts = vs.next().expect(Asn1.SEQUENCE, "扩展");
            vs.end("extensionRequest ");
            Asn1.Reader es = exts.children();
            while (es.hasNext()) {
                Asn1.Reader e = es.next().expect(Asn1.SEQUENCE, "扩展").children();
                String oid = e.next().oid();
                Asn1.Tlv v = e.next();
                if (v.tag == Asn1.BOOLEAN) {
                    v = e.next();
                }
                v.expect(Asn1.OCTET_STRING, "扩展的值");
                e.end("扩展");
                if (!oid.equals(OID_SAN)) {
                    continue;
                }
                if (seenSan) {
                    throw bad("subjectAltName 只能有一个");
                }
                seenSan = true;
                Asn1.Tlv gn = Asn1.Reader.single(v.content()).expect(Asn1.SEQUENCE, "subjectAltName ");
                Asn1.Reader g = gn.children();
                while (g.hasNext()) {
                    Asn1.Tlv one = g.next();
                    if (one.tag != TAG_DNS_NAME) {
                        throw bad("subjectAltName 里只收 DNS 名字（dNSName），不收 IP、邮箱等");
                    }
                    out.add(Asn1.ascii(one.content()));
                }
            }
        }
        return out;
    }

    private static String keyType(Asn1.Tlv spki) throws Asn1.Malformed, CaException {
        Asn1.Reader r = spki.children();
        Asn1.Reader alg = r.next().expect(Asn1.SEQUENCE, "公钥算法").children();
        String oid = alg.next().oid();
        if (oid.equals(OID_EC_KEY)) {
            Asn1.Tlv curve = alg.next();
            if (curve.tag != Asn1.OID) {
                throw new CaException(CaException.Code.BAD_KEY, "EC 公钥要用命名曲线（P-256 或 P-384）");
            }
            String c = curve.oid();
            alg.end("公钥算法");
            if (c.equals(OID_P256) || c.equals(OID_P384)) {
                return "EC";
            }
            throw new CaException(CaException.Code.BAD_KEY, "EC 公钥只收 P-256、P-384（现在是曲线 " + c + "）");
        }
        if (oid.equals(OID_RSA_KEY)) {
            return "RSA";
        }
        throw new CaException(CaException.Code.BAD_KEY, "公钥只收 EC（P-256、P-384）和 RSA（现在是 " + oid + "）");
    }

    private static PublicKey publicKey(Asn1.Tlv spki, String type) throws CaException {
        PublicKey key;
        try {
            key = KeyFactory.getInstance(type).generatePublic(new X509EncodedKeySpec(spki.encoded()));
        } catch (GeneralSecurityException | RuntimeException e) {
            throw new CaException(CaException.Code.BAD_KEY, "读不了证书请求里的公钥（" + type + "）");
        }
        if (key instanceof RSAPublicKey) {
            int bits = ((RSAPublicKey) key).getModulus().bitLength();
            if (bits < 2048 || bits > 4096) {
                throw new CaException(CaException.Code.BAD_KEY, "RSA 公钥要 2048–4096 位（现在是 " + bits + " 位）");
            }
        }
        return key;
    }

    private static String signatureAlgorithm(Asn1.Tlv algId, String keyType) throws Asn1.Malformed, CaException {
        Asn1.Reader r = algId.children();
        String oid = r.next().oid();
        boolean ec;
        String name;
        switch (oid) {
            case OID_ECDSA_SHA256:
                ec = true;
                name = "SHA256withECDSA";
                break;
            case OID_ECDSA_SHA384:
                ec = true;
                name = "SHA384withECDSA";
                break;
            case OID_RSA_SHA256:
                ec = false;
                name = "SHA256withRSA";
                break;
            case OID_RSA_SHA384:
                ec = false;
                name = "SHA384withRSA";
                break;
            case OID_RSA_SHA512:
                ec = false;
                name = "SHA512withRSA";
                break;
            case OID_RSA_SHA1:
            case OID_ECDSA_SHA1:
                throw bad("不收 SHA-1 的签名（要 SHA-256 以上）");
            default:
                throw bad("不收这种签名算法（" + oid + "）；收 SHA256/384withECDSA、SHA256/384/512withRSA");
        }
        if (ec) {
            // ECDSA 的算法标识不带参数
            r.end("签名算法");
        } else {
            Asn1.Tlv p = r.optional(Asn1.NULL);
            if (p != null && p.length() != 0) {
                throw new Asn1.Malformed("签名算法的参数不对");
            }
            r.end("签名算法");
        }
        if (ec != keyType.equals("EC")) {
            throw bad("签名算法（" + name + "）和公钥（" + keyType + "）对不上");
        }
        return name;
    }

    static CaException bad(String message) {
        return new CaException(CaException.Code.BAD_CSR, message);
    }
}
