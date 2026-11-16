package moe.qingu.youran.logic.litho;

import moe.qingu.youran.logic.purity.WaferMold;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

/**
 * 激光管充气的判断（固体装罐机、流体 / 固体装罐机的“装罐”模式共用）：产物从原件的数据加一个元素算出来，到上限不做，别的元素不归它管。
 * 机器本身（Mixin 挂上去、格子收东西、元素留在输入格）要进游戏测，这里测不到。
 */
class GasFillTest {
    /** 一个有数据的激光腔：石英 4 个 9、氟 1、氖 10、氩 250 mB、已用 37 次 */
    static ExcimerSpec used() {
        return new ExcimerSpec(4, DuvNumbers.MILLI, 10, 250, 37);
    }

    @Test
    void cavityKeepsItsData() {
        ExcimerSpec s = used();
        GasFill.Plan<ExcimerSpec> f = GasFill.cavity(s, GasFill.FLUORINE);
        assertEquals(GasFill.Kind.FILL, f.kind);
        assertEquals(2.0, f.spec.fluorine(), 1e-9);
        assertEquals(10, f.spec.neon);
        assertEquals(250, f.spec.argonMb);
        assertEquals(37, f.spec.used);
        assertEquals(4, f.spec.nines);

        GasFill.Plan<ExcimerSpec> n = GasFill.cavity(s, GasFill.NEON);
        assertEquals(GasFill.Kind.FILL, n.kind);
        assertEquals(11, n.spec.neon);
        assertEquals(1.0, n.spec.fluorine(), 1e-9);
        assertEquals(37, n.spec.used);
    }

    /** 氟满 3、氖满 48 就不做（元素留在输入格），另一种还能放 */
    @Test
    void cavityFull() {
        ExcimerSpec s = new ExcimerSpec(1, DuvNumbers.FLUORINE_MAX * DuvNumbers.MILLI, DuvNumbers.NEON_MAX, 0, 0);
        assertEquals(GasFill.Kind.FULL, GasFill.cavity(s, GasFill.FLUORINE).kind);
        assertNull(GasFill.cavity(s, GasFill.FLUORINE).spec);
        assertEquals(GasFill.Kind.FULL, GasFill.cavity(s, GasFill.NEON).kind);
        ExcimerSpec fOnly = new ExcimerSpec(1, DuvNumbers.FLUORINE_MAX * DuvNumbers.MILLI, 0, 0, 0);
        assertEquals(GasFill.Kind.FULL, GasFill.cavity(fOnly, GasFill.FLUORINE).kind);
        assertEquals(GasFill.Kind.FILL, GasFill.cavity(fOnly, GasFill.NEON).kind);
        // 氟被消耗成小数后又能补
        ExcimerSpec worn = new ExcimerSpec(1, DuvNumbers.FLUORINE_MAX * DuvNumbers.MILLI - 2, 0, 0, 1);
        assertEquals(GasFill.Kind.FILL, GasFill.cavity(worn, GasFill.FLUORINE).kind);
    }

    /** 激光腔不收氦，氦氖管不收氟；不是元素（−1）都不归它管 */
    @Test
    void notMine() {
        assertEquals(GasFill.Kind.NOT_MINE, GasFill.cavity(used(), GasFill.HELIUM).kind);
        assertEquals(GasFill.Kind.NOT_MINE, GasFill.cavity(used(), -1).kind);
        HeNeSpec t = HeNeSpec.empty(LampGlass.GLASS, WaferMold.GOLD, 1);
        assertEquals(GasFill.Kind.NOT_MINE, GasFill.hene(t, GasFill.FLUORINE, 0).kind);
        assertEquals(GasFill.Kind.NOT_MINE, GasFill.hene(t, -1, 0).kind);
    }

    @Test
    void heneKeepsItsData() {
        HeNeSpec t = HeNeSpecTest.filled(LampGlass.GLASS, WaferMold.CLAY, 20, 2, 0);
        GasFill.Plan<HeNeSpec> he = GasFill.hene(t, GasFill.HELIUM, 0);
        assertEquals(GasFill.Kind.FILL, he.kind);
        assertEquals(21, he.spec.heliumAt(0), 1e-9);
        assertEquals(2, he.spec.neon);
        assertEquals(LampGlass.GLASS, he.spec.glass);
        assertEquals(WaferMold.CLAY, he.spec.mold);
        GasFill.Plan<HeNeSpec> ne = GasFill.hene(t, GasFill.NEON, 0);
        assertEquals(3, ne.spec.neon);
        assertEquals(20, ne.spec.heliumAt(0), 1e-9);
    }

    /** 合计 250 个就不做 */
    @Test
    void heneFull() {
        HeNeSpec t = HeNeSpecTest.filled(LampGlass.GLASS, WaferMold.GOLD, 228, 22, 0);
        assertEquals(DuvNumbers.HENE_MAX_ELEMENTS, t.elementsAt(0));
        assertEquals(GasFill.Kind.FULL, GasFill.hene(t, GasFill.HELIUM, 0).kind);
        assertEquals(GasFill.Kind.FULL, GasFill.hene(t, GasFill.NEON, 0).kind);
    }
}
