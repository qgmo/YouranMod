package moe.qingu.youran.api.network.ca;

/**
 * 一个名字的认证要求：申请者按其中一种方式证明自己管着这个名字（两种都给出，通知已就绪时选一种）。
 * <ul>
 * <li>HTTP 认证：在 {@link #httpUrl}（{@code http://<名字>/.well-known/acme-challenge/<令牌>}，80 口、不加密）放
 * {@link #httpContent}，证书服务从 {@code ca.yr} 那台主机经递归解析器查名字、GET 这个网址，回 200 且内容（去掉首尾空白）一样就算通过；</li>
 * <li>DNS 认证：给 {@link #dnsName}（{@code _acme-challenge.<名字>}）加一条 TXT 记录，值为 {@link #dnsValue}，
 * 证书服务经递归解析器查这个名字的 TXT，有一条一样就算通过。</li>
 * </ul>
 * 令牌与内容每张订单都不一样；内容里带着证书请求里公钥的指纹（认证绑在这把密钥上），换了密钥要重新申请。
 */
public final class Challenge {
    /** 认证方式 */
    public enum Method {
        HTTP("http"),
        DNS("dns");

        /** HTTP 接口里的写法 */
        public final String id;

        Method(String id) {
            this.id = id;
        }

        /** 按 HTTP 接口里的写法找；不认识为 null */
        public static Method of(String id) {
            for (Method m : values()) {
                if (m.id.equals(id)) {
                    return m;
                }
            }
            return null;
        }
    }

    /** HTTP 认证网址的路径前缀 */
    public static final String HTTP_PATH = "/.well-known/acme-challenge/";
    /** DNS 认证记录名的前缀 */
    public static final String DNS_PREFIX = "_acme-challenge.";

    /** 要证明的名字（A-label） */
    public final String name;
    /** HTTP 认证的令牌（网址最后一段，base64url） */
    public final String token;
    /** HTTP 认证的网址 */
    public final String httpUrl;
    /** HTTP 认证时网页要回的内容（纯文本） */
    public final String httpContent;
    /** DNS 认证的记录名 */
    public final String dnsName;
    /** DNS 认证的 TXT 值 */
    public final String dnsValue;

    public Challenge(String name, String token, String httpContent, String dnsValue) {
        this.name = name;
        this.token = token;
        this.httpUrl = "http://" + name + HTTP_PATH + token;
        this.httpContent = httpContent;
        this.dnsName = DNS_PREFIX + name;
        this.dnsValue = dnsValue;
    }
}
