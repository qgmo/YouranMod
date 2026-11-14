package moe.qingu.youran.api.network.stack;

import moe.qingu.youran.api.network.Ipv6Address;

/** 一次 ping 的结果 */
public final class PingResult {
    public enum Status {
        /** 收到回显应答 */
        REPLY,
        /** 收到目标不可达（{@link #code} 是 ICMPv6 代码） */
        UNREACHABLE,
        /** 收到超时（跳数用完） */
        TIME_EXCEEDED,
        /** 等到时限也没有回音 */
        TIMEOUT
    }

    public final Status status;
    /** 回应来自哪里（超时时为 null） */
    public final Ipv6Address from;
    public final int code;
    /** 往返毫秒 */
    public final long rttMillis;
    public final int bytes;

    public PingResult(Status status, Ipv6Address from, int code, long rttMillis, int bytes) {
        this.status = status;
        this.from = from;
        this.code = code;
        this.rttMillis = rttMillis;
        this.bytes = bytes;
    }

    @Override
    public String toString() {
        return status + (from != null ? " from " + from : "") + " code=" + code + " rtt=" + rttMillis + "ms";
    }
}
