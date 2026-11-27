package moe.qingu.youran.api.network.identity;

/** 请操作者证明的结果（{@link OperatorProofs#sign}） */
public final class OperatorResult {
    public enum Status {
        /** 签好了，{@link #proof} 是证明全文 */
        OK("ok"),
        /** 玩家拒绝了 */
        DENIED("denied"),
        /** 玩家没回应 */
        TIMEOUT("timeout"),
        /** 还没回应时玩家下线了 */
        OPERATOR_CHANGED("operator_changed"),
        /** 这台电脑没装身份模块（电脑模组判断，不签时用这个代号告诉网站） */
        NO_IDENTITY("no_identity"),
        /** 没有操作者（玩家不在线、电脑现在没人操作） */
        NO_OPERATOR("no_operator"),
        /** 玩家已经有一个确认框开着，或请得太快 */
        BUSY("busy"),
        /** 网站域名或随机数的格式不对 */
        BAD_REQUEST("bad_request"),
        /** 网络没在运行、网卡没插着、没有签名密钥、服务器正在关 */
        UNAVAILABLE("unavailable");

        /** 写进表单字段、给网站看的代号 */
        public final String code;

        Status(String code) {
            this.code = code;
        }

        public static Status byCode(String code) {
            for (Status s : values()) {
                if (s.code.equals(code)) {
                    return s;
                }
            }
            return UNAVAILABLE;
        }
    }

    public final Status status;
    /** {@link Status#OK} 时是证明全文（{@link OperatorProof#text()}），否则为 null */
    public final String proof;

    public OperatorResult(Status status, String proof) {
        this.status = status;
        this.proof = status == Status.OK ? proof : null;
    }

    public static OperatorResult of(Status status) {
        return new OperatorResult(status, null);
    }

    public boolean ok() {
        return status == Status.OK && proof != null;
    }

    @Override
    public String toString() {
        return status.code;
    }
}
