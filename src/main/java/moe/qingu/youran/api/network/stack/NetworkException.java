package moe.qingu.youran.api.network.stack;

import moe.qingu.youran.api.network.tls.CertificateProblem;

import java.io.IOException;

/** 网络操作失败（future 以它异常结束）。{@link #reason} 说明是哪一种。 */
public final class NetworkException extends IOException {
    public enum Reason {
        /** 等不到回应 */
        TIMEOUT,
        /** 收到 ICMPv6 目标不可达等差错 */
        UNREACHABLE,
        /** 对方拒绝连接（TCP RST 回 SYN） */
        REFUSED,
        /** 连接被对方重置 */
        RESET,
        /** 协议栈已停或连接已关 */
        CLOSED,
        /** 超过上限（连接数、报文长度等） */
        LIMIT,
        /** 收到的回应格式不对 */
        BAD_RESPONSE,
        /** 域名不存在，或者没有 IPv6 地址（DNS 回答了，但没有可连的地址） */
        NAME_NOT_FOUND,
        /** 解析域名失败（递归解析器没有回应、回答服务器出错等） */
        DNS_FAILED,
        /** TLS 握手时对方的证书没通过检查（具体原因见 {@link #certificateProblem}） */
        CERTIFICATE,
        /** TLS 握手或加密层的其他失败（协议版本、密码套件谈不拢，记录格式不对等） */
        TLS
    }

    public final Reason reason;
    /** {@link Reason#CERTIFICATE} 时是证书的具体问题，其余为 null */
    public final CertificateProblem certificateProblem;

    public NetworkException(Reason reason, String message) {
        this(reason, message, null);
    }

    public NetworkException(Reason reason, String message, CertificateProblem certificateProblem) {
        super(message);
        this.reason = reason;
        this.certificateProblem = certificateProblem;
    }
}
