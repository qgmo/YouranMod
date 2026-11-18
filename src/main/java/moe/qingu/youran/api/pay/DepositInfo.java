package moe.qingu.youran.api.pay;

/** 一笔充值（{@link VirtualAccount#deposit}，不可变） */
public final class DepositInfo {
    /** 充值编号（{@code dep_…}），也是收支记录里的 {@link PayRecord#ref} */
    public final String id;
    public final AccountRef account;
    /** 记进账户的（钱已经按元折算，物品按 pay.yr 的写法认过） */
    public final Amount amount;
    /** 请求编号；没带为 null */
    public final String requestId;
    public final String note;
    public final long time;
    /** 同一个请求编号再来一次：没有再充，这是第一次的那笔 */
    public final boolean replayed;

    public DepositInfo(String id, AccountRef account, Amount amount, String requestId, String note, long time, boolean replayed) {
        this.id = id;
        this.account = account;
        this.amount = amount;
        this.requestId = requestId;
        this.note = note;
        this.time = time;
        this.replayed = replayed;
    }
}
