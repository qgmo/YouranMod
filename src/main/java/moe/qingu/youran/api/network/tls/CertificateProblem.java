package moe.qingu.youran.api.network.tls;

/** TLS 握手时对方证书没通过检查的原因（浏览器按它给错误页，不提供“继续访问”） */
public enum CertificateProblem {
    /** 对方没给证书 */
    NO_CERTIFICATE("对方没有给出证书"),
    /** 证书链接不到信任的根证书（不是 QinguMoe CA 签的） */
    UNTRUSTED("证书不是由受信任的根证书（QinguMoe CA）签发的"),
    /** 链上有证书已经过期 */
    EXPIRED("证书已过期"),
    /** 链上有证书还没到生效时间 */
    NOT_YET_VALID("证书还没到生效时间"),
    /** 证书上的名字（SAN）里没有要访问的主机名 */
    HOSTNAME_MISMATCH("证书与要访问的主机名不符"),
    /** 名字超出了中间 CA 的域名约束 */
    NAME_NOT_PERMITTED("证书上的名字超出了签发者允许的范围"),
    /** 证书不能用于网站（用途不对） */
    WRONG_USAGE("证书不能用于网站"),
    /** 其他问题（签名不对、链不完整、格式不对等） */
    INVALID("证书链无效");

    /** 给人看的说明（中文；界面上走语言文件） */
    public final String description;

    CertificateProblem(String description) {
        this.description = description;
    }
}
