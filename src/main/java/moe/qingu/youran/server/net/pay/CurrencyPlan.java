package moe.qingu.youran.server.net.pay;

import moe.qingu.youran.api.pay.Amount;
import moe.qingu.youran.api.pay.Currency;
import moe.qingu.youran.api.pay.Currency.Denomination;
import moe.qingu.youran.api.pay.PayItem;

import java.util.EnumMap;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 绿宝石与悠然元怎么凑、怎么找零、怎么分面额（服主 11-15：1 绿宝石 = 2 元，可以混着付，按面额合计）。纯计算，不碰世界。
 */
final class CurrencyPlan {
    /** 玩家选的：先用哪种付 */
    enum Prefer {
        EMERALD, YUAN
    }

    /** 从背包拿哪些、找多少零（元） */
    static final class Pick {
        final Map<Denomination, Long> take;
        final long change;

        Pick(Map<Denomination, Long> take, long change) {
            this.take = take;
            this.change = change;
        }

        long value() {
            long v = 0;
            for (Map.Entry<Denomination, Long> e : take.entrySet()) {
                v += e.getKey().value * e.getValue();
            }
            return v;
        }

        Map<PayItem, Long> takeItems() {
            Map<PayItem, Long> m = new LinkedHashMap<>();
            for (Map.Entry<Denomination, Long> e : take.entrySet()) {
                if (e.getValue() > 0) {
                    m.put(e.getKey().item, e.getValue());
                }
            }
            return m;
        }
    }

    /** 付一笔要从背包拿哪些、找哪些；不够时 {@link #lack} 是还差的 */
    static final class PayPlan {
        final Map<PayItem, Long> take;
        final Map<PayItem, Long> change;
        final long changeYuan;
        final Amount lack;

        PayPlan(Map<PayItem, Long> take, Map<PayItem, Long> change, long changeYuan, Amount lack) {
            this.take = take;
            this.change = change;
            this.changeYuan = changeYuan;
            this.lack = lack;
        }

        boolean ok() {
            return lack == null;
        }
    }

    /**
     * 付款时用多少 pay.yr 账户余额（服主 11-15：可以直接用账户里的余额付，不用先提现；全部或部分，剩下的从背包拿）。
     * 余额按价值记：钱（元）不分绿宝石还是悠然元；账户里的别的物品（退款收到的钻石等）只有“能用的全用上”时才用。
     */
    static final class BalanceUse {
        static final BalanceUse NONE = new BalanceUse(0, false);
        static final BalanceUse ALL = new BalanceUse(-1, true);

        /** 用多少元；-1 为能用多少用多少 */
        final long yuan;
        /** 账户里的别的物品也用上 */
        final boolean items;

        private BalanceUse(long yuan, boolean items) {
            this.yuan = yuan;
            this.items = items;
        }

        /** 只用 {@code yuan} 元余额（别的物品从背包拿） */
        static BalanceUse yuan(long yuan) {
            return yuan <= 0 ? NONE : new BalanceUse(yuan, false);
        }

        /** 表单 / 网址里的写法：{@code none}、{@code all}、或元数（1–9 位整数）；写错为 null */
        static BalanceUse parse(String s) {
            if (s == null || s.isEmpty() || s.equals("none")) {
                return NONE;
            }
            if (s.equals("all")) {
                return ALL;
            }
            if (s.matches("[0-9]{1,9}")) {
                return yuan(Long.parseLong(s));
            }
            return null;
        }

        String code() {
            return this == ALL || yuan < 0 ? "all" : yuan == 0 ? "none" : Long.toString(yuan);
        }

        boolean isNone() {
            return yuan == 0 && !items;
        }
    }

    /**
     * 这笔付款里从余额出多少：不超过价格、不超过余额。{@code use} 指定的元数比余额或价格（钱的部分）多时抛异常（说明原因）。
     */
    static Amount fromBalance(Amount price, Amount balance, BalanceUse use) throws moe.qingu.youran.api.pay.PayException {
        if (use == null || use.isNone()) {
            return Amount.ZERO;
        }
        long yuan;
        if (use.yuan < 0) {
            yuan = Math.min(price.yuan, balance.yuan);
        } else {
            if (use.yuan > balance.yuan) {
                throw new moe.qingu.youran.api.pay.PayException("insufficient_balance", "pay.yr 账户余额只有 " + balance.yuan + " 元，不够用 " + use.yuan + " 元");
            }
            if (use.yuan > price.yuan) {
                throw new moe.qingu.youran.api.pay.PayException("invalid_amount", "用余额付的比要付的钱（" + price.yuan + " 元）还多");
            }
            yuan = use.yuan;
        }
        Map<PayItem, Long> items = new LinkedHashMap<>();
        if (use.items) {
            for (Map.Entry<PayItem, Long> e : price.items.entrySet()) {
                long n = Math.min(e.getValue(), balance.count(e.getKey()));
                if (n > 0) {
                    items.put(e.getKey(), n);
                }
            }
        }
        return Amount.of(yuan, items);
    }

    private CurrencyPlan() {
    }

    /** 按背包里有的（{@code counts}）算怎么付 {@code price}：别的物品照单拿，钱按 {@link #pick} 凑、找零 */
    static PayPlan planPayment(Amount price, Map<PayItem, Long> counts, Prefer prefer) {
        Map<PayItem, Long> take = new LinkedHashMap<>();
        Map<PayItem, Long> lackItems = new LinkedHashMap<>();
        for (Map.Entry<PayItem, Long> e : price.items.entrySet()) {
            long have = counts.getOrDefault(e.getKey(), 0L);
            if (have < e.getValue()) {
                lackItems.put(e.getKey(), e.getValue() - have);
            }
            take.put(e.getKey(), e.getValue());
        }
        Map<Denomination, Long> avail = new EnumMap<>(Denomination.class);
        long total = 0;
        for (Denomination d : Denomination.values()) {
            long n = counts.getOrDefault(d.item, 0L);
            avail.put(d, n);
            total += n * d.value;
        }
        Pick pick = pick(avail, price.yuan, prefer);
        if (pick == null || !lackItems.isEmpty()) {
            long lackYuan = pick == null ? price.yuan - total : 0;
            return new PayPlan(take, new LinkedHashMap<PayItem, Long>(), 0, Amount.of(Math.max(0, lackYuan), lackItems));
        }
        for (Map.Entry<PayItem, Long> e : pick.takeItems().entrySet()) {
            take.merge(e.getKey(), e.getValue(), Math::addExact);
        }
        return new PayPlan(take, change(pick.change), pick.change, null);
    }

    /**
     * 从 {@code avail}（各面额有多少）里凑出至少 {@code target} 元：先让找零最少，再让“不是先用的那种”用得最少，再让个数最少。
     * 凑不够返回 null。{@code target} 为 0 时什么都不拿。
     */
    static Pick pick(Map<Denomination, Long> avail, long target, Prefer prefer) {
        if (target <= 0) {
            return new Pick(new EnumMap<Denomination, Long>(Denomination.class), 0);
        }
        long e = get(avail, Denomination.EMERALD);
        long n10 = get(avail, Denomination.TEN);
        long n5 = get(avail, Denomination.FIVE);
        long coins = get(avail, Denomination.ONE_COIN);
        long notes = get(avail, Denomination.ONE_NOTE);
        long n1 = coins + notes;
        long total = e * Currency.YUAN_PER_EMERALD + n10 * 10 + n5 * 5 + n1;
        if (total < target) {
            return null;
        }
        long bestOver = Long.MAX_VALUE, bestOther = Long.MAX_VALUE, bestCount = Long.MAX_VALUE;
        long[] best = null;
        long eMax = Math.min(e, (target + Currency.YUAN_PER_EMERALD - 1) / Currency.YUAN_PER_EMERALD);
        for (long ue = 0; ue <= eMax; ue++) {
            long r = target - ue * Currency.YUAN_PER_EMERALD;
            long tMax = r <= 0 ? 0 : Math.min(n10, (r + 9) / 10);
            for (long t = 0; t <= tMax; t++) {
                long r2 = r - 10 * t;
                long f, o, over;
                if (r2 <= 0) {
                    f = 0;
                    o = 0;
                    over = -r2;
                } else {
                    long lo = Math.max(0, ceilDiv(r2 - n1, 5));
                    long hi = Math.min(n5, r2 / 5);
                    if (lo <= hi) {
                        f = hi;
                        o = r2 - 5 * hi;
                        over = 0;
                    } else if (lo <= n5) {
                        f = lo;
                        o = Math.max(0, r2 - 5 * lo);
                        over = 5 * f + o - r2;
                    } else {
                        continue;
                    }
                }
                long emeraldValue = ue * Currency.YUAN_PER_EMERALD;
                long yuanValue = 10 * t + 5 * f + o;
                long other = prefer == Prefer.EMERALD ? yuanValue : emeraldValue;
                long count = ue + t + f + o;
                if (over < bestOver || (over == bestOver && (other < bestOther || (other == bestOther && count < bestCount)))) {
                    bestOver = over;
                    bestOther = other;
                    bestCount = count;
                    best = new long[]{ue, t, f, o};
                }
            }
        }
        if (best == null) {
            return null;
        }
        Map<Denomination, Long> take = new EnumMap<>(Denomination.class);
        put(take, Denomination.EMERALD, best[0]);
        put(take, Denomination.TEN, best[1]);
        put(take, Denomination.FIVE, best[2]);
        // 一元：先拿硬币，再拿纸币
        long c = Math.min(coins, best[3]);
        put(take, Denomination.ONE_COIN, c);
        put(take, Denomination.ONE_NOTE, best[3] - c);
        return new Pick(take, bestOver);
    }

    /** 找零：按面额从大到小给悠然元（一元给纸币） */
    static Map<PayItem, Long> change(long yuan) {
        return split(yuan, Currency.Unit.YUAN);
    }

    /**
     * 提现、取出时把钱分成物品（服主 11-15）：要绿宝石时给绿宝石，不足一个绿宝石的零头给元；要元时按面额从大到小给（一元给纸币）。
     */
    static Map<PayItem, Long> split(long yuan, Currency.Unit form) {
        Map<PayItem, Long> m = new LinkedHashMap<>();
        long left = yuan;
        if (form == Currency.Unit.EMERALD) {
            long em = left / Currency.YUAN_PER_EMERALD;
            if (em > 0) {
                m.put(Denomination.EMERALD.item, em);
            }
            left -= em * Currency.YUAN_PER_EMERALD;
        }
        for (Denomination d : new Denomination[]{Denomination.TEN, Denomination.FIVE, Denomination.ONE_NOTE}) {
            long n = left / d.value;
            if (n > 0) {
                m.put(d.item, n);
                left -= n * d.value;
            }
        }
        return m;
    }

    private static long ceilDiv(long a, long b) {
        return a <= 0 ? 0 : (a + b - 1) / b;
    }

    private static long get(Map<Denomination, Long> m, Denomination d) {
        Long v = m.get(d);
        return v == null ? 0 : Math.max(0, v);
    }

    private static void put(Map<Denomination, Long> m, Denomination d, long n) {
        if (n > 0) {
            m.put(d, n);
        }
    }
}
