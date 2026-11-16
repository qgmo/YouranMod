package moe.qingu.youran.logic.pcb;

import javax.annotation.Nullable;

/**
 * 线路板（物品 {@code youranmod:pcb}）的各道工序，编在元数据里（服主 11-16 的主板线）：
 * <pre>
 * 0  空白主板（覆铜板：酚醛基板 + 铜板）
 * 1  涂胶主板（注了 10 mB 光刻胶）          2  涂胶主板（涂的是变质的光刻胶）
 * 3  报废主板（手工打孔打坏、变质胶蚀刻不出线路）
 * 16 + 种类  曝光后的主板（紫外擦除器里涂胶主板 + 底片）   24 + 种类  同上，涂的是变质胶
 * 32 + 种类  光板（三氯化铁蚀刻出线路，没孔、没零件）
 * 48 + 种类  打好孔的光板（手工螺丝刀或冲床冲孔模具）
 * </pre>
 * 种类见 {@link BoardType}。变质胶（已经感光、整片交联）照样能曝光，但蚀刻时整块铜都被保护着，出不来线路，蚀刻出来就是报废主板。
 * 纯逻辑，测试见 {@code PcbStageTest}。
 */
public final class PcbStage {
    public static final int BLANK = 0;
    public static final int COATED = 1;
    public static final int COATED_SPOILED = 2;
    public static final int SCRAP = 3;
    public static final int EXPOSED = 16;
    public static final int EXPOSED_SPOILED = 24;
    public static final int ETCHED = 32;
    public static final int PUNCHED = 48;

    private PcbStage() {
    }

    public static int exposed(BoardType t, boolean spoiled) {
        return (spoiled ? EXPOSED_SPOILED : EXPOSED) + t.id;
    }

    public static int etched(BoardType t) {
        return ETCHED + t.id;
    }

    public static int punched(BoardType t) {
        return PUNCHED + t.id;
    }

    /** 这道工序的名字（翻译键 {@code item.pcb.<名字>.name}）；不认得的元数据为 null */
    @Nullable
    public static String stageName(int meta) {
        switch (meta) {
            case BLANK:
                return "blank";
            case COATED:
            case COATED_SPOILED:
                return "coated";
            case SCRAP:
                return "scrap";
            default:
                break;
        }
        if (type(meta) == null) {
            return null;
        }
        int base = meta & ~7;
        return base == EXPOSED || base == EXPOSED_SPOILED ? "exposed" : base == ETCHED ? "etched" : base == PUNCHED ? "punched" : null;
    }

    /** 带种类的工序（曝光后、光板、打好孔）的种类；别的为 null */
    @Nullable
    public static BoardType type(int meta) {
        int base = meta & ~7;
        if (base != EXPOSED && base != EXPOSED_SPOILED && base != ETCHED && base != PUNCHED) {
            return null;
        }
        return BoardType.byId(meta & 7);
    }

    /** 涂的是变质胶（涂胶后、曝光后两道） */
    public static boolean spoiled(int meta) {
        return meta == COATED_SPOILED || (meta & ~7) == EXPOSED_SPOILED && type(meta) != null;
    }

    /** 涂胶主板 + 这种底片曝光后的元数据；不是涂胶主板时 -1 */
    public static int afterExposure(int meta, BoardType film) {
        return meta == COATED ? exposed(film, false) : meta == COATED_SPOILED ? exposed(film, true) : -1;
    }

    /** 曝光后的主板蚀刻后的元数据（变质胶的出报废主板）；不是曝光后的主板时 -1 */
    public static int afterEtching(int meta) {
        BoardType t = type(meta);
        if (t == null) {
            return -1;
        }
        int base = meta & ~7;
        return base == EXPOSED ? etched(t) : base == EXPOSED_SPOILED ? SCRAP : -1;
    }

    /** 全部用得到的元数据（创造物品栏、模型） */
    public static int[] all() {
        java.util.List<Integer> l = new java.util.ArrayList<>(java.util.Arrays.asList(BLANK, COATED, COATED_SPOILED, SCRAP));
        for (int base : new int[]{EXPOSED, EXPOSED_SPOILED, ETCHED, PUNCHED}) {
            for (BoardType t : BoardType.values()) {
                l.add(base + t.id);
            }
        }
        int[] out = new int[l.size()];
        for (int i = 0; i < out.length; i++) {
            out[i] = l.get(i);
        }
        return out;
    }
}
