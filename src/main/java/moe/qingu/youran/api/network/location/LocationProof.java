package moe.qingu.youran.api.network.location;

import moe.qingu.youran.api.network.Ipv6Address;

import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.KeyFactory;
import java.security.MessageDigest;
import java.security.PrivateKey;
import java.security.PublicKey;
import java.security.Signature;
import java.security.spec.X509EncodedKeySpec;
import java.util.Base64;
import java.util.regex.Pattern;

/**
 * 位置证明（定位模块，服主 11-15）：YouranMod 用服务端的私钥给“某台设备此刻在哪”签的名。纯文本，一行一项，方便任何网站解析：
 * <pre>
 * youran-location-proof v1
 * dimension=0
 * x=120
 * y=64
 * z=-35
 * device=fd12:3456:789a::c:4000:1e00
 * time=1763200000
 * site=shop.yr
 * nonce=3q2-7wEXAMPLE
 * key=1a2b3c4d5e6f7a8b
 * signature=MEUCIQ…
 * </pre>
 * <ul>
 * <li>{@code dimension}：Minecraft 的维度 ID；{@code x y z}：设备（电脑方块）所在的方块坐标；都是十进制整数。</li>
 * <li>{@code device}：设备的游戏内 IPv6 地址（RFC 5952 写法，小写、最长的连续 0 缩写）。网站要和这次请求的 TCP 来源地址比对，换一台电脑用不了。</li>
 * <li>{@code time}：签名时刻，Unix 秒（UTC）。</li>
 * <li>{@code site}：要位置的网站的域名（A-label，小写），网站要核对是自己；{@code nonce}：网站给的一次性随机数（8–128 个 {@code A-Z a-z 0-9 . _ ~ -}），
 * 网站要核对是自己发的、没用过，防转给别的网站、防重放。</li>
 * <li>{@code key}：签名公钥的编号（公钥 DER 的 SHA-256 的前 16 个十六进制字）。</li>
 * <li>{@code signature}：ECDSA P-256 + SHA-256 的签名（DER 编码，标准 base64）。签的是从第一行到 {@code key=} 那一行（含）的 UTF-8 字节，
 * 每行以一个换行符 LF（0x0A）结束。表单提交时浏览器可能把换行改成 CRLF，核对前先把 CRLF 换回 LF、去掉末尾的空白。</li>
 * </ul>
 * 公钥（PEM，SubjectPublicKeyInfo）公开在 {@value LocationProofs#KEY_URL}；不能自己算 ECDSA 的程序可以把证明交给
 * {@code http://location.yr/verify} 核对（见 {@link LocationProofs}）。
 */
public final class LocationProof {
    public static final String HEADER = "youran-location-proof v1";
    public static final String ALGORITHM = "SHA256withECDSA";
    /** 证明全文最长多少字节（正常不到 400） */
    public static final int MAX_LENGTH = 1024;
    public static final Pattern NONCE = Pattern.compile("[A-Za-z0-9._~-]{8,128}");
    /** 域名（A-label）：小写字母、数字、连字符与点 */
    public static final Pattern SITE = Pattern.compile("(?=.{1,253}$)[a-z0-9]([a-z0-9-]{0,61}[a-z0-9])?(\\.[a-z0-9]([a-z0-9-]{0,61}[a-z0-9])?)*");
    private static final String[] KEYS = {"dimension", "x", "y", "z", "device", "time", "site", "nonce", "key"};

    public final int dimension;
    public final int x;
    public final int y;
    public final int z;
    public final Ipv6Address device;
    /** 签名时刻（Unix 秒） */
    public final long time;
    public final String site;
    public final String nonce;
    public final String keyId;
    /** 签名（DER）；还没签时为 null */
    private final byte[] signature;

    public LocationProof(int dimension, int x, int y, int z, Ipv6Address device, long time, String site, String nonce, String keyId) {
        this(dimension, x, y, z, device, time, site, nonce, keyId, null);
    }

    private LocationProof(int dimension, int x, int y, int z, Ipv6Address device, long time, String site, String nonce, String keyId, byte[] signature) {
        if (device == null || site == null || !SITE.matcher(site).matches() || nonce == null || !NONCE.matcher(nonce).matches()
                || keyId == null || !keyId.matches("[0-9a-f]{16}")) {
            throw new IllegalArgumentException("位置证明的字段不对");
        }
        this.dimension = dimension;
        this.x = x;
        this.y = y;
        this.z = z;
        this.device = device;
        this.time = time;
        this.site = site;
        this.nonce = nonce;
        this.keyId = keyId;
        this.signature = signature == null ? null : signature.clone();
    }

    /** 被签的那部分（含每行末尾的 LF） */
    public String signedText() {
        return HEADER + "\n"
                + "dimension=" + dimension + "\n"
                + "x=" + x + "\n"
                + "y=" + y + "\n"
                + "z=" + z + "\n"
                + "device=" + device + "\n"
                + "time=" + time + "\n"
                + "site=" + site + "\n"
                + "nonce=" + nonce + "\n"
                + "key=" + keyId + "\n";
    }

    /** 用私钥签名，返回带签名的证明 */
    public LocationProof sign(PrivateKey key) throws GeneralSecurityException {
        Signature s = Signature.getInstance(ALGORITHM);
        s.initSign(key);
        s.update(signedText().getBytes(StandardCharsets.UTF_8));
        return new LocationProof(dimension, x, y, z, device, time, site, nonce, keyId, s.sign());
    }

    /** 全文（含签名行，最后一行也以 LF 结束）；没签时抛 {@link IllegalStateException} */
    public String text() {
        if (signature == null) {
            throw new IllegalStateException("还没签名");
        }
        return signedText() + "signature=" + Base64.getEncoder().encodeToString(signature) + "\n";
    }

    @Override
    public String toString() {
        return signature == null ? signedText() : text();
    }

    /** 公钥的编号：DER（SubjectPublicKeyInfo）的 SHA-256 的前 16 个十六进制字 */
    public static String keyId(PublicKey key) {
        try {
            byte[] d = MessageDigest.getInstance("SHA-256").digest(key.getEncoded());
            StringBuilder sb = new StringBuilder();
            for (int i = 0; i < 8; i++) {
                sb.append(String.format("%02x", d[i] & 0xFF));
            }
            return sb.toString();
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException(e);
        }
    }

    /** 读 PEM 公钥（{@code -----BEGIN PUBLIC KEY-----}，EC P-256） */
    public static PublicKey readPublicKey(String pem) throws GeneralSecurityException {
        String b64 = pem.replace("-----BEGIN PUBLIC KEY-----", "").replace("-----END PUBLIC KEY-----", "").replaceAll("\\s", "");
        return KeyFactory.getInstance("EC").generatePublic(new X509EncodedKeySpec(Base64.getDecoder().decode(b64)));
    }

    /** 写成 PEM 公钥 */
    public static String publicKeyPem(PublicKey key) {
        String b64 = Base64.getMimeEncoder(64, new byte[]{'\n'}).encodeToString(key.getEncoded());
        return "-----BEGIN PUBLIC KEY-----\n" + b64 + "\n-----END PUBLIC KEY-----\n";
    }

    /**
     * 解析证明全文（不核对签名）。CRLF 当 LF；去掉首尾空白。格式不对抛 {@link IllegalArgumentException}（消息是原因）。
     */
    public static LocationProof parse(String text) {
        if (text == null) {
            throw new IllegalArgumentException("没有证明");
        }
        if (text.length() > MAX_LENGTH) {
            throw new IllegalArgumentException("证明太长");
        }
        String[] lines = text.replace("\r\n", "\n").trim().split("\n", -1);
        if (lines.length != KEYS.length + 2 || !lines[0].equals(HEADER)) {
            throw new IllegalArgumentException("不是位置证明（第一行应为“" + HEADER + "”，共 " + (KEYS.length + 2) + " 行）");
        }
        String[] v = new String[KEYS.length];
        for (int i = 0; i < KEYS.length; i++) {
            String prefix = KEYS[i] + "=";
            if (!lines[i + 1].startsWith(prefix)) {
                throw new IllegalArgumentException("第 " + (i + 2) + " 行应为 " + prefix + "…");
            }
            v[i] = lines[i + 1].substring(prefix.length());
        }
        String sigLine = lines[KEYS.length + 1];
        if (!sigLine.startsWith("signature=")) {
            throw new IllegalArgumentException("最后一行应为 signature=…");
        }
        byte[] sig;
        try {
            sig = Base64.getDecoder().decode(sigLine.substring("signature=".length()));
        } catch (IllegalArgumentException e) {
            throw new IllegalArgumentException("签名不是 base64");
        }
        LocationProof p;
        try {
            Ipv6Address device = Ipv6Address.parse(v[4]);
            p = new LocationProof(integer(v[0]), integer(v[1]), integer(v[2]), integer(v[3]), device,
                    Long.parseLong(v[5]), v[6], v[7], v[8], sig);
        } catch (RuntimeException e) {
            throw new IllegalArgumentException("字段不对（数字、地址、域名、随机数或公钥编号）");
        }
        // 字段要是规范写法（签的是规范写法；换一种写法的地址、带前导 0 或加号的数都不算）
        StringBuilder given = new StringBuilder();
        for (int i = 0; i <= KEYS.length; i++) {
            given.append(lines[i]).append('\n');
        }
        if (!p.signedText().equals(given.toString())) {
            throw new IllegalArgumentException("字段不是规范写法");
        }
        return p;
    }

    private static int integer(String s) {
        return Integer.parseInt(s);
    }

    /** 用公钥核对签名（只核签名，不核网站、设备、时间、随机数；那些用 {@link LocationVerifier}） */
    public boolean signatureValid(PublicKey key) {
        if (signature == null || key == null) {
            return false;
        }
        if (!keyId.equals(keyId(key))) {
            return false;
        }
        try {
            Signature s = Signature.getInstance(ALGORITHM);
            s.initVerify(key);
            s.update(signedText().getBytes(StandardCharsets.UTF_8));
            return s.verify(signature);
        } catch (GeneralSecurityException | RuntimeException e) {
            return false;
        }
    }
}
