package moe.qingu.youran.api.network.stack;

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
        BAD_RESPONSE
    }

    public final Reason reason;

    public NetworkException(Reason reason, String message) {
        super(message);
        this.reason = reason;
    }
}
