package moe.qingu.youran.logic.litho;

import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;

class LithoStepTest {
    /** 两层：每层氧化 → 涂胶 → 对准曝光 → 显影 → 刻蚀，然后掺杂 → 金属化 → 去胶 → 切割 → 封装，再回到空闲 */
    @Test
    void twoLayerSequence() {
        List<String> seen = new ArrayList<>();
        LithoStep s = LithoStep.OXIDIZE;
        int layer = 0;
        int n = 0;
        while (s != LithoStep.IDLE) {
            n++;
            assertEquals(n, s.stepNumber(layer, 2));
            seen.add(s.name() + layer);
            LithoStep next = s.next(layer, 2);
            layer = s.nextLayer(layer, 2);
            s = next;
        }
        assertEquals(LithoStep.totalSteps(2), seen.size());
        assertEquals("[OXIDIZE0, COAT0, EXPOSE0, DEVELOP0, ETCH0, OXIDIZE1, COAT1, EXPOSE1, DEVELOP1, ETCH1, "
                + "DOPE1, METALLIZE1, STRIP1, DICE1, PACKAGE1]", seen.toString());
        assertEquals(0, layer);
    }

    @Test
    void stripIsIgnored() {
        assertEquals(0, LithoStep.STRIP.ticks);
        assertEquals(0, LithoStep.STRIP.fePerTick);
    }
}
