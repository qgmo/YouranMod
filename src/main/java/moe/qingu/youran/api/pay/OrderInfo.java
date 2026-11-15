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
    public final UUID payer;
    public final String payerName;
    public final String paymentId;
    /** 已经退了多少（没付、没退为零） */
    public final Amount refunded;
    /** 付款页网址（把浏览器跳到这里） */
    public final String checkoutUrl;

    public OrderInfo(String id, Status status, AccountRef receiver, String receiverName, String site, Amount price, Currency.Unit unit,
                     String description, String reference, String returnUrl, String cancelUrl, long createdAt, long expiresAt,
                     long paidAt, UUID payer, String payerName, String paymentId, Amount refunded, String checkoutUrl) {
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
        this.payer = payer;
        this.payerName = payerName;
        this.paymentId = paymentId;
        this.refunded = refunded;
        this.checkoutUrl = checkoutUrl;
    }

    public boolean isPaid() {
        return status == Status.PAID;
    }
}
