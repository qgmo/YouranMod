package moe.qingu.youran.compat.alchemistry;

import org.junit.jupiter.api.Test;

import java.util.Arrays;
import java.util.HashSet;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class AlchemistryCompatTest {
    /** 元数据避开炼金化学内置的（0–60）与整合包脚本用的（500–512），互不重复；组成照服主定的 */
    @Test
    void compounds() {
        Set<Integer> metas = new HashSet<>();
        for (AlchemistryCompat.CompoundSpec c : AlchemistryCompat.COMPOUNDS) {
            assertTrue(c.meta > 512, c.name);
            assertTrue(metas.add(c.meta), c.name);
        }
        assertEquals(3, AlchemistryCompat.COMPOUNDS.size());
        assertSpec(0, "triiron_tetroxide", 600, "iron", 3, "oxygen", 4);
        assertSpec(1, "acetic_acid", 601, "carbon", 2, "hydrogen", 4, "oxygen", 2);
        assertSpec(2, "cellulose_acetate", 602, "carbon", 12, "hydrogen", 16, "oxygen", 8);
    }

    private static void assertSpec(int i, String name, int meta, Object... components) {
        AlchemistryCompat.CompoundSpec c = AlchemistryCompat.COMPOUNDS.get(i);
        assertEquals(name, c.name);
        assertEquals(meta, c.meta);
        assertArrayEquals(components, c.components, Arrays.toString(c.components));
    }
}
