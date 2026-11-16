package moe.qingu.youran.litho;

/**
 * 光刻机声音的触发规则（服主 11-16）：什么状态变化响哪一声。纯逻辑，不碰世界、不碰客户端，单元测试直接测。
 * 服务端只在状态变化时发包：嗡嗡声的开关（{@link Hum}，随方块实体更新包同步）与一次性的提示（方块事件，只发给附近的玩家）。
 */
public final class LithoSoundCues {
    /** 方块事件编号（一次性的声音） */
    public static final int EVENT_STEP = 1;
    public static final int EVENT_ALIGNED = 2;
    public static final int EVENT_BATCH = 3;
    public static final int EVENT_FAULT = 4;

    /** 嗡嗡声的状态：0 关；1 开；2 开且叠准分子（DUV）的嗞嗞声 */
    public static final int HUM_OFF = 0;
    public static final int HUM_ON = 1;
    public static final int HUM_DUV = 2;

    /** 曝光中断多少 tick 才算停（电一抖、一层与一层之间的空档不让声音反复开关） */
    public static final int HUM_OFF_DELAY = 20;
    /** 同一台机器两次报警至少隔多少 tick */
    public static final int ALARM_COOLDOWN = 100;

    private LithoSoundCues() {
    }

    /**
     * 算得上“故障停机”的状态：缺原料、产物罐 / 输出格满了、光源或掩模坏了。
     * 不算：空闲 / 缺晶圆 / 缺掩模 / 没光源 / 没对准（等人处理，不是机器停了）、红石停机、取走晶圆（人为）、缺电（太阳能等会反复抖）、激光对准中（正常工序）。
     */
    public static boolean isFault(LithoStatus s) {
        switch (s) {
            case NEED_STEAM:
            case NEED_RESIST:
            case NEED_SOLVENT:
            case NEED_ETCHANT:
            case NEED_DOPANT:
            case NEED_ALUMINUM:
            case NEED_CASING:
            case NEED_WATER:
            case NEED_ALKALI:
            case OUTPUT_TANK_FULL:
            case OUTPUT_FULL:
            case LAMP_OUT:
            case NO_MERCURY:
            case LASER_OUT:
            case NO_LASER_GAS:
            case MASK_WORN:
                return true;
            default:
                return false;
        }
    }

    /** 状态从 {@code prev} 变成 {@code next} 时该不该报警：正在运转（RUNNING）时转成故障才响，开工前就缺东西不响 */
    public static boolean alarmOnTransition(LithoStatus prev, LithoStatus next) {
        return prev == LithoStatus.RUNNING && isFault(next);
    }

    /** 一次性声音的音高（相对声音事件的原音）：每格步进的咔嗒调高；对准完成与一批做完的提示音一低一高 */
    public static float pitch(int event) {
        switch (event) {
            case EVENT_STEP:
                return 1.8F;
            case EVENT_ALIGNED:
                return 1.0F;
            case EVENT_BATCH:
                return 1.5F;
            default:
                return 1.0F;
        }
    }

    /** 报警的冷却：{@link #ALARM_COOLDOWN} tick 内只响一次 */
    public static final class AlarmGate {
        private long last = Long.MIN_VALUE / 2;

        public boolean tryAlarm(LithoStatus prev, LithoStatus next, long now) {
            if (!alarmOnTransition(prev, next) || now - last < ALARM_COOLDOWN) {
                return false;
            }
            last = now;
            return true;
        }
    }

    /** 嗡嗡声状态机：每 tick 喂“是不是在曝光、光源是不是准分子”，返回要同步给客户端的模式；曝光断了要过 {@link #HUM_OFF_DELAY} tick 才关 */
    public static final class Hum {
        private int mode = HUM_OFF;
        private int offTicks;

        public int mode() {
            return mode;
        }

        public int update(boolean exposing, boolean excimer) {
            if (exposing) {
                offTicks = 0;
                mode = excimer ? HUM_DUV : HUM_ON;
            } else if (mode != HUM_OFF && ++offTicks >= HUM_OFF_DELAY) {
                mode = HUM_OFF;
                offTicks = 0;
            }
            return mode;
        }

        /** 结构拆了等：立刻关 */
        public void reset() {
            mode = HUM_OFF;
            offTicks = 0;
        }
    }
}
