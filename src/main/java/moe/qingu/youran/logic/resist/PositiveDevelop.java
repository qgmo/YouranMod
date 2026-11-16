package moe.qingu.youran.logic.resist;

import moe.qingu.youran.logic.litho.ResistType;

import javax.annotation.Nullable;

/**
 * 正胶显影（服主 11-16）：光刻机显影时，这一层涂的是正胶（及以后同类的胶）就用碱液：溶剂口进水，物品口吃碱；
 * 每层 {@link ResistNumbers#DEVELOP_WATER} mB 水，同时吃掉 {@link ResistNumbers#DEVELOP_HYDROXIDE} 个氢氧根
 * 或 {@link ResistNumbers#DEVELOP_CARBONATE} 个碳酸钾（炼金化学化合物）。负胶照旧用溶剂。
 * 缺水或缺碱时这一步停下等，不报废；碱液显影不改显影溶剂的品质（不拉低指标）。
 */
public final class PositiveDevelop {
    /** 能当显影用水的流体：原版水、IC2 蒸馏水、本模组矿泉水、意志坚定的纯净水（有哪个算哪个） */
    public static final String[] WATERS = {"water", "ic2distilled_water", "fuild_mineral_water", "purified_water"};

    /** 用哪种碱 */
    public enum Alkali {
        HYDROXIDE(ResistNumbers.DEVELOP_HYDROXIDE),
        CARBONATE(ResistNumbers.DEVELOP_CARBONATE);

        /** 每层吃多少个 */
        public final int perLayer;

        Alkali(int perLayer) {
            this.perLayer = perLayer;
        }
    }

    /** 一层显影缺什么 */
    public enum Missing {
        NOTHING,
        WATER,
        ALKALI
    }

    private PositiveDevelop() {
    }

    /** 这一层涂的胶要不要碱液显影：负胶（和还没记下种类的）用溶剂，其余（正胶、以后的改良正胶）用碱液 */
    public static boolean usesAlkali(@Nullable ResistType type) {
        return type != null && type != ResistType.NEGATIVE;
    }

    /** 这种流体（注册名）能不能当显影用水 */
    public static boolean isWater(String fluidName) {
        for (String w : WATERS) {
            if (w.equals(fluidName)) {
                return true;
            }
        }
        return false;
    }

    /** 先用氢氧根（够 2 个），不够再用碳酸钾（够 1 个）；都不够为 null */
    @Nullable
    public static Alkali choose(int hydroxide, int carbonate) {
        if (hydroxide >= Alkali.HYDROXIDE.perLayer) {
            return Alkali.HYDROXIDE;
        }
        if (carbonate >= Alkali.CARBONATE.perLayer) {
            return Alkali.CARBONATE;
        }
        return null;
    }

    /** 这一层够不够料：水 {@code waterMb}（只算是水的）、两种碱各有几个；水先查 */
    public static Missing check(int waterMb, int hydroxide, int carbonate) {
        if (waterMb < ResistNumbers.DEVELOP_WATER) {
            return Missing.WATER;
        }
        return choose(hydroxide, carbonate) == null ? Missing.ALKALI : Missing.NOTHING;
    }
}
