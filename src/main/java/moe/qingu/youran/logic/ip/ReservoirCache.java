package moe.qingu.youran.logic.ip;

import java.util.Iterator;
import java.util.Map;
import java.util.Objects;
import java.util.function.Predicate;
import java.util.function.ToLongFunction;

/**
 * 沉浸原油油井信息缓存的清理规则（审查 IP-L1），由 {@code compat.immersivepetroleum} 调用。
 *
 * <p>沉浸原油第一次查某个区块的储层时按世界种子与区块坐标现算一份（类型、容量，储量 = 容量），放进缓存且永不删，
 * 每次存档都整块写出。只有被抽过油、被管理员改过的那些才真的需要记着；从没动过的条目删掉以后，
 * 下次查会按同样的种子、坐标再算出同样的一份。这里只认“这次开服里现算出来、之后没动过”的条目可以删
 * （见 {@link #regenerable}）；从存档读回来的条目不知道当初是怎么来的，一律照旧保留。
 */
public final class ReservoirCache {
    private ReservoirCache() {
    }

    /**
     * 条目能不能删掉（删了下次再算出来一模一样）：是这次开服现算的（{@code generated}），类型与容量还是算出来时的，
     * 没有管理员设的覆盖类型，储量没被抽过（等于容量）。
     */
    public static boolean regenerable(boolean generated, Object generatedType, int generatedCapacity,
                                      Object type, Object overrideType, int capacity, int current) {
        return generated && overrideType == null && Objects.equals(type, generatedType)
                && capacity == generatedCapacity && current == capacity;
    }

    /** 从 {@code map} 删掉 {@code drop} 认可的条目，返回删了几个 */
    public static <K, V> int removeIf(Map<K, V> map, Predicate<Map.Entry<K, V>> drop) {
        int removed = 0;
        Iterator<Map.Entry<K, V>> it = map.entrySet().iterator();
        while (it.hasNext()) {
            if (drop.test(it.next())) {
                it.remove();
                removed++;
            }
        }
        return removed;
    }

    /**
     * 清理残余产量的计时表（区块 → 上次给残余产量的世界时间）。沉浸原油只用它判断“这一 tick 给过没有”：
     * 记的时间不是现在的条目，与没有这一条效果完全相同（都给残余产量并记下现在），可以删。
     * {@code now} 给出条目所在世界现在的时间，世界没加载时给 {@code Long.MIN_VALUE}（删掉）。
     */
    public static <K> int pruneTimes(Map<K, Long> times, ToLongFunction<K> now) {
        return removeIf(times, e -> e.getValue() == null || e.getValue() != now.applyAsLong(e.getKey()));
    }
}
