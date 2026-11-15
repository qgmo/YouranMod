package moe.qingu.youran.api.pay;

/** 收款方（多是模组的虚拟账户）收到付款、退了款时的回调。在服务端主线程上调用；不要在里面做慢事 */
public interface PaymentListener {
    /** 收款单付了（钱已经记进收款方账户） */
    void onPaid(OrderInfo order, PaymentInfo payment);

    /** 这笔付款退了一部分或全部（{@code refund} 是这一次退的） */
    default void onRefunded(PaymentInfo payment, Amount refund) {
    }
}
