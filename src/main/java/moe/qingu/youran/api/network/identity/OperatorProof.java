package moe.qingu.youran.api.network.identity;

import moe.qingu.youran.api.network.Ipv6Address;
import moe.qingu.youran.api.network.location.LocationProof;

import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.PrivateKey;
import java.security.PublicKey;
import java.security.Signature;
import java.util.Base64;
import java.util.Locale;
import java.util.UUID;
import java.util.regex.Pattern;

/**
 * 操作者证明（身份模块，服主 11-27）：电脑模组替它的操作者签的“这个地址现在是这名玩家在操作”，由 YouranMod 用服务端的私钥签名。
 * 纯文本，一行一项，格式与位置证明（{@link LocationProof}）同一个做法：
 * <pre>
 * youran-operator-proof v1
 * uuid=0f8e1c2a-3b4d-4e5f-8a9b-0c1d2e3f4a5b
 * name=Steve
 * address=fd12:3456:789a::c:4000:1e00
 * time=1763200000
 * site=id.yr
 * nonce=3q2-7wEXAMPLE
 * key=1a2b3c4d5e6f7a8b
 * signature=MEUCIQ…
 * </pre>
 * <ul>
 * <li>{@code uuid}、{@code name}：这名玩家（小写带连字符的 UUID；名字是登录时的名字）。</li>
 * <li>{@code address}：签名时这张网卡的地址（RFC 5952 写法）。网站要和这次请求的 TCP 来源地址比对，别的电脑拿去用不了。</li>
 * <li>{@code time}：签名时刻，Unix 秒（UTC）。{@code site}、{@code nonce}、{@code key}、{@code signature} 同位置证明。</li>
 * </ul>
 * 签名密钥与定位模块的分开（各签各的），公钥公开在 {@value OperatorProofs#KEY_URL}。
 */
public final class OperatorProof {
    public static final String HEADER = "youran-operator-proof v1";
    /** 证明全文最长多少字节（正常不到 450） */
    public static final int MAX_LENGTH = 1024;
    /** 玩家名：不含控制字符，1–32 个字 */
    public static final Pattern NAME = Pattern.compile("[^\\x00-\\x1f\\x7f]{1,32}");
    private static final String[] KEYS = {"uuid", "name", "address", "time", "site", "nonce", "key"};

    public final UUID player;
    public final String name;
    public final Ipv6Address address;
    /** 签名时刻（Unix 秒） */
    public final long time;
    public final String site;
    public final String nonce;
    public final String keyId;
    private final byte[] signature;

    public OperatorProof(UUID player, String name, Ipv6Address address, long time, String site, String nonce, String keyId) {
        this(player, name, address, time, site, nonce, keyId, null);
    }

    private OperatorProof(UUID player, String name, Ipv6Address address, long time, String site, String nonce, String keyId, byte[] signature) {
        if (player == null || name == null || !NAME.matcher(name).matches() || address == null || site == null || !LocationProof.SITE.matcher(site).matches()
                || nonce == null || !LocationProof.NONCE.matcher(nonce).matches() || keyId == null || !keyId.matches("[0-9a-f]{16}")) {
            throw new IllegalArgumentException("操作者证明的字段不对");
        }
        this.player = player;
        this.name = name;
        this.address = address;
        this.time = time;
        this.site = site;
        this.nonce = nonce;
        this.keyId = keyId;
        this.signature = signature == null ? null : signature.clone();
    }

    /** 被签的那部分（含每行末尾的 LF） */
    public String signedText() {
        return HEADER + "\n"
                + "uuid=" + player.toString().toLowerCase(Locale.ROOT) + "\n"
                + "name=" + name + "\n"
                + "address=" + address + "\n"
                + "time=" + time + "\n"
                + "site=" + site + "\n"
                + "nonce=" + nonce + "\n"
                + "key=" + keyId + "\n";
    }

    /** 用私钥签名，返回带签名的证明 */
    public OperatorProof sign(PrivateKey key) throws GeneralSecurityException {
        Signature s = Signature.getInstance(LocationProof.ALGORITHM);
        s.initSign(key);
        s.update(signedText().getBytes(StandardCharsets.UTF_8));
        return new OperatorProof(player, name, address, time, site, nonce, keyId, s.sign());
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

    /** 解析证明全文（不核对签名）。CRLF 当 LF；去掉首尾空白。格式不对抛 {@link IllegalArgumentException}（消息是原因） */
    public static OperatorProof parse(String text) {
        if (text == null) {
            throw new IllegalArgumentException("没有证明");
        }
        if (text.getBytes(StandardCharsets.UTF_8).length > MAX_LENGTH) {
            throw new IllegalArgumentException("证明太长");
        }
        String[] lines = text.replace("\r\n", "\n").trim().split("\n", -1);
        if (lines.length != KEYS.length + 2 || !lines[0].equals(HEADER)) {
            throw new IllegalArgumentException("不是操作者证明（第一行应为“" + HEADER + "”，共 " + (KEYS.length + 2) + " 行）");
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
        OperatorProof p;
        try {
            p = new OperatorProof(UUID.fromString(v[0]), v[1], Ipv6Address.parse(v[2]), Long.parseLong(v[3]), v[4], v[5], v[6], sig);
        } catch (RuntimeException e) {
            throw new IllegalArgumentException("字段不对（UUID、名字、地址、数字、域名、随机数或公钥编号）");
        }
        // 字段要是规范写法（签的是规范写法）
        StringBuilder given = new StringBuilder();
        for (int i = 0; i <= KEYS.length; i++) {
            given.append(lines[i]).append('\n');
        }
        if (!p.signedText().equals(given.toString())) {
            throw new IllegalArgumentException("字段不是规范写法");
        }
        return p;
    }

    /** 用公钥核对签名（只核签名，不核网站、地址、时间、随机数；那些用 {@link OperatorVerifier}） */
    public boolean signatureValid(PublicKey key) {
        if (signature == null || key == null || !keyId.equals(LocationProof.keyId(key))) {
            return false;
        }
        try {
            Signature s = Signature.getInstance(LocationProof.ALGORITHM);
            s.initVerify(key);
            s.update(signedText().getBytes(StandardCharsets.UTF_8));
            return s.verify(signature);
        } catch (GeneralSecurityException | RuntimeException e) {
            return false;
        }
    }
}
