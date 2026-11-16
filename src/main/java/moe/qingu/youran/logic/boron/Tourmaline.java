package moe.qingu.youran.logic.boron;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;

/**
 * 夸克的洞穴水晶当作电气石（服主 11-15，硼的来源）：按颜色分四种，各自的特征元素不同。纯逻辑，不依赖 Minecraft。
 *
 * <p>电气石的结构单元（以黑电气石 NaFe₃Al₆(Si₆O₁₈)(BO₃)₃(OH)₄ 为例）：偏硅酸根 SiO₃ ×6（Si₆O₁₈ 环）、硼酸根 BO₃ ×3、
 * 氢氧根 OH ×4、钠 ×1、铝 ×6，再加特征元素。溶解机拆出来的就是这些单元（{@link #dissolverOutputs()}），不是散原子。
 *
 * <p>数量照规格的表，概率是 Claude 11-15 定的一版（见 {@link #MANGANESE_PERCENT}）。
 */
public enum Tourmaline {
    /** 白色洞穴水晶（夸克元数据 0）：锂电气石 */
    WHITE(0, Kind.ELBAITE),
    /** 红色（1）：红碧玺 */
    RED(1, Kind.RUBELLITE),
    /** 橙色（2）：镁电气石 */
    ORANGE(2, Kind.DRAVITE),
    /** 黄色（3）：镁电气石 */
    YELLOW(3, Kind.DRAVITE),
    /** 绿色（4）：锂电气石 */
    GREEN(4, Kind.ELBAITE),
    /** 青色（5，夸克英文 blue）：锂电气石 */
    BLUE(5, Kind.ELBAITE),
    /** 蓝色（6，夸克英文 indigo）：锂电气石 */
    INDIGO(6, Kind.ELBAITE),
    /** 紫色（7，夸克英文 violet，规格里的“蓝紫”）：红碧玺 */
    VIOLET(7, Kind.RUBELLITE),
    /** 黑色（8）：黑电气石 */
    BLACK(8, Kind.SCHORL);

    /** 种类 */
    public enum Kind {
        /** 黑电气石：铁 ×3 */
        SCHORL,
        /** 锂电气石：锂 ×2，铝再 +1 */
        ELBAITE,
        /** 红碧玺：锂 ×2，铝再 +1，锰少量 */
        RUBELLITE,
        /** 镁电气石：镁 ×3 */
        DRAVITE
    }

    /** 偏硅酸根（整合包脚本注册的炼金化学化合物 metasilicate，硅 1 氧 3） */
    public static final String METASILICATE = "metasilicate";
    /** 硼酸根（本模组注册的炼金化学化合物 borate，硼 1 氧 3） */
    public static final String BORATE = "borate";
    /** 氢氧根（炼金化学自带 hydroxide，氧 1 氢 1） */
    public static final String HYDROXIDE = "hydroxide";
    public static final int METASILICATE_COUNT = 6;
    public static final int BORATE_COUNT = 3;
    public static final int HYDROXIDE_COUNT = 4;
    public static final int SODIUM_COUNT = 1;
    public static final int ALUMINUM_COUNT = 6;
    /** 红碧玺的“锰少量”：溶解一个出 1 个锰的概率（百分数，Claude 定） */
    public static final int MANGANESE_PERCENT = 30;

    /** 溶解机的一组产物：炼金化学的元素或化合物名、个数、概率（百分数，100 = 必出） */
    public static final class Portion {
        public final String name;
        public final int count;
        public final int percent;

        public Portion(String name, int count, int percent) {
            this.name = name;
            this.count = count;
            this.percent = percent;
        }

        @Override
        public String toString() {
            return name + " ×" + count + (percent < 100 ? "（" + percent + "%）" : "");
        }
    }

    /** 夸克洞穴水晶的元数据 */
    public final int meta;
    public final Kind kind;

    Tourmaline(int meta, Kind kind) {
        this.meta = meta;
        this.kind = kind;
    }

    /** 元数据对应的颜色；没有时为 null */
    public static Tourmaline byMeta(int meta) {
        for (Tourmaline t : values()) {
            if (t.meta == meta) {
                return t;
            }
        }
        return null;
    }

    /** 铝的个数：锂电气石、红碧玺再 +1 */
    public int aluminum() {
        return kind == Kind.ELBAITE || kind == Kind.RUBELLITE ? ALUMINUM_COUNT + 1 : ALUMINUM_COUNT;
    }

    /**
     * 溶解机拆一个洞穴水晶的产物，按组列出：同一组一起出。第一组必出（结构单元 + 特征元素），
     * 红碧玺另有一组按 {@value #MANGANESE_PERCENT}% 出 1 个锰。
     */
    public List<List<Portion>> dissolverOutputs() {
        List<Portion> main = new ArrayList<>(structuralUnits());
        main.add(new Portion("aluminum", aluminum(), 100));
        switch (kind) {
            case SCHORL:
                main.add(new Portion("iron", 3, 100));
                break;
            case ELBAITE:
            case RUBELLITE:
                main.add(new Portion("lithium", 2, 100));
                break;
            case DRAVITE:
                main.add(new Portion("magnesium", 3, 100));
                break;
            default:
                break;
        }
        List<List<Portion>> groups = new ArrayList<>();
        groups.add(Collections.unmodifiableList(main));
        if (kind == Kind.RUBELLITE) {
            groups.add(Collections.singletonList(new Portion("manganese", 1, MANGANESE_PERCENT)));
        }
        return Collections.unmodifiableList(groups);
    }

    /**
     * 不分颜色的结构单元：偏硅酸根 ×6、硼酸根 ×3、氢氧根 ×4、钠 ×1。
     * 硼硅酸盐渣在溶解机里也拆成这些（热能离心机已经把金属分出去了）。
     */
    public static List<Portion> structuralUnits() {
        return Collections.unmodifiableList(Arrays.asList(
                new Portion(METASILICATE, METASILICATE_COUNT, 100),
                new Portion(BORATE, BORATE_COUNT, 100),
                new Portion(HYDROXIDE, HYDROXIDE_COUNT, 100),
                new Portion("sodium", SODIUM_COUNT, 100)));
    }
}
