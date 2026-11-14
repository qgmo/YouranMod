package moe.qingu.youran.api.network.tls;

import moe.qingu.youran.api.network.Ipv6Address;
import org.apache.logging.log4j.LogManager;

import java.io.IOException;
import java.io.InputStream;
import java.io.ByteArrayOutputStream;
import java.net.IDN;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.security.cert.CertPath;
import java.security.cert.CertPathValidator;
import java.security.cert.CertPathValidatorException;
import java.security.cert.CertificateException;
import java.security.cert.CertificateFactory;
import java.security.cert.CertificateParsingException;
import java.security.cert.PKIXParameters;
import java.security.cert.PKIXReason;
import java.security.cert.TrustAnchor;
import java.security.cert.X509Certificate;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.Collections;
import java.util.Date;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/**
 * 游戏内网络的 TLS 信任：客户端只信任 <b>QinguMoe CA</b> 这一张根证书（打在 YouranMod 的资源里，
 * {@value #ROOT_RESOURCE}），不用系统和 JDK 自带的信任库。
 * <p>
 * 开发环境（未混淆，{@code fml.deobfuscatedEnvironment} 为 true）可以用系统属性 {@value #DEV_ROOT_PROPERTY}
 * 指向一份 PEM，换成测试用的根证书；正式包（混淆后的环境）里这个属性不起作用。
 * <p>
 * 检查：用 PKIX（RFC 5280）验证对方给的链能接到信任的根、每一级都在有效期内、符合 CA 的基本约束与域名约束，
 * 不查吊销；再核对叶子证书的主题备用名（SAN）里有要访问的主机名（不看 CN）、用途允许做网站。
 */
public final class TlsTrust {
    public static final String ROOT_RESOURCE = "/assets/youranmod/network/QinguMoeCA.crt";
    public static final String DEV_ROOT_PROPERTY = "youranmod.network.devTrustRoot";
    static final String SERVER_AUTH = "1.3.6.1.5.5.7.3.1";
    static final String ANY_EKU = "2.5.29.37.0";

    private static volatile TlsTrust defaultTrust;

    private final List<X509Certificate> roots;
    private final Set<TrustAnchor> anchors;

    private TlsTrust(List<X509Certificate> roots) {
        if (roots.isEmpty()) {
            throw new IllegalArgumentException("没有根证书");
        }
        this.roots = Collections.unmodifiableList(new ArrayList<>(roots));
        Set<TrustAnchor> a = new HashSet<>();
        for (X509Certificate c : roots) {
            a.add(new TrustAnchor(c, null));
        }
        this.anchors = Collections.unmodifiableSet(a);
    }

    /** 只信任给定的根证书（测试、开发用） */
    public static TlsTrust of(X509Certificate... roots) {
        return new TlsTrust(Arrays.asList(roots));
    }

    /** 默认的信任：QinguMoe CA（开发环境可换，见类说明） */
    public static TlsTrust qinguMoe() {
        TlsTrust t = defaultTrust;
        if (t == null) {
            synchronized (TlsTrust.class) {
                t = defaultTrust;
                if (t == null) {
                    t = loadDefault();
                    defaultTrust = t;
                }
            }
        }
        return t;
    }

    private static TlsTrust loadDefault() {
        String dev = System.getProperty(DEV_ROOT_PROPERTY);
        if (dev != null && !dev.isEmpty()) {
            if (isDevelopmentEnvironment()) {
                try {
                    List<X509Certificate> certs = Pem.certificates(Pem.text(Files.readAllBytes(Paths.get(dev))));
                    LogManager.getLogger("YouranMod").warn("游戏内网络：开发环境，TLS 改为信任 {} 里的根证书（不是 QinguMoe CA）", dev);
                    return new TlsTrust(certs);
                } catch (IOException | CertificateException | IllegalArgumentException e) {
                    LogManager.getLogger("YouranMod").error("游戏内网络：读不了开发用的根证书 {}，仍信任 QinguMoe CA", dev, e);
                }
            } else {
                LogManager.getLogger("YouranMod").warn("游戏内网络：{} 只在开发环境有效，这里不换信任的根证书", DEV_ROOT_PROPERTY);
            }
        }
        try (InputStream in = TlsTrust.class.getResourceAsStream(ROOT_RESOURCE)) {
            if (in == null) {
                throw new IllegalStateException("模组里没有 " + ROOT_RESOURCE);
            }
            ByteArrayOutputStream buf = new ByteArrayOutputStream();
            byte[] b = new byte[4096];
            int n;
            while ((n = in.read(b)) > 0) {
                buf.write(b, 0, n);
            }
            return new TlsTrust(Pem.certificates(Pem.text(buf.toByteArray())));
        } catch (IOException | CertificateException e) {
            throw new IllegalStateException("读不了 QinguMoe CA 根证书", e);
        }
    }

    /** FML 的开发环境标记（launchwrapper 的黑板）；拿不到时当作不是开发环境 */
    static boolean isDevelopmentEnvironment() {
        try {
            Class<?> launch = Class.forName("net.minecraft.launchwrapper.Launch");
            Object board = launch.getField("blackboard").get(null);
            if (board instanceof java.util.Map) {
                return Boolean.TRUE.equals(((java.util.Map<?, ?>) board).get("fml.deobfuscatedEnvironment"));
            }
        } catch (Throwable ignored) {
            // 不在 Forge 里（单元测试等）
        }
        return false;
    }

    public List<X509Certificate> roots() {
        return roots;
    }

    /**
     * 检查对方给的证书链能不能用来访问 {@code host}（域名或 IPv6 地址）。通过返回 null，否则返回问题。
     *
     * @param chain 对方给的链，叶子在前（可以带上根，会去掉）
     */
    public CertificateProblem check(List<X509Certificate> chain, String host, Date now) {
        if (chain == null || chain.isEmpty()) {
            return CertificateProblem.NO_CERTIFICATE;
        }
        List<X509Certificate> path = new ArrayList<>(chain);
        // 对方把根也发过来时去掉（信任的根只当锚点）
        while (path.size() > 1 && roots.contains(path.get(path.size() - 1))) {
            path.remove(path.size() - 1);
        }
        try {
            CertificateFactory cf = CertificateFactory.getInstance("X.509");
            CertPath cp = cf.generateCertPath(path);
            PKIXParameters params = new PKIXParameters(anchors);
            params.setRevocationEnabled(false);
            params.setDate(now);
            CertPathValidator.getInstance("PKIX").validate(cp, params);
        } catch (CertPathValidatorException e) {
            return problemOf(e);
        } catch (Exception e) {
            return CertificateProblem.INVALID;
        }
        X509Certificate leaf = path.get(0);
        try {
            List<String> eku = leaf.getExtendedKeyUsage();
            if (eku != null && !eku.contains(SERVER_AUTH) && !eku.contains(ANY_EKU)) {
                return CertificateProblem.WRONG_USAGE;
            }
        } catch (CertificateParsingException e) {
            return CertificateProblem.INVALID;
        }
        boolean[] ku = leaf.getKeyUsage();
        if (ku != null && !ku[0] && !ku[2] && !ku[4]) {
            // 数字签名、密钥加密、密钥协商都没有：不能做 TLS 服务端
            return CertificateProblem.WRONG_USAGE;
        }
        if (leaf.getBasicConstraints() >= 0) {
            // CA 证书不能直接当网站证书用
            return CertificateProblem.WRONG_USAGE;
        }
        return matchesHost(leaf, host) ? null : CertificateProblem.HOSTNAME_MISMATCH;
    }

    static CertificateProblem problemOf(CertPathValidatorException e) {
        CertPathValidatorException.Reason r = e.getReason();
        if (r == CertPathValidatorException.BasicReason.EXPIRED) {
            return CertificateProblem.EXPIRED;
        }
        if (r == CertPathValidatorException.BasicReason.NOT_YET_VALID) {
            return CertificateProblem.NOT_YET_VALID;
        }
        if (r == PKIXReason.NO_TRUST_ANCHOR || r == CertPathValidatorException.BasicReason.INVALID_SIGNATURE) {
            return CertificateProblem.UNTRUSTED;
        }
        if (r == PKIXReason.INVALID_NAME) {
            return CertificateProblem.NAME_NOT_PERMITTED;
        }
        if (r == PKIXReason.INVALID_KEY_USAGE) {
            return CertificateProblem.WRONG_USAGE;
        }
        return CertificateProblem.INVALID;
    }

    /** 叶子证书的 SAN 里有没有这个主机名（域名不分大小写，可以有最左一级的通配符；IP 地址按 IP SAN 比） */
    static boolean matchesHost(X509Certificate leaf, String host) {
        String h = host;
        if (h.startsWith("[") && h.endsWith("]")) {
            h = h.substring(1, h.length() - 1);
        }
        Ipv6Address ip = Ipv6Address.tryParse(h);
        Collection<List<?>> names;
        try {
            names = leaf.getSubjectAlternativeNames();
        } catch (CertificateParsingException e) {
            return false;
        }
        if (names == null) {
            return false;
        }
        String want = ip == null ? normalizeName(h) : null;
        for (List<?> n : names) {
            if (n.size() < 2) {
                continue;
            }
            int type = (Integer) n.get(0);
            if (ip != null && type == 7) {
                Ipv6Address a = Ipv6Address.tryParse(String.valueOf(n.get(1)));
                if (ip.equals(a)) {
                    return true;
                }
            } else if (ip == null && type == 2 && want != null) {
                if (dnsMatches(normalizeName(String.valueOf(n.get(1))), want)) {
                    return true;
                }
            }
        }
        return false;
    }

    static String normalizeName(String s) {
        String n = s.toLowerCase(Locale.ROOT);
        if (n.endsWith(".")) {
            n = n.substring(0, n.length() - 1);
        }
        try {
            return IDN.toASCII(n, IDN.ALLOW_UNASSIGNED).toLowerCase(Locale.ROOT);
        } catch (IllegalArgumentException e) {
            return null;
        }
    }

    /** RFC 6125：完全相同，或者 {@code *.example.yr} 匹配正好多一级的名字（通配符只能在最左一级、整级） */
    static boolean dnsMatches(String pattern, String host) {
        if (pattern == null || host == null) {
            return false;
        }
        if (pattern.equals(host)) {
            return true;
        }
        if (pattern.startsWith("*.") && pattern.indexOf('*', 1) < 0) {
            String suffix = pattern.substring(1);
            int dot = host.indexOf('.');
            // 后缀至少两级（不许 *.yr 这种）
            return dot > 0 && host.substring(dot).equals(suffix) && suffix.indexOf('.', 1) > 0;
        }
        return false;
    }
}
