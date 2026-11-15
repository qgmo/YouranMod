package moe.qingu.youran.api.pay;

import java.util.UUID;

/** 一笔付款（收款单付了以后有，不可变的快照） */
public final class PaymentInfo {
    public final String id;
    public final String orderId;
    public final UUID payer;
    public final String payerName;
    public final AccountRef receiver;
    public final String site;
    public final String reference;
    /** 付了多少（= 收款单的价格） */
    public final Amount amount;
    /** 已经退了多少 */
    public final Amount refunded;
    public final long time;

    public PaymentInfo(String id, String orderId, UUID payer, String payerName, AccountRef receiver, String site, String reference,
                       Amount amount, Amount refunded, long time) {
        this.id = id;
        this.orderId = orderId;
        this.payer = payer;
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
