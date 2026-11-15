package moe.qingu.youran.api.pay;

import java.util.Collections;
import java.util.Map;
import java.util.TreeMap;
import java.util.function.Function;

/**
 * 一笔东西：钱（绿宝石与悠然元合并，按元记，见 {@link Currency}）+ 其他物品（按 {@link PayItem} 分，各多少个）。都不为负。不可变。
 */
public final class Amount {
    public static final Amount ZERO = new Amount(0, Collections.<PayItem, Long>emptyMap());

    /** 钱，单位元 */
    public final long yuan;
    /** 其他物品（不含绿宝石、悠然元），按物品排好；没有数量为 0 的 */
    public final Map<PayItem, Long> items;

    private Amount(long yuan, Map<PayItem, Long> items) {
        this.yuan = yuan;
        this.items = items;
    }

    public static Amount of(long yuan, Map<PayItem, Long> items) {
        if (yuan < 0) {
            throw new IllegalArgumentException("钱不能为负");
        }
        TreeMap<PayItem, Long> m = new TreeMap<>();
        long y = yuan;
        if (items != null) {
            for (Map.Entry<PayItem, Long> e : items.entrySet()) {
                long n = e.getValue();
                if (n < 0) {
                    throw new IllegalArgumentException("数量不能为负");
                }
                if (n == 0) {
                    continue;
                }
                Currency.Denomination d = Currency.Denomination.of(e.getKey());
                if (d != null) {
                    y = Math.addExact(y, Math.multiplyExact(n, d.value));
                } else {
                    m.merge(e.getKey(), n, Math::addExact);
                }
            }
        }
        return y == 0 && m.isEmpty() ? ZERO : new Amount(y, Collections.unmodifiableMap(m));
    }

    public static Amount yuan(long yuan) {
        return of(yuan, null);
    }

    public static Amount item(PayItem item, long count) {
        return of(0, Collections.singletonMap(item, count));
    }

    /** 一堆实物（钱按面额折成元）合成一笔 */
    public static Amount ofItems(Map<PayItem, Long> things) {
        return of(0, things);
    }

    public boolean isZero() {
        return yuan == 0 && items.isEmpty();
    }

    public long count(PayItem item) {
        Long n = items.get(item);
        return n == null ? 0 : n;
    }

    public Amount plus(Amount o) {
        TreeMap<PayItem, Long> m = new TreeMap<>(items);
        for (Map.Entry<PayItem, Long> e : o.items.entrySet()) {
            m.merge(e.getKey(), e.getValue(), Math::addExact);
        }
        return of(Math.addExact(yuan, o.yuan), m);
    }

    /** 减去 {@code o}；不够减时抛 {@link IllegalArgumentException} */
    public Amount minus(Amount o) {
        if (!covers(o)) {
            throw new IllegalArgumentException("不够减");
        }
        TreeMap<PayItem, Long> m = new TreeMap<>(items);
        for (Map.Entry<PayItem, Long> e : o.items.entrySet()) {
            m.put(e.getKey(), m.get(e.getKey()) - e.getValue());
        }
        return of(yuan - o.yuan, m);
    }

    /** 每一样都不少于 {@code o} */
    public boolean covers(Amount o) {
        if (yuan < o.yuan) {
            return false;
        }
        for (Map.Entry<PayItem, Long> e : o.items.entrySet()) {
            if (count(e.getKey()) < e.getValue()) {
                return false;
            }
        }
        return true;
    }

    /** {@code o} 比这笔多出来的部分（不够的那些） */
    public Amount shortOf(Amount o) {
        TreeMap<PayItem, Long> m = new TreeMap<>();
        for (Map.Entry<PayItem, Long> e : o.items.entrySet()) {
            long lack = e.getValue() - count(e.getKey());
            if (lack > 0) {
                m.put(e.getKey(), lack);
            }
        }
        return of(Math.max(0, o.yuan - yuan), m);
    }

    /** 给人看：{@code 20 绿宝石（40 元）、钻石 ×3}；{@code names} 把物品变成名字 */
    public String describe(Currency.Unit unit, Function<PayItem, String> names) {
        StringBuilder sb = new StringBuilder();
        if (yuan > 0) {
            sb.append(Currency.formatBoth(yuan, unit));
        }
        for (Map.Entry<PayItem, Long> e : items.entrySet()) {
            if (sb.length() > 0) {
                sb.append("、");
            }
            sb.append(names.apply(e.getKey())).append(" ×").append(e.getValue());
        }
        return sb.length() == 0 ? "（无）" : sb.toString();
    }

    @Override
    public boolean equals(Object o) {
        return o instanceof Amount && ((Amount) o).yuan == yuan && ((Amount) o).items.equals(items);
    }

    @Override
    public int hashCode() {
        return Long.hashCode(yuan) * 31 + items.hashCode();
    }

    @Override
    public String toString() {
        return describe(Currency.Unit.YUAN, PayItem::toString);
    }
}
