package moe.qingu.youran.api.pay;

import java.util.UUID;

/** 收款单的状态（查的那一刻的快照，不可变） */
public final class OrderInfo {
    public enum Status {
        /** 还没付，可以付 */
        OPEN("open"),
        /** 有人正在付（扣物品的那一下，很快结束） */
        PAYING("paying"),
        /** 付了 */
        PAID("paid"),
        /** 收款方取消了 */
        CANCELED("canceled"),
        /** 过期了，不能再付 */
        EXPIRED("expired");

        public final String code;

        Status(String code) {
            this.code = code;
        }
    }

    public final String id;
    public final Status status;
    public final AccountRef receiver;
    /** 收款方的名字（玩家角色名；虚拟账户是它的名字） */
    public final String receiverName;
    public final String site;
    public final Amount price;
    public final Currency.Unit unit;
    public final String description;
    public final String reference;
    public final String returnUrl;
    public final String cancelUrl;
    public final long createdAt;
    public final long expiresAt;
    /** 付了以后才有 */
    public final long paidAt;
    /** 付款的玩家；没付、或是虚拟账户付的（{@link VirtualAccount#pay}）为 null，见 {@link #payerAccount} */
    public final UUID payer;
    /** 付款人的名字：玩家角色名；虚拟账户付的是它的显示名（如“清”） */
    public final String payerName;
    /** 付款的账户（玩家账户或虚拟账户）；没付为 null */
    public final AccountRef payerAccount;
    public final String paymentId;
    /** 已经退了多少（没付、没退为零） */
    public final Amount refunded;
    /** 付款页网址（把浏览器跳到这里） */
    public final String checkoutUrl;
    /** 只许这个玩家付（{@link OrderSpec#payer}）；谁付都行时为 null */
    public final UUID requiredPayer;

    /** {@code payerAccount}：玩家付的写 {@code AccountRef.player(玩家)}（{@link #payer} 就是那个玩家），虚拟账户付的写那个虚拟账户（{@link #payer} 为 null）；没付为 null */
    public OrderInfo(String id, Status status, AccountRef receiver, String receiverName, String site, Amount price, Currency.Unit unit,
                     String description, String reference, String returnUrl, String cancelUrl, long createdAt, long expiresAt,
                     long paidAt, AccountRef payerAccount, String payerName, String paymentId, Amount refunded, String checkoutUrl,
                     UUID requiredPayer) {
        this.id = id;
        this.status = status;
        this.receiver = receiver;
        this.receiverName = receiverName;
        this.site = site;
        this.price = price;
        this.unit = unit;
        this.description = description;
        this.reference = reference;
        this.returnUrl = returnUrl;
        this.cancelUrl = cancelUrl;
        this.createdAt = createdAt;
        this.expiresAt = expiresAt;
        this.paidAt = paidAt;
        this.payerAccount = payerAccount;
        this.payer = payerAccount == null ? null : payerAccount.player;
        this.payerName = payerName;
        this.paymentId = paymentId;
        this.refunded = refunded;
        this.checkoutUrl = checkoutUrl;
        this.requiredPayer = requiredPayer;
    }

    public boolean isPaid() {
        return status == Status.PAID;
    }
}
