package moe.qingu.youran.api.pay;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;

/**
 * 付款请求（服主 11-18）：pay.yr 的付款页上有人要用某个账户付一张收款单。pay.yr 不自己决定付不付，按账户交给它的处理者
 * （{@link PaymentRequestHandler}）：玩家账户由 YouranMod 自己处理（id.yr 弹确认框给那名玩家），虚拟账户由它所属的模组处理。
 * 处理者同意了就调 {@link PayApi#pay}，那一下才动钱；没有处理者时什么都不发生，收款单照旧开着。不可变。
 */
public final class PaymentRequest {
    /** 要付的收款单（发请求那一刻的样子） */
    public final OrderInfo order;
    /** 要用哪个账户付 */
    public final AccountRef account;
    /** 这个账户给人看的名字（玩家名、虚拟账户的显示名） */
    public final String accountName;
    /** 付款页上登录的玩家（发请求的人） */
    public final UUID requestedBy;
    public final String requestedByName;
    /** 付款页表单里的选项（如 {@code prefer}、{@code balance}），原样给处理者 */
    public final Map<String, String> options;

    public PaymentRequest(OrderInfo order, AccountRef account, String accountName, UUID requestedBy, String requestedByName,
                          Map<String, String> options) {
        this.order = order;
        this.account = account;
        this.accountName = accountName;
        this.requestedBy = requestedBy;
        this.requestedByName = requestedByName;
        this.options = options == null ? Collections.<String, String>emptyMap() : Collections.unmodifiableMap(new LinkedHashMap<>(options));
    }
}
