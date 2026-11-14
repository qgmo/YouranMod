package moe.qingu.youran.server.net;

import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** 地址分配表：按顺序分空号、指定编号、冲突拒绝、名字以后拿到的不变 */
class AllocationTableTest {
    private static AllocationTable.Request dc(String key, long fixed) {
        return new AllocationTable.Request(key, fixed, null);
    }

    private static AllocationTable.Request host(String key, long fixed, String dc) {
        return new AllocationTable.Request(key, fixed, dc);
    }

    @Test
    void dimensionsInConfigOrderAndKept() {
        AllocationTable t = new AllocationTable();
        List<String> p = new ArrayList<>();
        Map<Integer, Integer> a = t.assignDimensions(new int[]{0, -1, 1}, p);
        assertEquals(Arrays.asList(0, 1, 2), new ArrayList<>(a.values()));
        // 下次清单换了顺序、删了下界、加了 7：老的不变，新的拿最小空号
        Map<Integer, Integer> b = t.assignDimensions(new int[]{1, 7, 0}, p);
        assertEquals(2, (int) b.get(1));
        assertEquals(3, (int) b.get(7));
        assertEquals(0, (int) b.get(0));
        assertFalse(b.containsKey(-1));
        assertEquals(1, (int) t.dimensions.get(-1), "下界的序号保留");
        assertTrue(p.isEmpty());
    }

    @Test
    void dimensionLimit() {
        AllocationTable t = new AllocationTable();
        int[] dims = new int[257];
        for (int i = 0; i < dims.length; i++) {
            dims[i] = i + 100;
        }
        List<String> p = new ArrayList<>();
        Map<Integer, Integer> a = t.assignDimensions(dims, p);
        assertEquals(256, a.size());
        assertEquals(1, p.size());
    }

    @Test
    void datacentersFixedFirstThenSequential() {
        AllocationTable t = new AllocationTable();
        List<String> p = new ArrayList<>();
        Map<String, Long> a = t.assignDatacenters(Arrays.asList(dc("cfm:shop", -1), dc("youranmod:main", 0), dc("scp:site", 5)), p);
        assertEquals(0L, (long) a.get("youranmod:main"));
        assertEquals(5L, (long) a.get("scp:site"));
        assertEquals(1L, (long) a.get("cfm:shop"));
        assertTrue(p.isEmpty());
        // 冲突：指定 5 的另一个名字被拒绝
        List<String> p2 = new ArrayList<>();
        Map<String, Long> b = t.assignDatacenters(Arrays.asList(dc("youranmod:main", 0), dc("other:x", 5), dc("cfm:shop", -1)), p2);
        assertFalse(b.containsKey("other:x"));
        assertEquals(1, p2.size());
        assertTrue(p2.get(0).contains("scp:site"));
        assertEquals(1L, (long) b.get("cfm:shop"), "同一个名字拿到的不变");
        // 没登记的 scp:site 仍占着 5
        assertEquals(5L, (long) t.datacenters.get("scp:site"));
    }

    @Test
    void hostsSequentialSkipZeroAndFixed() {
        AllocationTable t = new AllocationTable();
        List<String> p = new ArrayList<>();
        Map<String, AllocationTable.Host> a = t.assignHosts(Arrays.asList(
                host("youranmod:router", 1, "youranmod:main"),
                host("youranmod:test", -1, "youranmod:main"),
                host("youranmod:resolver", 0x53, "youranmod:main"),
                host("cfm:minebay", -1, "youranmod:main"),
                host("ghost:x", -1, "ghost:dc")), new HashSet<>(Collections.singletonList("youranmod:main")), p);
        assertEquals(1, a.get("youranmod:router").hostPart);
        assertEquals(0x53, a.get("youranmod:resolver").hostPart);
        assertEquals(2, a.get("youranmod:test").hostPart);
        assertEquals(3, a.get("cfm:minebay").hostPart);
        assertFalse(a.containsKey("ghost:x"));
        assertEquals(1, p.size());
        // 再开一次：先登记 cfm:minebay，地址不变
        List<String> p2 = new ArrayList<>();
        Map<String, AllocationTable.Host> b = t.assignHosts(Arrays.asList(
                host("cfm:minebay", -1, "youranmod:main"), host("new:one", -1, "youranmod:main"),
                host("bad:fixed", 2, "youranmod:main")), new HashSet<>(Collections.singletonList("youranmod:main")), p2);
        assertEquals(3, b.get("cfm:minebay").hostPart);
        assertEquals(4, b.get("new:one").hostPart);
        assertFalse(b.containsKey("bad:fixed"), "::2 已经给了 youranmod:test");
        assertEquals(1, p2.size());
    }

    @Test
    void lowestFree() {
        assertEquals(3, AllocationTable.lowestFree(new java.util.TreeSet<>(Arrays.asList(1L, 2L, 5L)), 1, 10));
        assertEquals(-1, AllocationTable.lowestFree(new java.util.TreeSet<>(Arrays.asList(1L, 2L)), 1, 2));
        assertEquals(0, AllocationTable.lowestFree(new HashSet<>(Arrays.asList(1, 2)), 0, 255));
    }
}
