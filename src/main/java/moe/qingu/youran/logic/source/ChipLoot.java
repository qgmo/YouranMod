package moe.qingu.youran.logic.source;

import moe.qingu.youran.logic.litho.ChipModel;

import javax.annotation.Nullable;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Random;

/**
 * 失落的城市建筑箱子里刷的掩模、芯片设计图（服主 11-16）：每个箱子按 {@code chance} 出一件（默认 1%，期望开 100 个箱子出 1 件），
 * 出了以后按 {@code maskShare} 分掩模、设计图（默认各一半），型号从全部型号里等概率抽（以后加的型号自动算进去）。纯逻辑。
 */
public final class ChipLoot {
    /** 出的是什么 */
    public enum Kind {
        MASK, DESIGN
    }

    /** 一个箱子里多出的那一件 */
    public static final class Drop {
        public final Kind kind;
        public final ChipModel model;

        public Drop(Kind kind, ChipModel model) {
            this.kind = kind;
            this.model = model;
        }

        @Override
        public String toString() {
            return kind + "/" + model.id;
        }
    }

    /** 全部型号（{@link ChipModel} 的全部值） */
    public static final List<ChipModel> MODELS = Collections.unmodifiableList(Arrays.asList(ChipModel.values()));

    private ChipLoot() {
    }

    /** 概率收进 0–1（NaN 当 0） */
    public static double clamp01(double p) {
        return p > 0 ? Math.min(1, p) : 0;
    }

    /**
     * 一个箱子：按 {@code chance} 出一件，出了按 {@code maskShare} 是掩模、否则设计图，型号在 {@code models} 里等概率抽。
     * 没出时为 null。随机数依次用三次（出不出、哪种、哪个型号），没出时只用一次。
     */
    @Nullable
    public static Drop roll(Random random, double chance, double maskShare, List<ChipModel> models) {
        if (models.isEmpty() || !(random.nextDouble() < clamp01(chance))) {
            return null;
        }
        Kind kind = random.nextDouble() < clamp01(maskShare) ? Kind.MASK : Kind.DESIGN;
        return new Drop(kind, models.get(random.nextInt(models.size())));
    }

    @Nullable
    public static Drop roll(Random random, double chance, double maskShare) {
        return roll(random, chance, maskShare, MODELS);
    }

    /** 一张战利品表加不加、怎么加 */
    public enum Target {
        /** 不加 */
        NONE,
        /** 加，哪个维度都刷（失落的城市建筑专用的表） */
        ANY_DIMENSION,
        /** 加，只在失落的城市的维度里刷（失落的城市少量建筑箱子借用的原版表） */
        LOST_CITY_ONLY
    }

    /**
     * 按配置（{@code buildingTables}、{@code vanillaTables}）看这张表：先看建筑专用的，再看原版的。表名不分大小写、去掉首尾空白，
     * 没写命名空间的当 {@code minecraft}。
     */
    public static Target target(String table, String[] buildingTables, String[] vanillaTables) {
        String t = normalize(table);
        if (t.isEmpty()) {
            return Target.NONE;
        }
        if (listed(t, buildingTables)) {
            return Target.ANY_DIMENSION;
        }
        return listed(t, vanillaTables) ? Target.LOST_CITY_ONLY : Target.NONE;
    }

    /** 维度号在不在名单里 */
    public static boolean inDimensions(int dim, int[] dims) {
        if (dims != null) {
            for (int d : dims) {
                if (d == dim) {
                    return true;
                }
            }
        }
        return false;
    }

    private static boolean listed(String t, String[] names) {
        if (names != null) {
            for (String n : names) {
                if (t.equals(normalize(n))) {
                    return true;
                }
            }
        }
        return false;
    }

    static String normalize(String name) {
        if (name == null) {
            return "";
        }
        String n = name.trim().toLowerCase(java.util.Locale.ROOT);
        return n.isEmpty() || n.indexOf(':') >= 0 ? n : "minecraft:" + n;
    }
}
