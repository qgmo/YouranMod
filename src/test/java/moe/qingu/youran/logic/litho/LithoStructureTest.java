package moe.qingu.youran.logic.litho;

import moe.qingu.youran.logic.litho.LithoStructure.Port;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** 结构照设计稿（光刻机多方块.md 2.3–2.5） */
class LithoStructureTest {
    @Test
    void blockCountsMatchDesign() {
        assertEquals(78, LithoStructure.blockCount());
        assertEquals(18, LithoStructure.count('P'));
        assertEquals(5, LithoStructure.count('I'));
        assertEquals(14, LithoStructure.count('S'));
        assertEquals(11, LithoStructure.count('K'));
        assertEquals(4, LithoStructure.count('D'));
        assertEquals(5, LithoStructure.count('|'));
        assertEquals(5, LithoStructure.count('Y'));
        assertEquals(4, LithoStructure.count('G'));
        assertEquals(3, LithoStructure.count('H'));
        assertEquals(3, LithoStructure.count('F'));
        for (char c : new char[]{'L', 'R', 'h', 'd', 'A', 'U'}) {
            assertEquals(1, LithoStructure.count(c), String.valueOf(c));
        }
    }

    @Test
    void portsSitOnTheirBlocks() {
        for (Port p : Port.values()) {
            char c = LithoStructure.at(p.h, p.l, p.w);
            if (p.isFluid()) {
                assertEquals('|', c, p.name());
            }
        }
        assertEquals('h', LithoStructure.at(Port.ITEM_IN.h, Port.ITEM_IN.l, Port.ITEM_IN.w));
        assertEquals('d', LithoStructure.at(Port.ITEM_OUT.h, Port.ITEM_OUT.l, Port.ITEM_OUT.w));
        assertEquals('L', LithoStructure.at(Port.ENERGY.h, Port.ENERGY.l, Port.ENERGY.w));
        assertEquals('R', LithoStructure.at(Port.REDSTONE.h, Port.REDSTONE.l, Port.REDSTONE.w));
        assertEquals('A', LithoStructure.at(LithoStructure.MASTER_H, LithoStructure.MASTER_L, LithoStructure.MASTER_W));
        assertEquals('U', LithoStructure.at(LithoStructure.MASTER_H + 1, LithoStructure.MASTER_L, LithoStructure.MASTER_W));
    }

    /** 两个流体口不能挨着（沉浸工程的输液管会连上相邻的输液管） */
    @Test
    void fluidPortsNeverTouch() {
        List<Port> fluids = new ArrayList<>();
        for (Port p : Port.values()) {
            if (p.isFluid()) {
                fluids.add(p);
            }
        }
        assertEquals(5, fluids.size());
        for (Port a : fluids) {
            for (Port b : fluids) {
                if (a != b) {
                    int d = Math.abs(a.h - b.h) + Math.abs(a.l - b.l) + Math.abs(a.w - b.w);
                    assertTrue(d > 1, a + " 挨着 " + b);
                }
            }
        }
    }

    /** “只用在接口”：输液管、轻型 / 红石工程块、漏斗、投掷器只出现在接口位置 */
    @Test
    void interfaceBlocksOnlyAtPorts() {
        for (int h = 0; h < LithoStructure.HEIGHT; h++) {
            for (int l = 0; l < LithoStructure.LENGTH; l++) {
                for (int w = 0; w < LithoStructure.WIDTH; w++) {
                    char c = LithoStructure.at(h, l, w);
                    if ("|LRhd".indexOf(c) >= 0) {
                        assertNotNull(Port.at(h, l, w), h + "," + l + "," + w);
                    }
                }
            }
        }
    }

    @Test
    void metaFitsInFourBits() {
        assertEquals(16, LithoStructure.Part.values().length);
        assertEquals(LithoStructure.Part.PIPE, LithoStructure.Part.bySymbol('|'));
        assertFalse(LithoStructure.Part.STEEL_SHEET.opensGui());
        assertTrue(LithoStructure.Part.ALIGNER.opensGui());
    }

    @Test
    void indexRoundTrip() {
        for (int h = 0; h < 4; h++) {
            for (int l = 0; l < 4; l++) {
                for (int w = 0; w < 7; w++) {
                    int i = LithoStructure.index(h, l, w);
                    assertEquals(h, LithoStructure.hOf(i));
                    assertEquals(l, LithoStructure.lOf(i));
                    assertEquals(w, LithoStructure.wOf(i));
                }
            }
        }
    }
}
