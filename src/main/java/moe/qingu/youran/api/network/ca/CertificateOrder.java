package moe.qingu.youran.api.network.ca;

import java.security.cert.X509Certificate;
import java.util.Collections;
import java.util.List;

/**
 * 一张证书订单在某一刻的样子（不可变；状态变了要重新取）。订单只在服务器内存里，过了 {@link #expires} 就没了，
 * 关服也没了，到时重新申请。
 * <p>
 * 状态：{@link Status#PENDING 等认证}（刚申请，按 {@link #challenges} 布置好后通知已就绪）→
 * {@link Status#PROCESSING 认证中}（证书服务正在经网络核对）→ {@link Status#VALID 已签发}（{@link #certificate} 里是证书）
 * 或 {@link Status#INVALID 失败}（{@link #error} 是原因；不能再试，重新申请一张）。
 */
public final class CertificateOrder {
    public enum Status {
        PENDING("pending"),
        PROCESSING("processing"),
        VALID("valid"),
        INVALID("invalid");

        /** HTTP 接口里的写法 */
        public final String id;

        Status(String id) {
            this.id = id;
        }
    }

    /** 订单号（随机，知道它就能查、通知就绪、取证书） */
    public final String id;
    public final Status status;
    /** 申请的名字（A-label，按申请时的顺序去重） */
    public final List<String> names;
    /** 每个名字一份认证要求，顺序同 {@link #names} */
    public final List<Challenge> challenges;
    /** 通知已就绪时选的认证方式；还没通知为 null */
    public final Challenge.Method method;
    /** 失败的原因；不是 {@link Status#INVALID} 时为 null */
    public final String error;
    /** 签好的证书链：叶子在前，后面是中间 CA（不含根）；不是 {@link Status#VALID} 时为空表 */
    public final List<X509Certificate> certificate;
    /** 订单什么时候过期（毫秒，UNIX 时间） */
    public final long expires;

    public CertificateOrder(String id, Status status, List<String> names, List<Challenge> challenges, Challenge.Method method, String error,
                            List<X509Certificate> certificate, long expires) {
        this.id = id;
        this.status = status;
        this.names = Collections.unmodifiableList(names);
        this.challenges = Collections.unmodifiableList(challenges);
        this.method = method;
        this.error = error;
        this.certificate = certificate == null ? Collections.<X509Certificate>emptyList() : Collections.unmodifiableList(certificate);
        this.expires = expires;
    }

    /** 已签发或已失败 */
    public boolean isFinished() {
        return status == Status.VALID || status == Status.INVALID;
    }
}
