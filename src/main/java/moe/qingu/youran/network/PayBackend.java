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

    boolean cancelOrder(String id, AccountRef receiver);

    PaymentInfo payment(String id);

    PaymentInfo refund(String paymentId, Amount amount, String note) throws PayException;

    Amount balance(AccountRef account);

    List<PayRecord> records(AccountRef account, int max);

    VirtualAccount virtual(String key) throws PayException;

    String checkoutUrl(String orderId);
}
