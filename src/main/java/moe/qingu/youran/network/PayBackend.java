package moe.qingu.youran.network;

import moe.qingu.youran.api.pay.AccountRef;
import moe.qingu.youran.api.pay.Amount;
import moe.qingu.youran.api.pay.OrderInfo;
import moe.qingu.youran.api.pay.OrderSpec;
import moe.qingu.youran.api.pay.PayException;
import moe.qingu.youran.api.pay.PayRecord;
import moe.qingu.youran.api.pay.PaymentInfo;
import moe.qingu.youran.api.pay.VirtualAccount;

import java.util.List;

/** pay.yr 的服务端实现（在 {@code server.net.pay}，客户端包里没有）。运行时登记到 {@link PayHooks} */
public interface PayBackend {
    OrderInfo createOrder(OrderSpec spec) throws PayException;

    OrderInfo order(String id);

    /** 付款入口（{@code PayApi.pay}） */
    PaymentInfo pay(moe.qingu.youran.api.pay.PaySpec spec) throws PayException;

    boolean cancelOrder(String id, AccountRef receiver);

    PaymentInfo payment(String id);

    PaymentInfo refund(String paymentId, Amount amount, String note) throws PayException;

    Amount balance(AccountRef account);

    List<PayRecord> records(AccountRef account, int max);

    VirtualAccount virtual(String key) throws PayException;

    /** 把虚拟账户的显示名记进账本（{@link PayHooks#setDisplayName} 调用；账户没有时先建）。写不进去只记日志 */
    void applyDisplayName(String key, String name);

    String checkoutUrl(String orderId);

    /** 有收款回调登记到 {@code account} 了（{@link PayHooks#addListener} 调用，任意线程）：把这个账户还没确认通知过的付款补发一遍 */
    void listenerAdded(AccountRef account);
}
