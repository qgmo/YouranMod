package moe.qingu.youran.api.pay;

/** 账户的一条收支记录（不可变） */
public final class PayRecord {
    public enum Kind {
        /** 收到一笔付款（记进账户） */
        RECEIVED,
        /** 付了一笔款（从背包扣，账户余额不变；只是记一笔） */
        PAID,
        /** 付款的找零放不下背包，记进账户 */
        CHANGE,
        /** 收到退款（记进账户） */
        REFUND_IN,
        /** 退款给付款人（从账户扣） */
        REFUND_OUT,
        /** 提现（从账户扣，进背包或掉在脚边） */
        WITHDRAW,
        /** 提现没送到（崩溃恢复时发现），退回账户 */
        WITHDRAW_RETURNED,
        /** 模组取出（虚拟账户） */
        TAKEN,
        /** 模组作废（虚拟账户） */
        DISCARDED,
        /** 用账户余额付了一笔款（虚拟账户付款，{@link VirtualAccount#pay}；从账户扣） */
        SPENT,
        /** 模组充值（虚拟账户，{@link VirtualAccount#deposit}；记进账户） */
        DEPOSIT
    }

    public final long time;
    public final Kind kind;
    /** 多少（总是正的；进出看 {@link #kind}） */
    public final Amount amount;
    /** 对方（付款人、收款方等）的名字；没有为 null */
    public final String counterparty;
    /** 相关的付款编号（{@code pay_…}）、充值编号（{@code dep_…}）等；没有为 null */
    public final String ref;
    public final String note;

    public PayRecord(long time, Kind kind, Amount amount, String counterparty, String ref, String note) {
        this.time = time;
        this.kind = kind;
        this.amount = amount;
        this.counterparty = counterparty;
        this.ref = ref;
        this.note = note;
    }

    /** 账户余额是增加（+1）、减少（-1）还是不变（0） */
    public int sign() {
        switch (kind) {
            case RECEIVED:
            case CHANGE:
            case REFUND_IN:
            case WITHDRAW_RETURNED:
            case DEPOSIT:
                return 1;
            case PAID:
                return 0;
            default:
                return -1;
        }
    }
}
