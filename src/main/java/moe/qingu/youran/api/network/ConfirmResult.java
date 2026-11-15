package moe.qingu.youran.api.network;

import java.util.UUID;

/** 系统确认框的结果（{@link SystemConfirm}） */
public final class ConfirmResult {
    public enum Outcome {
        /** 操作者点了“同意” */
        APPROVED,
        /** 操作者点了“拒绝”（或按了 Esc） */
        DENIED,
        /** 到时限还没回应，按拒绝算 */
        TIMEOUT,
        /** 还没回应时操作者离开了电脑、下线、换了人（或设备拆了、断电），作废 */
        OPERATOR_CHANGED,
        /** 没有可以问的人：来源认不出、设备现在没人操作、玩家不在线 */
        NO_OPERATOR,
        /** 这个玩家已经有一个确认框开着（每个玩家同一时间最多一个） */
        BUSY,
        /** 网络没在运行、服务器正在关 */
        UNAVAILABLE
    }

    public final Outcome outcome;
    /** 被问的玩家（操作者）；{@link Outcome#NO_OPERATOR}、{@link Outcome#UNAVAILABLE} 时为 null */
    public final UUID player;
    public final String playerName;

    public ConfirmResult(Outcome outcome, UUID player, String playerName) {
        this.outcome = outcome;
        this.player = player;
        this.playerName = player == null ? null : playerName;
    }

    public boolean approved() {
        return outcome == Outcome.APPROVED;
    }

    @Override
    public String toString() {
        return outcome + (player == null ? "" : "（" + playerName + "）");
    }
}
