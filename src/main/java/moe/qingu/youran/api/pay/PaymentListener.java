package moe.qingu.youran.api.pay;

/**
 * 收款方（多是模组的虚拟账户）收到付款、退了款时的回调，用 {@link PayApi#addListener} 按收款账户登记。在服务端主线程上调用；不要在里面做慢事。
 * <p>
 * <b>至少送到一次</b>：pay.yr 在账本里记着每笔付款、每次退款“已经通知过收款方”，回调正常返回以后才记。所以：
 * <ul>
 * <li><b>同一笔付款的回调可能来不止一次</b>（例如回调返回以后、pay.yr 记下“已通知”以前服务器崩了，开服后会再来一次）。
 * 收款方要<b>按付款编号（{@link PaymentInfo#id}）去重</b>，退款按退款编号去重，同一笔只处理一次。</li>
 * <li>没确认通知过的付款会补发：开服以后（第一次 tick，各模组都已开服），以及有监听器登记到这个收款账户时（登记以前付的也补）。</li>
 * <li>回调抛异常表示没处理好：不记“已通知”，过一阵再试（1 分钟起，越来越久，最长每小时一次），直到正常返回。
 * 东西没存下来（写盘失败、自己还没准备好）时就该抛异常，别吞掉。</li>
 * <li>“已通知”是按付款记的：同一个收款账户登记了几个监听器时，都正常返回才记；其中一个抛异常，下次重试时每个都会再收到一次。</li>
 * <li>收款方没有登记任何监听器时不算通知过，等登记了再补。</li>
 * </ul>
 */
public interface PaymentListener {
    /** 收款单付了（钱已经记进收款方账户）。同一笔付款可能来不止一次，按 {@code payment.id} 去重 */
    void onPaid(OrderInfo order, PaymentInfo payment);

    /**
     * 这笔付款退了一部分或全部：{@code refund} 是这一次退的，{@code refundId} 是这一次退款的编号（{@code ref_…}）。
     * 同一次退款可能来不止一次，按 {@code refundId} 去重。一定在这笔付款的 {@link #onPaid} 正常返回以后才来
     */
    default void onRefunded(PaymentInfo payment, String refundId, Amount refund) {
    }
}
