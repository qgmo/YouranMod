package moe.qingu.youran.logic.pcb;

import moe.qingu.youran.logic.litho.LampGlass;
import moe.qingu.youran.logic.purity.WaferMold;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** 烧录速度只看小窗的玻璃管（服主 11-16）：石英比玻璃快、几个 9 越多越快（有下限），金铸模比粘土快 */
class EpromBurnTest {
    @Test
    void windowGrades() {
        int clay = EpromBurn.ticksPerKb(LampGlass.GLASS, WaferMold.CLAY, 1);
        int gold = EpromBurn.ticksPerKb(LampGlass.GLASS, WaferMold.GOLD, 1);
        int q1 = EpromBurn.ticksPerKb(LampGlass.QUARTZ, null, 1);
        int q3 = EpromBurn.ticksPerKb(LampGlass.QUARTZ, null, 3);
        int q30 = EpromBurn.ticksPerKb(LampGlass.QUARTZ, null, 30);
        assertTrue(clay > gold && gold > q1 && q1 > q3 && q3 >= q30);
        assertEquals(PcbNumbers.BURN_TICKS_PER_KB_MIN, q30);
        assertEquals(256, q3);
    }

    @Test
    void progress() {
        int perKb = 400;
        assertEquals(3200, EpromBurn.totalTicks(perKb, 8192));
        assertEquals(0, EpromBurn.bytesDone(perKb, 8192, 0));
        assertEquals(4096, EpromBurn.bytesDone(perKb, 8192, 1600));
        assertEquals(8192, EpromBurn.bytesDone(perKb, 8192, 3200));
        assertEquals(8192, EpromBurn.bytesDone(perKb, 8192, 99999));
        assertEquals(1, EpromBurn.totalTicks(perKb, 0));
    }
}
