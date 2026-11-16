package moe.qingu.youran.logic.litho;

import javax.annotation.Nullable;

/**
 * 激光管充气的判断（服主 11-16）：IC2 固体装罐机、流体 / 固体装罐机的“装罐”模式每次放一个炼金化学元素。纯逻辑，两台机器共用
 * （{@code compat.ic2.GasFillHooks}）：
 * <ul>
 *     <li>ArF 激光腔收氟、氖，到上限（氟 3、氖 48）就不再做，元素留在输入格；</li>
 *     <li>氦氖激光管收氦、氖，合计到 250 个就不再做。</li>
 * </ul>
 * 产物一律从原件的数据加一个元素算出来，不用配方表里登记的那一件（那一件只给 JEI 看、让机器的格子收东西）。
 */
public final class GasFill {
    /** 炼金化学元素的元数据（= 原子序数） */
    public static final int HELIUM = 2;
    public static final int FLUORINE = 9;
    public static final int NEON = 10;

    private GasFill() {
    }

    /** 判断结果：{@link #NOT_MINE} 不是充气，照 IC2 原来的走；{@link #FULL} 是充气但满了，不做；{@link #FILL} 做，产物是 {@link Plan#spec} */
    public enum Kind {
        NOT_MINE, FULL, FILL
    }

    public static final class Plan<S> {
        public final Kind kind;
        /** {@link Kind#FILL} 时是加了一个元素以后的数据，其余为 null */
        @Nullable
        public final S spec;

        Plan(Kind kind, @Nullable S spec) {
            this.kind = kind;
            this.spec = spec;
        }
    }

    /** ArF 激光腔放一个元素 {@code z} */
    public static Plan<ExcimerSpec> cavity(ExcimerSpec s, int z) {
        if (z != FLUORINE && z != NEON) {
            return new Plan<>(Kind.NOT_MINE, null);
        }
        if (z == FLUORINE ? !s.fluorineRoom() : !s.neonRoom()) {
            return new Plan<>(Kind.FULL, null);
        }
        return new Plan<>(Kind.FILL, z == FLUORINE ? s.plusFluorine() : s.plusNeon());
    }

    /** 氦氖激光管在世界时刻 {@code now} 放一个元素 {@code z}（氦会慢慢漏，按这一刻算） */
    public static Plan<HeNeSpec> hene(HeNeSpec s, int z, long now) {
        if (z != HELIUM && z != NEON) {
            return new Plan<>(Kind.NOT_MINE, null);
        }
        if (!s.hasRoom(now)) {
            return new Plan<>(Kind.FULL, null);
        }
        return new Plan<>(Kind.FILL, z == HELIUM ? s.plusHelium(now) : s.plusNeon(now));
    }
}
