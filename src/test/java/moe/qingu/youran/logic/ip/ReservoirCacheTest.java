package moe.qingu.youran.logic.ip;

import org.junit.jupiter.api.Test;

import java.util.HashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ReservoirCacheTest {
    private static final Object OIL = "oil";
    private static final Object LAVA = "lava";

    @Test
    void untouchedGeneratedEntryCanBeDropped() {
        assertTrue(ReservoirCache.regenerable(true, OIL, 5000, OIL, null, 5000, 5000));
        // 没有储层的区块（类型为空、容量 0）
        assertTrue(ReservoirCache.regenerable(true, null, 0, null, null, 0, 0));
    }

    @Test
    void entriesFromSaveAreKept() {
        assertFalse(ReservoirCache.regenerable(false, null, 0, OIL, null, 5000, 5000));
        assertFalse(ReservoirCache.regenerable(false, OIL, 5000, OIL, null, 5000, 5000));
    }

    @Test
    void pumpedOrEditedEntriesAreKept() {
        // 抽过油
        assertFalse(ReservoirCache.regenerable(true, OIL, 5000, OIL, null, 5000, 4999));
        assertFalse(ReservoirCache.regenerable(true, OIL, 5000, OIL, null, 5000, 0));
        // 管理员设了覆盖类型
        assertFalse(ReservoirCache.regenerable(true, OIL, 5000, OIL, LAVA, 5000, 5000));
        // 管理员改了容量（储量跟着改成一样也不行）
        assertFalse(ReservoirCache.regenerable(true, OIL, 5000, OIL, null, 3000, 3000));
        // 类型被改了
        assertFalse(ReservoirCache.regenerable(true, OIL, 5000, LAVA, null, 5000, 5000));
    }

    @Test
    void removeIfCounts() {
        Map<String, Integer> m = new HashMap<>();
        for (int i = 0; i < 10; i++) {
            m.put("k" + i, i);
        }
        assertEquals(5, ReservoirCache.removeIf(m, e -> e.getValue() % 2 == 0));
        assertEquals(5, m.size());
        assertFalse(m.containsKey("k0"));
        assertTrue(m.containsKey("k1"));
    }

    @Test
    void pruneTimesKeepsOnlyThisTick() {
        Map<String, Long> times = new HashMap<>();
        times.put("dim0-a", 100L);
        times.put("dim0-b", 99L);
        times.put("dim7-a", 50L);
        times.put("gone-a", 100L);
        int removed = ReservoirCache.pruneTimes(times, k -> k.startsWith("dim0") ? 100L : k.startsWith("dim7") ? 50L : Long.MIN_VALUE);
        assertEquals(2, removed);
        assertTrue(times.containsKey("dim0-a"));
        assertTrue(times.containsKey("dim7-a"));
        assertFalse(times.containsKey("dim0-b"));
        assertFalse(times.containsKey("gone-a"));
    }
}
