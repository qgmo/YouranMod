package moe.qingu.youran.logic.boron;

import org.junit.jupiter.api.Test;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;

class TourmalineTest {
    /** 各单元的组成（原子个数），用来数原子 */
    private static final Map<String, Map<String, Integer>> UNITS = new HashMap<>();

    static {
        unit(Tourmaline.METASILICATE, "silicon", 1, "oxygen", 3);
        unit(Tourmaline.BORATE, "boron", 1, "oxygen", 3);
        unit(Tourmaline.HYDROXIDE, "oxygen", 1, "hydrogen", 1);
    }

    private static void unit(String name, Object... atoms) {
        Map<String, Integer> m = new HashMap<>();
        for (int i = 0; i < atoms.length; i += 2) {
            m.put((String) atoms[i], (Integer) atoms[i + 1]);
        }
        UNITS.put(name, m);
    }

    /** 必出那组拆到原子 */
    private static Map<String, Integer> atoms(Tourmaline t) {
        Map<String, Integer> atoms = new HashMap<>();
        for (Tourmaline.Portion p : t.dissolverOutputs().get(0)) {
            assertEquals(100, p.percent);
            Map<String, Integer> unit = UNITS.get(p.name);
            if (unit == null) {
                atoms.merge(p.name, p.count, Integer::sum);
            } else {
                unit.forEach((a, n) -> atoms.merge(a, n * p.count, Integer::sum));
            }
        }
        return atoms;
    }

    /** 规格：以黑电气石 NaFe₃Al₆(Si₆O₁₈)(BO₃)₃(OH)₄ 核对，氧 18 + 9 + 4 = 31 */
    @Test
    void schorlFormula() {
        Map<String, Integer> a = atoms(Tourmaline.BLACK);
        assertEquals(1, a.get("sodium"));
        assertEquals(3, a.get("iron"));
        assertEquals(6, a.get("aluminum"));
        assertEquals(6, a.get("silicon"));
        assertEquals(31, a.get("oxygen"));
        assertEquals(3, a.get("boron"));
        assertEquals(4, a.get("hydrogen"));
        assertEquals(1, Tourmaline.BLACK.dissolverOutputs().size(), "黑电气石没有概率组");
    }

    /** 各颜色的特征元素 */
    @Test
    void colors() {
        for (Tourmaline t : new Tourmaline[]{Tourmaline.WHITE, Tourmaline.GREEN, Tourmaline.BLUE, Tourmaline.INDIGO}) {
            Map<String, Integer> a = atoms(t);
            assertEquals(2, a.get("lithium"), t.name());
            assertEquals(7, a.get("aluminum"), t.name());
            assertNull(a.get("iron"));
            assertEquals(1, t.dissolverOutputs().size());
        }
        for (Tourmaline t : new Tourmaline[]{Tourmaline.RED, Tourmaline.VIOLET}) {
            Map<String, Integer> a = atoms(t);
            assertEquals(2, a.get("lithium"), t.name());
            assertEquals(7, a.get("aluminum"), t.name());
            List<List<Tourmaline.Portion>> groups = t.dissolverOutputs();
            assertEquals(2, groups.size());
            assertEquals("manganese", groups.get(1).get(0).name);
            assertEquals(1, groups.get(1).get(0).count);
            assertEquals(Tourmaline.MANGANESE_PERCENT, groups.get(1).get(0).percent);
        }
        for (Tourmaline t : new Tourmaline[]{Tourmaline.YELLOW, Tourmaline.ORANGE}) {
            Map<String, Integer> a = atoms(t);
            assertEquals(3, a.get("magnesium"), t.name());
            assertEquals(6, a.get("aluminum"), t.name());
        }
    }

    /** 夸克的元数据：白 0 红 1 橙 2 黄 3 绿 4 青 5 蓝 6 紫 7 黑 8 */
    @Test
    void metas() {
        assertEquals(9, Tourmaline.values().length);
        for (int meta = 0; meta < 9; meta++) {
            assertEquals(meta, Tourmaline.byMeta(meta).meta);
        }
        assertSame(Tourmaline.BLACK, Tourmaline.byMeta(8));
        assertNull(Tourmaline.byMeta(9));
    }

    /** 渣拆出来的是不分颜色的结构单元：没有铝、没有特征元素 */
    @Test
    void slagUnits() {
        List<Tourmaline.Portion> units = Tourmaline.structuralUnits();
        assertEquals(4, units.size());
        assertEquals(Tourmaline.METASILICATE, units.get(0).name);
        assertEquals(6, units.get(0).count);
        assertEquals(Tourmaline.BORATE, units.get(1).name);
        assertEquals(3, units.get(1).count);
        assertEquals(Tourmaline.HYDROXIDE, units.get(2).name);
        assertEquals(4, units.get(2).count);
        assertEquals("sodium", units.get(3).name);
        assertEquals(1, units.get(3).count);
    }
}
