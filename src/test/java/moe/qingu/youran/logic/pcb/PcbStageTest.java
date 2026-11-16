package moe.qingu.youran.logic.pcb;

import org.junit.jupiter.api.Test;

import java.util.HashSet;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** 主板线各道工序的元数据（服主 11-16） */
class PcbStageTest {
    @Test
    void line() {
        for (BoardType t : BoardType.circuits()) {
            int exposed = PcbStage.afterExposure(PcbStage.COATED, t);
            assertSame(t, PcbStage.type(exposed));
            assertEquals("exposed", PcbStage.stageName(exposed));
            assertFalse(PcbStage.spoiled(exposed));
            int etched = PcbStage.afterEtching(exposed);
            assertEquals(PcbStage.etched(t), etched);
            assertEquals("etched", PcbStage.stageName(etched));
            assertSame(t, PcbStage.type(PcbStage.punched(t)));
            assertEquals("punched", PcbStage.stageName(PcbStage.punched(t)));
        }
    }

    /** 变质胶：照样曝光，蚀刻出来是报废主板 */
    @Test
    void spoiledResistScrapsAtEtching() {
        int exposed = PcbStage.afterExposure(PcbStage.COATED_SPOILED, BoardType.PC);
        assertTrue(PcbStage.spoiled(exposed));
        assertTrue(PcbStage.spoiled(PcbStage.COATED_SPOILED));
        assertSame(BoardType.PC, PcbStage.type(exposed));
        assertEquals(PcbStage.SCRAP, PcbStage.afterEtching(exposed));
    }

    @Test
    void wrongInputs() {
        assertEquals(-1, PcbStage.afterExposure(PcbStage.BLANK, BoardType.COMPUTER));
        assertEquals(-1, PcbStage.afterExposure(PcbStage.etched(BoardType.COMPUTER), BoardType.COMPUTER));
        assertEquals(-1, PcbStage.afterEtching(PcbStage.COATED));
        assertEquals(-1, PcbStage.afterEtching(PcbStage.punched(BoardType.HANZI)));
        assertNull(PcbStage.type(PcbStage.SCRAP));
        assertNull(PcbStage.stageName(5));
        assertNull(PcbStage.stageName(PcbStage.EXPOSED + 7));
    }

    @Test
    void allDistinct() {
        Set<Integer> seen = new HashSet<>();
        for (int m : PcbStage.all()) {
            assertTrue(seen.add(m));
            assertTrue(PcbStage.stageName(m) != null, "没有名字：" + m);
        }
        assertEquals(4 + 4 * BoardType.circuits().length, seen.size());
    }

    /** 家具电脑主板没有自己的线路（服主 11-16：不为它加底片）：用个人电脑的光板，光板、底片的元数据里没有它 */
    @Test
    void furnitureHasNoCircuitOfItsOwn() {
        assertSame(BoardType.PC, BoardType.FURNITURE.blank());
        assertFalse(java.util.Arrays.asList(BoardType.circuits()).contains(BoardType.FURNITURE));
        assertEquals(java.util.Arrays.asList(BoardType.PC, BoardType.FURNITURE), BoardType.builtOn(BoardType.PC));
        assertEquals(java.util.Collections.singletonList(BoardType.COMPUTER), BoardType.builtOn(BoardType.COMPUTER));
        assertNull(BoardType.circuitById(BoardType.FURNITURE.id));
        assertNull(PcbStage.type(PcbStage.PUNCHED + BoardType.FURNITURE.id));
        assertNull(PcbStage.stageName(PcbStage.ETCHED + BoardType.FURNITURE.id));
    }
}
