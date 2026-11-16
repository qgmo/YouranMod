package moe.qingu.youran.litho;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** 光刻机声音的触发规则：状态转移响哪一声、DUV 的嗞嗞声只给准分子、嗡嗡声开关的去抖 */
class LithoSoundCuesTest {
    @Test
    void faultsAreResourceAndLightFailures() {
        for (LithoStatus s : new LithoStatus[]{LithoStatus.NEED_WATER, LithoStatus.NEED_ALKALI, LithoStatus.NO_LASER_GAS,
                LithoStatus.LASER_OUT, LithoStatus.LAMP_OUT, LithoStatus.NO_MERCURY, LithoStatus.NEED_STEAM,
                LithoStatus.OUTPUT_FULL, LithoStatus.MASK_WORN}) {
            assertTrue(LithoSoundCues.isFault(s), s.name());
        }
        for (LithoStatus s : new LithoStatus[]{LithoStatus.IDLE, LithoStatus.RUNNING, LithoStatus.NO_WAFER, LithoStatus.NO_MASK,
                LithoStatus.NOT_ALIGNED, LithoStatus.REDSTONE, LithoStatus.NO_POWER, LithoStatus.WAFER_REMOVED,
                LithoStatus.LASER_ALIGNING, LithoStatus.NO_LIGHT}) {
            assertFalse(LithoSoundCues.isFault(s), s.name());
        }
    }

    /** 每个状态都要明确归类：新加的状态要在 isFault 里表态（这里至少保证不会抛、RUNNING 到自己不响） */
    @Test
    void alarmOnlyWhenRunningTurnsFaulty() {
        for (LithoStatus next : LithoStatus.values()) {
            assertEquals(LithoSoundCues.isFault(next), LithoSoundCues.alarmOnTransition(LithoStatus.RUNNING, next), next.name());
            assertFalse(LithoSoundCues.alarmOnTransition(LithoStatus.IDLE, next), "开工前缺东西不响 " + next);
            assertFalse(LithoSoundCues.alarmOnTransition(LithoStatus.NO_WAFER, next), next.name());
            assertFalse(LithoSoundCues.alarmOnTransition(next, next == LithoStatus.RUNNING ? LithoStatus.IDLE : next), next.name());
        }
    }

    @Test
    void alarmFiresOnceThenCoolsDown() {
        LithoSoundCues.AlarmGate gate = new LithoSoundCues.AlarmGate();
        assertTrue(gate.tryAlarm(LithoStatus.RUNNING, LithoStatus.NEED_WATER, 1000));
        assertFalse(gate.tryAlarm(LithoStatus.RUNNING, LithoStatus.NEED_ALKALI, 1000 + LithoSoundCues.ALARM_COOLDOWN - 1));
        assertTrue(gate.tryAlarm(LithoStatus.RUNNING, LithoStatus.NEED_ALKALI, 1000 + LithoSoundCues.ALARM_COOLDOWN));
        assertFalse(gate.tryAlarm(LithoStatus.RUNNING, LithoStatus.NO_POWER, 5000), "缺电不报警，也不占冷却");
        assertFalse(gate.tryAlarm(LithoStatus.NEED_WATER, LithoStatus.NEED_WATER, 9000), "故障持续不重复响");
    }

    @Test
    void duvLayerOnlyForExcimer() {
        LithoSoundCues.Hum h = new LithoSoundCues.Hum();
        assertEquals(LithoSoundCues.HUM_OFF, h.mode());
        assertEquals(LithoSoundCues.HUM_ON, h.update(true, false));
        assertEquals(LithoSoundCues.HUM_DUV, h.update(true, true));
        assertEquals(LithoSoundCues.HUM_ON, h.update(true, false), "换回汞灯就不再叠");
    }

    @Test
    void humStopsOnlyAfterDelay() {
        LithoSoundCues.Hum h = new LithoSoundCues.Hum();
        h.update(true, true);
        for (int i = 1; i < LithoSoundCues.HUM_OFF_DELAY; i++) {
            assertEquals(LithoSoundCues.HUM_DUV, h.update(false, true), "第 " + i + " tick 不该关");
        }
        assertEquals(LithoSoundCues.HUM_OFF, h.update(false, true));
        assertEquals(LithoSoundCues.HUM_OFF, h.update(false, false));
        // 中途又曝光，计时重来
        h.update(true, false);
        h.update(false, false);
        h.update(true, false);
        for (int i = 1; i < LithoSoundCues.HUM_OFF_DELAY; i++) {
            h.update(false, false);
        }
        assertEquals(LithoSoundCues.HUM_ON, h.mode());
        h.reset();
        assertEquals(LithoSoundCues.HUM_OFF, h.mode());
    }

    @Test
    void oneShotPitches() {
        assertTrue(LithoSoundCues.pitch(LithoSoundCues.EVENT_STEP) > 1.5F, "步进的咔嗒调高");
        assertTrue(LithoSoundCues.pitch(LithoSoundCues.EVENT_BATCH) > LithoSoundCues.pitch(LithoSoundCues.EVENT_ALIGNED));
    }
}
