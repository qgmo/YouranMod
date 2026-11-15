package moe.qingu.youran.api.pay;

import java.util.Map;
import java.util.TreeMap;

/**
 * 创建收款单要的东西（{@link PayApi#createOrder}）。收款方、网站域名、价格必填；其余可选。
 * <pre>
 *   OrderSpec s = new OrderSpec(AccountRef.virtual("cfm:minebay"), "minebay.yr")
 *           .emeralds(20).description("钻石剑 ×1").reference("订单 1234")
 *           .returnUrl("https://minebay.yr/paid");
 * </pre>
 * 价格：钱（绿宝石或元，二选一标价；付的时候玩家用哪种都行）加上任意别的物品（按物品 + 数据值 + NBT 完全一致），至少一样。
 */
public final class OrderSpec {
    public final AccountRef receiver;
    public final String site;
    long yuan;
    Currency.Unit unit = Currency.Unit.EMERALD;
    boolean unitSet;
    final Map<PayItem, Long> items = new TreeMap<>();
    String description;
    String reference;
    String returnUrl;
    String cancelUrl;
    int ttlSeconds;

    /** @param site 网站域名（付款页上写给玩家看的，如 {@code minebay.yr}） */
    public OrderSpec(AccountRef receiver, String site) {
        if (receiver == null || site == null) {
            throw new IllegalArgumentException("收款方、网站域名都要给");
        }
        this.receiver = receiver;
        this.site = site;
    }

    /** 标价：多少绿宝石 */
    public OrderSpec emeralds(long n) {
        return money(n, Currency.Unit.EMERALD);
    }

    /** 标价：多少元 */
    public OrderSpec yuan(long n) {
        return money(n, Currency.Unit.YUAN);
    }

    public OrderSpec money(long n, Currency.Unit u) {
        if (n <= 0) {
            throw new IllegalArgumentException("价格要大于 0");
        }
        if (unitSet && u != unit) {
            throw new IllegalArgumentException("只能标一种价（绿宝石或元）");
        }
        unit = u;
        unitSet = true;
        yuan = Math.addExact(yuan, Currency.toYuan(n, u));
        return this;
    }

    /** 加一样别的物品（绿宝石、悠然元也可以这样加，会合进钱里） */
    public OrderSpec item(PayItem item, long count) {
        if (count <= 0) {
            throw new IllegalArgumentException("数量要大于 0");
        }
        Currency.Denomination d = Currency.Denomination.of(item);
        if (d != null) {
            // 钱合进价格；还没标过价时按这种钱定显示单位
            if (!unitSet) {
                unit = d == Currency.Denomination.EMERALD ? Currency.Unit.EMERALD : Currency.Unit.YUAN;
                unitSet = true;
            }
            yuan = Math.addExact(yuan, Math.multiplyExact(count, d.value));
            return this;
        }
        items.merge(item, count, Math::addExact);
        return this;
    }

    /** 付款页上写的“买的是什么”（最多 60 字） */
    public OrderSpec description(String s) {
        this.description = s;
        return this;
    }

    /** 网站自己的单号，查收款单时原样带回（最多 64 个可见 ASCII 字符） */
    public OrderSpec reference(String s) {
        this.reference = s;
        return this;
    }

    /** 付完跳回哪里（同一个网站）；不给时付完显示 pay.yr 的“付款成功”页 */
    public OrderSpec returnUrl(String url) {
        this.returnUrl = url;
        return this;
    }

    /** 玩家点“不付了”跳回哪里；不给时用 {@link #returnUrl} */
    public OrderSpec cancelUrl(String url) {
        this.cancelUrl = url;
        return this;
    }

    /** 有效期（秒）；不给用配置的默认值 */
    public OrderSpec ttlSeconds(int s) {
        this.ttlSeconds = s;
        return this;
    }

    public Amount price() {
        return Amount.of(yuan, items);
    }

    public Currency.Unit unit() {
        return unit;
    }

    public String description() {
        return description;
    }

    public String reference() {
        return reference;
    }

    public String returnUrl() {
        return returnUrl;
    }

    public String cancelUrl() {
        return cancelUrl;
    }

    public int ttlSeconds() {
        return ttlSeconds;
    }
}
