package moe.qingu.youran.api.pay;

import net.minecraft.entity.player.EntityPlayerMP;
import net.minecraft.item.ItemStack;

import java.util.List;

/**
 * 模组创建的虚拟账户（服主 11-15：如 YouranSCP 给 dado 建的、家具模组 MineBay 收款用的）。收款单的收款方可以是它，
 * 付款记进来；模组按需查看余额、取出或作废里面的东西。用 {@link PayApi#virtualAccount} 拿（没有时创建）。
 * <p>
 * 查看的方法任意线程都可以调用；取出、作废、发给玩家要在服务端主线程上调用。
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
}
