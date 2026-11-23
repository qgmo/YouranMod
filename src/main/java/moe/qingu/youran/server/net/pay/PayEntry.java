package moe.qingu.youran.server.net.pay;

import moe.qingu.youran.api.pay.AccountRef;
import moe.qingu.youran.api.pay.Amount;
import moe.qingu.youran.api.pay.Currency;
import moe.qingu.youran.api.pay.PayException;
import moe.qingu.youran.api.pay.PaySpec;
import moe.qingu.youran.api.pay.VirtualAccount;

import java.io.IOException;
import java.util.function.Consumer;
import java.util.function.LongSupplier;

/**
 * pay.yr 唯一的付款入口（服主 11-18，{@code PayApi.pay}）：用某个账户付一张收款单。玩家在付款页的确认框里同意以后、模组用虚拟账户的余额付、
 * 以后别的付款方式，都从 {@link #pay} 进；在这里之外没有别的地方会把收款单改成已付。调用就是授权（确认框、处理付款请求在这一层之上）。
 * <ul>
 * <li>先认请求编号：这个付款账户用同一个编号付过就原样返回那一笔（{@link Ledger#replay}），玩家那笔还没结束就回“处理中”。</li>
 * <li>收款单能不能付（开着、不是付给自己、指定的付款人、预期金额）由账本在同一把锁里查（{@code Ledger.checkPayable}），两种账户一样。</li>
 * <li>虚拟账户：不动世界，扣款、记进收款方、收款单改成已付是账本里的一条日志（{@link Ledger#payFromVirtual}），写盘以后才改内存。</li>
 * <li>玩家账户：动背包，照 {@link TxRunner} 的开始 → 改背包 → 存盘核对 → 结束，崩了开服照存档补上或作废。</li>
 * </ul>
 * 付成以后回调收款方（{@code onPaid}）；同一个请求编号重来的不再回调。主线程上调用。
 */
final class PayEntry {
    /** 玩家确认过的拿法（确认框里写的就是它）：入口照它扣，不重新算 */
    static final class Plan {
        final CurrencyPlan.PayPlan items;
        /** 从玩家 pay.yr 账户余额里出的 */
        final Amount fromBalance;

        Plan(CurrencyPlan.PayPlan items, Amount fromBalance) {
            this.items = items;
            this.fromBalance = fromBalance;
        }
    }

    private final Ledger ledger;
    private final WorldPort world;
    private final TxRunner runner;
    private final LongSupplier clock;
    private final Consumer<Ledger.Paid> onPaid;
    /** 报错信息里要写物品名时先把名字算好（服务端是 {@link ItemText#cacheAll}；测试里不用） */
    private final Consumer<Amount> names;

    PayEntry(Ledger ledger, WorldPort world, TxRunner runner, LongSupplier clock, Consumer<Ledger.Paid> onPaid, Consumer<Amount> names) {
        this.ledger = ledger;
        this.world = world;
        this.runner = runner;
        this.clock = clock;
        this.onPaid = onPaid;
        this.names = names == null ? a -> {
        } : names;
    }

    /**
     * 付款。{@code confirmed} 是玩家在确认框里同意的拿法（付款页走这里）；为 null 时按 {@code spec} 的选项照现在的背包与余额算（模组直接调）。
     * 结果：做成（{@link TxRunner.Result.Status#DONE}，带付款）、没做成（带原因，什么都没扣）、处理中（玩家付款存盘还没核对上）。
     */
    TxRunner.Result pay(PaySpec spec, Plan confirmed) {
        try {
            if (spec.orderId == null || spec.orderId.isEmpty()) {
                throw new PayException("invalid_order", "要写账单号");
            }
            String req = spec.requestId();
            if (req != null && !VirtualAccount.REQUEST_ID.matcher(req).matches()) {
                throw new PayException("invalid_request_id", "请求编号要 1–100 个字母、数字或 _ . : -");
            }
            Ledger.Paid again = ledger.replay(spec.payer, spec.orderId, req);
            if (again != null) {
                return new TxRunner.Result(TxRunner.Result.Status.DONE, null, again, null);
            }
            if (spec.payer.isPlayer()) {
                return payAsPlayer(spec, confirmed);
            }
            Ledger.Paid paid = ledger.payFromVirtual(spec.payer, spec.orderId, spec.expected(), req, clock.getAsLong());
            if (!paid.replayed && onPaid != null) {
                onPaid.accept(paid);
            }
            return new TxRunner.Result(TxRunner.Result.Status.DONE, null, paid, null);
        } catch (PayException e) {
            return TxRunner.Result.failed(e);
        } catch (IOException e) {
            Ledger.LOG.error("pay.yr：写账本失败，{} 没有付 {}", spec.payer, spec.orderId, e);
            return TxRunner.Result.failed(new PayException("storage", "pay.yr 记账失败，没有扣，请稍后再试"));
        }
    }

    private TxRunner.Result payAsPlayer(PaySpec spec, Plan confirmed) throws PayException {
        java.util.UUID uuid = spec.payer.player;
        Ledger.Tx t = ledger.pendingByRequest(uuid, spec.requestId());
        if (t != null) {
            return new TxRunner.Result(TxRunner.Result.Status.PROCESSING, t.id, null, null);
        }
        Ledger.Order o = ledger.order(spec.orderId);
        if (o == null) {
            throw new PayException("no_such_order", "没有这张账单");
        }
        WorldPort.PlayerPort p = world.player(uuid);
        if (p == null) {
            throw new PayException("offline", "付款的角色不在线（要从他的背包拿）");
        }
        Plan plan = confirmed != null ? confirmed : plan(o, uuid, spec.prefer() == Currency.Unit.YUAN ? CurrencyPlan.Prefer.YUAN
                : CurrencyPlan.Prefer.EMERALD, spec.balanceYuan() < 0 ? CurrencyPlan.BalanceUse.ALL : CurrencyPlan.BalanceUse.yuan(spec.balanceYuan()));
        return runner.pay(o, p, plan.items.take, plan.items.change, plan.fromBalance, spec.expected(), spec.requestId());
    }

    /** 按现在的余额与背包算玩家怎么付（付款页的确认框、模组直接让玩家付都用它）；背包里的不够时抛异常 */
    Plan plan(Ledger.Order o, java.util.UUID payer, CurrencyPlan.Prefer prefer, CurrencyPlan.BalanceUse use) throws PayException {
        WorldPort.PlayerPort p = world.player(payer);
        if (p == null) {
            throw new PayException("offline", "付款的角色不在线（要从他的背包拿）");
        }
        Amount fromBal = CurrencyPlan.fromBalance(o.price, ledger.balance(AccountRef.player(payer)), use);
        CurrencyPlan.PayPlan items = CurrencyPlan.planPayment(o.price.minus(fromBal), p.counts(), prefer);
        if (!items.ok()) {
            names.accept(items.lack);
            throw new PayException("insufficient_items", "背包里的不够：还差 " + ItemText.describe(items.lack, o.unit));
        }
        return new Plan(items, fromBal);
    }
}
