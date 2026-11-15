package moe.qingu.youran.api.pay;

import moe.qingu.youran.network.PayBackend;
import moe.qingu.youran.network.PayHooks;

import java.util.Collections;
import java.util.List;

/**
 * 聚合付款服务 {@code pay.yr}（服主 11-15，像现实中的 Stripe）给模组的 Java 接口：模组网站在服务端直接创建收款单、
 * 查结果或收到付款回调，收款进模组的虚拟账户。不用走 HTTP（玩家自己搭的网站走 HTTP 接口，见 pay.yr 首页）。
 * <p>
 * 流程：{@link #createOrder} 拿到收款单 → 把玩家的浏览器跳到 {@link OrderInfo#checkoutUrl} → 玩家在 pay.yr 用游戏角色登录、
 * 在系统确认框里确认付款，服务端从他背包扣物品、记进收款方账户 → 浏览器跳回 {@link OrderSpec#returnUrl}，
 * 同时在主线程上调用收款方登记的 {@link PaymentListener}；网站也可以用 {@link #order} 查是不是付了。
 * <p>
 * 只在服务端、游戏内网络与 pay.yr 运行时有用（{@link #isAvailable}）；客户端、YouranMod 客户端包里返回 null / 抛 {@link PayException}（{@code unavailable}）。
 * 收款回调可以在模组加载阶段就登记。
 */
public final class PayApi {
    /** 付款服务的域名 */
    public static final String DOMAIN = "pay.yr";

    private PayApi() {
    }

    public static boolean isAvailable() {
        return PayHooks.backend() != null;
    }

    /** 创建收款单（服务端主线程上调用：要检查物品）。收款方、价格、网址不对时抛异常，代号同 HTTP 接口 */
    public static OrderInfo createOrder(OrderSpec spec) throws PayException {
        return need().createOrder(spec);
    }

    /** 查收款单；没有（或不可用）为 null。任意线程 */
    public static OrderInfo order(String id) {
        PayBackend b = PayHooks.backend();
        return b == null || id == null ? null : b.order(id);
    }

    /** 收款方取消一张还没付的收款单；取消了返回 true */
    public static boolean cancelOrder(String id, AccountRef receiver) {
        PayBackend b = PayHooks.backend();
        return b != null && id != null && b.cancelOrder(id, receiver);
    }

    /** 查一笔付款；没有为 null。任意线程 */
    public static PaymentInfo payment(String id) {
        PayBackend b = PayHooks.backend();
        return b == null || id == null ? null : b.payment(id);
    }

    /**
     * 模组给自己虚拟账户收到的付款退款（全额或部分，按价值）：从虚拟账户扣回，退进付款人在 pay.yr 的账户，由他自己提现。
     * 收款方是玩家的付款只能由那个玩家在 pay.yr 上退（要系统确认框确认）。余额不够、超过还能退的时抛异常。服务端主线程上调用。
     */
    public static PaymentInfo refund(String paymentId, Amount amount, String note) throws PayException {
        return need().refund(paymentId, amount, note);
    }

    /** 虚拟账户（没有时创建）；{@code key} 写成“模组 ID:名字” */
    public static VirtualAccount virtualAccount(String key) throws PayException {
        return need().virtual(key);
    }

    /** 账户余额（玩家账户、虚拟账户都行）；不可用为 null */
    public static Amount balance(AccountRef account) {
        PayBackend b = PayHooks.backend();
        return b == null ? null : b.balance(account);
    }

    public static List<PayRecord> records(AccountRef account, int max) {
        PayBackend b = PayHooks.backend();
        return b == null ? Collections.<PayRecord>emptyList() : b.records(account, max);
    }

    /** 收款回调：收款方 {@code receiver} 收到付款、退款时调用（主线程）。加载阶段就能登记 */
    public static void addListener(AccountRef receiver, PaymentListener listener) {
        PayHooks.addListener(receiver, listener);
    }

    public static void removeListener(AccountRef receiver, PaymentListener listener) {
        PayHooks.removeListener(receiver, listener);
    }

    private static PayBackend need() throws PayException {
        PayBackend b = PayHooks.backend();
        if (b == null) {
            throw new PayException("unavailable", "pay.yr 现在不可用（游戏内网络没开，或这是客户端）");
        }
        return b;
    }
}
