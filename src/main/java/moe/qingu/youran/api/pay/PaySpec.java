package moe.qingu.youran.api.pay;

/**
 * 一次付款（{@link PayApi#pay}）：用哪个账户付哪张收款单，以及可选的预期金额、请求编号、玩家账户从背包怎么拿。
 * 不可变的部分是单号与付款账户；其余用链式方法设，设完交给 {@link PayApi#pay}。
 */
public final class PaySpec {
    public final String orderId;
    public final AccountRef payer;
    private Amount expected;
    private String requestId;
    private Currency.Unit prefer = Currency.Unit.EMERALD;
    private long balanceYuan;

    public PaySpec(String orderId, AccountRef payer) {
        if (payer == null) {
            throw new IllegalArgumentException("要写用哪个账户付");
        }
        this.orderId = orderId;
        this.payer = payer;
    }

    /** 预期金额：不为 null 时要和收款单的价格一样，不一样就拒（{@code amount_mismatch}），什么都不扣 */
    public PaySpec expect(Amount amount) {
        this.expected = amount;
        return this;
    }

    /**
     * 请求编号（{@link VirtualAccount#REQUEST_ID} 的写法，按付款账户分）：同一个编号再付只付一次，返回第一次的那笔付款（不再回调）；
     * 用在另一张单上抛 {@code request_id_reused}。为 null 时不认重发
     */
    public PaySpec requestId(String id) {
        this.requestId = id;
        return this;
    }

    /** 玩家账户：钱从背包拿时先用悠然元（默认先用绿宝石） */
    public PaySpec preferYuan() {
        this.prefer = Currency.Unit.YUAN;
        return this;
    }

    /** 玩家账户：用 pay.yr 余额付 {@code yuan} 元（其余从背包拿；默认不用余额） */
    public PaySpec useBalance(long yuan) {
        if (yuan < 0) {
            throw new IllegalArgumentException("余额用多少不能为负");
        }
        this.balanceYuan = yuan;
        return this;
    }

    /** 玩家账户：余额能用多少用多少（钱和价格里的物品都算） */
    public PaySpec useAllBalance() {
        this.balanceYuan = -1;
        return this;
    }

    public Amount expected() {
        return expected;
    }

    public String requestId() {
        return requestId;
    }

    public Currency.Unit prefer() {
        return prefer;
    }

    /** 用多少元余额；-1 为能用多少用多少，0 为不用 */
    public long balanceYuan() {
        return balanceYuan;
    }
}
