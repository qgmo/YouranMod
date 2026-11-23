package moe.qingu.youran.server.net.pay;

import moe.qingu.youran.api.pay.AccountRef;
import moe.qingu.youran.api.pay.Amount;
import moe.qingu.youran.api.pay.OrderInfo;
import moe.qingu.youran.api.pay.PaymentInfo;
import moe.qingu.youran.api.pay.PaymentListener;

import java.io.IOException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Function;
import java.util.function.LongSupplier;

/**
 * 通知收款方（{@link PaymentListener}），保证至少送到一次。主线程上用（{@link #kick} 任意线程）。
 * <ul>
 * <li>付款、退款记账以后马上回调；收款方的监听器都正常返回以后，才在账本里记“已通知”（{@link Ledger#markNotified}）。</li>
 * <li>没记上的（回调前崩了、回调抛异常、记的时候写盘失败）都留在账本里（{@link Ledger#notices}）：开服后第一次 {@link #tick}
 * （各模组都已开服、读好了自己的存档）补发一遍；有监听器登记到某个账户时（{@link #kick}）补发那个账户的。</li>
 * <li>回调抛异常：不记，1 分钟后重试，之后每次加倍，最长每小时一次。日志只在第一次失败时写全（带异常），之后每 24 次写一行，
 * 成功时写一行“补上了”，不会每次都刷。</li>
 * <li>收款账户没有监听器时什么都不做，也不记“已通知”，等有监听器登记了再补。</li>
 * </ul>
 * 所以同一笔付款可能回调不止一次，收款方按付款编号去重（{@link PaymentListener} 的约定）。
 */
final class PayNotifier {
    /** 第一次失败后多久重试 */
    static final long FIRST_RETRY_MILLIS = 60_000;
    /** 重试间隔最长多久 */
    static final long MAX_RETRY_MILLIS = 3_600_000;
    /** 一直失败时每多少次写一行日志 */
    static final int LOG_EVERY = 24;

    /** 一笔付款没通知成的情况（运行时，不存盘：重开后从头算） */
    private static final class Retry {
        int failures;
        long nextAt;
    }

    private final Ledger ledger;
    private final Function<AccountRef, List<PaymentListener>> listeners;
    private final Function<String, OrderInfo> orders;
    private final LongSupplier clock;
    private final Map<String, Retry> retries = new HashMap<>();
    /** 有监听器新登记的账户（任意线程放进来，{@link #tick} 取走） */
    private final Set<AccountRef> kicked = ConcurrentHashMap.newKeySet();
    private volatile boolean kickAll;
    /** 开服后第一次 {@link #tick} 以前不回调（别的模组可能还没读好存档），只留在账本里等补发 */
    private boolean ready;
    /** 正在回调（回调里又付款、退款时不嵌套回调，记下来等这一轮回调完再发） */
    private boolean busy;
    private final java.util.LinkedHashSet<String> later = new java.util.LinkedHashSet<>();

    /**
     * @param listeners 收款账户 → 登记的监听器（服务端是 {@code PayHooks::listeners}）
     * @param orders    收款单号 → 现在的样子（给 {@code onPaid} 的）
     */
    PayNotifier(Ledger ledger, Function<AccountRef, List<PaymentListener>> listeners, Function<String, OrderInfo> orders, LongSupplier clock) {
        this.ledger = ledger;
        this.listeners = listeners;
        this.orders = orders;
        this.clock = clock;
    }

    /** 开服：下一次 {@link #tick} 把账本里没通知过的都补发一遍 */
    void kickAll() {
        kickAll = true;
    }

    /** 有监听器登记到 {@code account}：下一次 {@link #tick} 补发这个账户没通知过的（任意线程） */
    void kick(AccountRef account) {
        if (account != null) {
            kicked.add(account);
        }
    }

    /** 刚记好一笔付款或退款（主线程）：马上通知（开服后第一次 tick 以前不通知，等补发） */
    void notify(String paymentId) {
        if (!ready) {
            return;
        }
        later.add(paymentId);
        if (busy) {
            return;
        }
        busy = true;
        try {
            drainLater();
        } finally {
            busy = false;
        }
    }

    /** 回调期间记下的（回调里付了款、退了款）：一笔一笔发 */
    private void drainLater() {
        while (!later.isEmpty()) {
            java.util.Iterator<String> it = later.iterator();
            String id = it.next();
            it.remove();
            for (Ledger.Notice n : ledger.notices()) {
                if (n.paymentId.equals(id)) {
                    deliver(n, clock.getAsLong());
                    break;
                }
            }
        }
    }

    /** 主线程，每秒（开服后第一次越早越好）：补发、重试到时间的 */
    void tick() {
        ready = true;
        long now = clock.getAsLong();
        boolean all = kickAll;
        kickAll = false;
        Set<AccountRef> ks = new HashSet<>();
        for (AccountRef a : new ArrayList<>(kicked)) {
            kicked.remove(a);
            ks.add(a);
        }
        if (!all && ks.isEmpty() && retries.isEmpty()) {
            return;
        }
        List<Ledger.Notice> notices = ledger.notices();
        Set<String> live = new HashSet<>();
        int resent = 0;
        busy = true;
        try {
            for (Ledger.Notice n : notices) {
                live.add(n.paymentId);
                Retry r = retries.get(n.paymentId);
                if (all || ks.contains(n.receiver) || (r != null && now >= r.nextAt)) {
                    if (deliver(n, now) && r == null) {
                        resent++;
                    }
                }
            }
            drainLater();
        } finally {
            busy = false;
        }
        // 已经不在账本里的（通知过了、付款清掉了）不再重试
        for (Ledger.Notice n : ledger.notices()) {
            live.add(n.paymentId);
        }
        retries.keySet().retainAll(live);
        if (resent > 0) {
            Ledger.LOG.info("pay.yr：补发了 {} 笔付款 / 退款的通知（上次没确认收款方收到）", resent);
        }
    }

    /** 这笔付款还在等重试（测试、日志用） */
    boolean retrying(String paymentId) {
        return retries.containsKey(paymentId);
    }

    /** 通知一笔付款的事（先 onPaid，再按先后各次退款），每件事所有监听器都正常返回后记“已通知”。全部通知成了返回 true */
    private boolean deliver(Ledger.Notice n, long now) {
        List<PaymentListener> ls = listeners.apply(n.receiver);
        if (ls == null || ls.isEmpty()) {
            // 没人听：不算通知过，等有监听器登记再补
            retries.remove(n.paymentId);
            return false;
        }
        Ledger.Payment p = ledger.payment(n.paymentId);
        if (p == null) {
            return false;
        }
        if (n.paid) {
            OrderInfo o = orders.apply(n.orderId);
            PaymentInfo info = p.info();
            if (!call(n, "onPaid", now, l -> l.onPaid(o, info), ls) || !mark(n, null, now)) {
                return false;
            }
        }
        for (Map.Entry<String, Amount> r : n.refunds.entrySet()) {
            PaymentInfo info = p.info();
            if (!call(n, "onRefunded " + r.getKey(), now, l -> l.onRefunded(info, r.getKey(), r.getValue()), ls) || !mark(n, r.getKey(), now)) {
                return false;
            }
        }
        Retry r = retries.remove(n.paymentId);
        if (r != null) {
            Ledger.LOG.info("pay.yr：付款 {} 的通知补上了（收款方 {}，之前失败 {} 次）", n.paymentId, n.receiver, r.failures);
        }
        return true;
    }

    /** 每个监听器都调一遍；有一个抛异常就算没通知成 */
    private boolean call(Ledger.Notice n, String step, long now, java.util.function.Consumer<PaymentListener> c, List<PaymentListener> ls) {
        try {
            for (PaymentListener l : ls) {
                c.accept(l);
            }
            return true;
        } catch (RuntimeException | LinkageError e) {
            failed(n, step, "收款人的回调出错", e, now);
            return false;
        }
    }

    /** 记“已通知”；写不进去算没通知成（下次再发） */
    private boolean mark(Ledger.Notice n, String refundId, long now) {
        try {
            ledger.markNotified(n.paymentId, refundId);
            return true;
        } catch (IOException | RuntimeException e) {
            failed(n, refundId == null ? "onPaid" : "onRefunded " + refundId, "记“已通知”时写账本失败", e, now);
            return false;
        }
    }

    private void failed(Ledger.Notice n, String step, String why, Throwable e, long now) {
        Retry r = retries.computeIfAbsent(n.paymentId, k -> new Retry());
        r.failures++;
        long delay = Math.min(MAX_RETRY_MILLIS, FIRST_RETRY_MILLIS << Math.min(r.failures - 1, 16));
        r.nextAt = now + delay;
        if (r.failures == 1) {
            Ledger.LOG.error("pay.yr：通知收款方 {} 付款 {}（{}）时{}；没有记“已通知”，{} 秒后重试，之后间隔加倍、最长每小时一次，"
                    + "再失败不逐次记日志", n.receiver, n.paymentId, step, why, delay / 1000, e);
        } else if (r.failures % LOG_EVERY == 0) {
            Ledger.LOG.warn("pay.yr：通知收款方 {} 付款 {}（{}）还是失败（第 {} 次）：{}", n.receiver, n.paymentId, step, r.failures, e.toString());
        }
    }
}
