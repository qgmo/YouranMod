package moe.qingu.youran.api.pay;

import net.minecraft.entity.player.EntityPlayerMP;
import net.minecraft.item.ItemStack;

import java.util.List;

/**
 * 模组创建的虚拟账户（服主 11-15：如 YouranSCP 给 dado 建的、家具模组 MineBay 收款用的）。收款单的收款方可以是它，
 * 付款记进来；模组按需查看余额、取出或作废里面的东西，也可以往里充值（{@link #deposit}）。用 {@link PayApi#virtualAccount} 拿（没有时创建）。
 * <p>
 * 查看的方法任意线程都可以调用；取出、作废、发给玩家、付款、充值要在服务端主线程上调用。
 * <p>
 * 拿到这个对象就能动这个账户（服主 11-18：对模组来说走 Java 调用就相当于验证过了），pay.yr 不再核对是哪个模组在调；
 * 别把它交给不该动这个账户的代码。
 */
public interface VirtualAccount {
    AccountRef ref();

    /** 给人看的名字（{@link PayApi#setDisplayName}）；没起过时是登记名（“模组 ID:名字”） */
    String displayName();

    /** 现在的余额 */
    Amount balance();

    /** 最近的收支记录，新的在前，最多 {@code max} 条（服务端只留每个账户最近的 200 条） */
    List<PayRecord> records(int max);

    /**
     * 取出：从账户扣掉 {@code amount}，返回对应的物品（钱按 {@code form} 给：绿宝石，或悠然元按面额从大到小；不足一个绿宝石的零头给元）。
     * 扣账先记下来再返回物品，<b>物品交给你以后就是你的了</b>（放进箱子、发给谁），没存好就丢了，pay.yr 不再管。余额不够时抛异常、什么都不扣。
     */
    List<ItemStack> take(Amount amount, Currency.Unit form, String note) throws PayException;

    /** 作废：从账户扣掉 {@code amount}，东西没了（例如回收货币）。余额不够时抛异常、什么都不扣 */
    void discard(Amount amount, String note) throws PayException;

    /**
     * 发给在线玩家：从账户扣掉 {@code amount}，放进他的背包，放不下的掉在脚边（同 pay.yr 的提现，扛得住崩溃）。
     * 余额不够、玩家不在线时抛异常、什么都不扣。
     */
    void giveTo(EntityPlayerMP player, Amount amount, Currency.Unit form, String note) throws PayException;

    /**
     * 充值（服主 11-18）：直接往账户余额里加 {@code amount}，记一条“充值”收支记录（{@link PayRecord.Kind#DEPOSIT}）。
     * Java 调用不校验东西从哪来：调用的模组自己先把东西收走（例如从它保管的背包里拿走），来源由它负责。不发收款回调。
     * <p>
     * 收什么照付款的规则：绿宝石、悠然元按价值折成元记（1 绿宝石 = 2 元），其他物品按 {@link PayItem} 分开记；
     * 黑名单里的物品、装着东西的容器、现在没有的物品拒（{@code item_not_allowed} / {@code invalid_item}），什么都不记。
     * <p>
     * {@code requestId}（{@link #REQUEST_ID}）不为 null 时同一个编号只入账一次：再调返回第一次的那笔（{@link DepositInfo#replayed}），
     * 金额不一样时拒（{@code request_id_reused}）。编号记在账本里，重开服、写快照以后都认得（保留天数同付款记录，{@code pay.keepDays}）。
     * 入账是账本里的一条记录，写盘以后才算数，没有充了一半的时候：调用返回或抛异常以后，用 {@link #deposited} 查到的就是实情。
     * 服务端主线程上调用。
     *
     * @return 这笔充值
     */
    DepositInfo deposit(Amount amount, String requestId, String note) throws PayException;

    /** 用请求编号 {@code requestId} 充过的那笔；没有为 null（崩溃恢复时用它判断上次充没充进去）。任意线程 */
    DepositInfo deposited(String requestId);

    /** 请求编号的写法（{@link #pay}、{@link #deposit}） */
    java.util.regex.Pattern REQUEST_ID = java.util.regex.Pattern.compile("[A-Za-z0-9_.:\\-]{1,100}");

    /**
     * 用这个账户的余额付一张收款单：{@link PayApi#pay} 的简写（同一个入口、同样的规则、崩溃安全与请求编号），付款账户是这个账户。
     *
     * @param orderId   收款单号（{@code ord_…}）
     * @param expected  预期金额；不为 null 时要和收款单的价格一样，不一样就拒（{@code amount_mismatch}），什么都不扣
     * @param requestId 请求编号（{@link #REQUEST_ID}）；不为 null 时同一个编号再调只付一次，返回第一次的那笔付款
     * @return 这笔付款
     */
    default PaymentInfo pay(String orderId, Amount expected, String requestId) throws PayException {
        return PayApi.pay(new PaySpec(orderId, ref()).expect(expected).requestId(requestId));
    }
}
