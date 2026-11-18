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
 * 同时在主线程上调用收款方登记的 {@link PaymentListener}（至少一次，可能重复，收款方按付款编号去重）；网站也可以用 {@link #order} 查是不是付了。
 * <p>
 * 只在服务端、游戏内网络与 pay.yr 运行时有用（{@link #isAvailable}）；客户端、YouranMod 客户端包里返回 null / 抛 {@link PayException}（{@code unavailable}）。
 * 收款回调可以在模组加载阶段就登记。
 * <p>
 * <b>付款分两层</b>（服主 11-18）：
 * <ol>
 * <li><b>付款入口</b> {@link #pay}：用某个账户付一张收款单，是唯一动钱付款的地方。在 Java 里调用就是授权，不再弹确认框。
 * 检查（单子开着、没过期、没付过、不能付给自己……）、扣款、记账、崩溃安全、请求编号都在这里。玩家在付款页上确认以后，YouranMod 也是调它付的；
 * 模组用自己虚拟账户的余额付别人的收款单同样调它（{@link VirtualAccount#pay} 是它的简写）。</li>
 * <li><b>付款请求</b> {@link PaymentRequest}：付款页上有人要用某个账户付时，pay.yr 不自己决定，按账户类型交出去：玩家账户由 YouranMod 处理
 * （id.yr 弹确认框给那名玩家，同意了调 {@link #pay}，拒绝或超时什么都不做）；虚拟账户交给它所属模组登记的 {@link PaymentRequestHandler}（可选）。
 * 请求只是通知：没有处理者就什么都不发生，单子照旧开着，以后谁主动调 {@link #pay} 都能付。</li>
 * </ol>
 * 付钱和收货是两回事：pay.yr 只管付钱，收货人由网站自己定（例如 dado 结账时填的名字记在单号里）。
 */
public final class PayApi {
    /** 付款服务的域名 */
    public static final String DOMAIN = "pay.yr";
    /** 开发者文档站（接入流程、接口、错误码、示例） */
    public static final String DOCS_DOMAIN = "docs.pay.yr";

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

    /**
     * 付款入口（服主 11-18）：用 {@code spec.payer} 付收款单 {@code spec.orderId}，所有付款都走这里（玩家在付款页上确认以后也是）。
     * 调用就是授权，不弹确认框。服务端主线程上调用。
     * <ul>
     * <li>规则：收款单要开着、没过期、没付过、没取消，不能付给自己；只许某个玩家付的单只有那个玩家能付；{@link PaySpec#expect} 不为 null 时价格要一样。</li>
     * <li>虚拟账户：整个价格从账户余额里出（钱按元算，绿宝石、悠然元标价的都一样；别的物品要余额里有），不够时抛 {@code insufficient_balance}。
     * 扣款、记进收款方、收款单改成已付是账本里的一条记录，写盘以后才算数，没有付了一半的时候。</li>
     * <li>玩家账户：玩家要在线；照 {@link PaySpec#prefer}、{@link PaySpec#balanceYuan} 从背包拿、用余额，找零放进背包。动背包要核对存档：
     * 偶尔核对不上时抛 {@code processing}（东西已经扣了，pay.yr 每秒重试，关服后下次开服照存档补上或作废），之后用 {@link #order} 查结果。</li>
     * <li>{@link PaySpec#requestId}：同一个付款账户、同一个编号再付只付一次，返回第一次的那笔（不再回调）。</li>
     * </ul>
     * 付成以后收款方照常收到 {@link PaymentListener#onPaid}；付款记录里的付款人是这个账户（{@link PaymentInfo#payerAccount}，名字是玩家名或虚拟账户的显示名）。
     * 这笔款退款时退回这个账户。
     */
    public static PaymentInfo pay(PaySpec spec) throws PayException {
        return need().pay(spec);
    }

    /**
     * 登记处理付款请求的一方（{@link PaymentRequestHandler}）：虚拟账户 {@code modId:…} 的请求交给它；{@code handler} 为 null 时去掉。
     * 一个模组 ID 一个（后登记的替换先登记的）。可选，加载阶段就能登记。
     */
    public static void setRequestHandler(String modId, PaymentRequestHandler handler) {
        PayHooks.setRequestHandler(modId, handler);
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

    /** 虚拟账户（没有时创建），同时起显示名（同 {@link #setDisplayName}） */
    public static VirtualAccount virtualAccount(String key, String displayName) throws PayException {
        try {
            setDisplayName(key, displayName);
        } catch (IllegalArgumentException e) {
            throw new PayException("invalid_account", e.getMessage());
        }
        return need().virtual(key);
    }

    /** 显示名最长几个字 */
    public static final int MAX_DISPLAY_NAME = 24;

    /**
     * 给虚拟账户起一个给人看的名字（如 {@code cfm:minebay} → “MineBay”）：付款页、系统确认框、付款人的收支记录里显示它，不显示登记名。
     * 模组加载阶段就能调用（pay.yr 开服时记进账本），之后任何时候、任意线程调用也行；改名后新的记录用新名字，已经记下的不改。
     * 名字 1–{@value #MAX_DISPLAY_NAME} 个字，不能有控制字符、格式代码（§）；不对时抛 {@link IllegalArgumentException}。
     */
    public static void setDisplayName(String key, String displayName) {
        AccountRef.virtual(key);
        PayHooks.setDisplayName(key, checkDisplayName(displayName));
    }

    /** 检查显示名，返回去掉首尾空白的写法；不合格时抛 {@link IllegalArgumentException} */
    public static String checkDisplayName(String name) {
        String n = name == null ? "" : name.trim();
        if (n.isEmpty() || n.codePointCount(0, n.length()) > MAX_DISPLAY_NAME) {
            throw new IllegalArgumentException("显示名要 1–" + MAX_DISPLAY_NAME + " 个字：" + name);
        }
        for (int i = 0; i < n.length(); ) {
            int c = n.codePointAt(i);
            if (Character.isISOControl(c) || c == '\u00a7' || c == 0xFFFD || c == 0xFFFE || c == 0xFFFF
                    || Character.getType(c) == Character.FORMAT || Character.getType(c) == Character.SURROGATE
                    || Character.getType(c) == Character.LINE_SEPARATOR || Character.getType(c) == Character.PARAGRAPH_SEPARATOR) {
                throw new IllegalArgumentException("显示名里不能有控制字符、格式代码：" + name);
            }
            i += Character.charCount(c);
        }
        return n;
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

    /**
     * 收款回调：收款方 {@code receiver} 收到付款、退款时调用（主线程）。加载阶段就能登记，开服以后登记也行。
     * <p>
     * <b>回调可能对同一笔付款来不止一次，收款方要按付款编号（{@link PaymentInfo#id}）去重</b>：pay.yr 保证至少送到一次——
     * 回调正常返回以后才在账本里记“已通知”，没记上的在开服后、以及有监听器登记到这个账户时补发（登记以前付的也补），回调抛异常的过一阵重试。
     * 详见 {@link PaymentListener}。
     */
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
