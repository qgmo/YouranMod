package moe.qingu.youran.api.pay;

import java.util.UUID;

/** 一笔付款（收款单付了以后有，不可变的快照） */
public final class PaymentInfo {
    public final String id;
    public final String orderId;
    /** 付款的玩家；虚拟账户付的（{@link VirtualAccount#pay}）为 null，见 {@link #payerAccount} */
    public final UUID payer;
    /** 付款人的名字：玩家角色名；虚拟账户付的是它的显示名（如“清”） */
    public final String payerName;
    /** 付款的账户：玩家付的是他的玩家账户，虚拟账户付的是那个虚拟账户。退款退回这个账户 */
    public final AccountRef payerAccount;
    public final AccountRef receiver;
    public final String site;
    public final String reference;
    /** 付了多少（= 收款单的价格） */
    public final Amount amount;
    /** 已经退了多少 */
    public final Amount refunded;
    public final long time;

    /** {@code payerAccount}：玩家付的写 {@code AccountRef.player(玩家)}（{@link #payer} 就是那个玩家），虚拟账户付的写那个虚拟账户（{@link #payer} 为 null） */
    public PaymentInfo(String id, String orderId, AccountRef payerAccount, String payerName, AccountRef receiver, String site,
                       String reference, Amount amount, Amount refunded, long time) {
        this.id = id;
        this.orderId = orderId;
        this.payerAccount = payerAccount;
        this.payer = payerAccount == null ? null : payerAccount.player;
        this.payerName = payerName;
        this.receiver = receiver;
        this.site = site;
        this.reference = reference;
        this.amount = amount;
        this.refunded = refunded;
        this.time = time;
    }

    /** 还能退多少 */
    public Amount refundable() {
        return amount.minus(refunded);
    }
}
