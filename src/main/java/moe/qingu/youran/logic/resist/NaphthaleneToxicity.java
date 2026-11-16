package moe.qingu.youran.logic.resist;

/**
 * 萘的毒性（服主 11-16）：物品栏（含快捷栏、副手；盔甲栏不算）里所有萘加起来超过 {@link ResistNumbers#CARRY_FREE} 个，
 * 每秒给一次反胃。时长 = 3 秒 + 超出部分每 8 个加 1 秒；等级 = 超出部分每 64 个加一级，最多 IV（放大等级 3）。不做升华。
 */
public final class NaphthaleneToxicity {
    private NaphthaleneToxicity() {
    }

    /** 超出免费个数的部分；没超出时为 0 */
    public static int excess(int total) {
        return Math.max(0, total - ResistNumbers.CARRY_FREE);
    }

    /** 带这么多个萘会不会反胃：超过 32 个才会（正好 32 个不会） */
    public static boolean poisons(int total) {
        return total > ResistNumbers.CARRY_FREE;
    }

    /** 反胃时长（秒）；不反胃时为 0 */
    public static int seconds(int total) {
        if (!poisons(total)) {
            return 0;
        }
        return ResistNumbers.NAUSEA_BASE_SECONDS + excess(total) / ResistNumbers.NAUSEA_SECONDS_STEP;
    }

    /** 反胃的放大等级（0 = I 级，3 = IV 级封顶）；不反胃时为 −1 */
    public static int amplifier(int total) {
        if (!poisons(total)) {
            return -1;
        }
        return Math.min(ResistNumbers.NAUSEA_MAX_AMPLIFIER, excess(total) / ResistNumbers.NAUSEA_LEVEL_STEP);
    }
}
