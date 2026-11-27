package moe.qingu.youran.api.network.identity;

import moe.qingu.youran.api.network.Ipv6Address;

import java.security.PublicKey;
import java.util.function.Predicate;

/**
 * 网站这一侧核对操作者证明的参考实现（Java；id.yr 用它，别的语言照着做）：
 * <ol>
 * <li>解析（{@link OperatorProof#parse}），格式不对就不收；</li>
 * <li>用公钥核对签名（公钥编号要对上）；</li>
 * <li>{@code site} 是自己的域名；</li>
 * <li>{@code address} 等于这次请求的 TCP 来源地址；</li>
 * <li>{@code time} 不早于 {@code maxAgeSeconds} 秒以前、不晚于 30 秒以后（容许时钟差）；</li>
 * <li>{@code nonce} 是自己发出去的、没用过（{@code nonceCheck} 返回 true 并作废它）。</li>
 * </ol>
 * 失败的原因代号：format、signature、site、address、expired、nonce。
 */
public final class OperatorVerifier {
    /** 结果：成功时 {@link #proof} 不为 null；失败时 {@link #reason} 是原因 */
    public static final class Result {
        public final OperatorProof proof;
        public final String reason;

        Result(OperatorProof proof, String reason) {
            this.proof = proof;
            this.reason = reason;
        }

        public boolean ok() {
            return proof != null;
        }

        @Override
        public String toString() {
            return ok() ? "ok" : "fail " + reason;
        }
    }

    private final PublicKey key;
    private final long maxAgeSeconds;

    public OperatorVerifier(PublicKey key, long maxAgeSeconds) {
        this.key = key;
        this.maxAgeSeconds = maxAgeSeconds;
    }

    /**
     * @param site       自己的域名（A-label）；为 null 时不核对
     * @param source     这次请求的来源地址；为 null 时不核对
     * @param nonceCheck 随机数是自己发的、没用过时返回 true（并作废它）；为 null 时不核对。前面几项都通过了才调用
     */
    public Result verify(String text, String site, Ipv6Address source, Predicate<String> nonceCheck, long nowSeconds) {
        OperatorProof p;
        try {
            p = OperatorProof.parse(text);
        } catch (IllegalArgumentException e) {
            return new Result(null, "format");
        }
        if (!p.signatureValid(key)) {
            return new Result(null, "signature");
        }
        if (site != null && !site.equals(p.site)) {
            return new Result(null, "site");
        }
        if (source != null && !source.equals(p.address)) {
            return new Result(null, "address");
        }
        if (p.time < nowSeconds - maxAgeSeconds || p.time > nowSeconds + 30) {
            return new Result(null, "expired");
        }
        if (nonceCheck != null && !nonceCheck.test(p.nonce)) {
            return new Result(null, "nonce");
        }
        return new Result(p, null);
    }
}
