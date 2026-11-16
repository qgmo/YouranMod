package moe.qingu.youran.logic.litho;

/**
 * 光刻机多方块的结构（服主 11-16 定按设计稿“光刻机多方块.md”做）：7 宽 × 4 深 × 4 高，78 个方块。纯逻辑。
 *
 * <p>坐标照设计稿：{@code w} 0..6 站在正面看从左到右，{@code l} 0 是正面、3 是背面，{@code h} 0 是地面层。
 * 字母见 {@link Part#symbol}；{@code U} 是光源（不是多方块的一格，成形时原样保留），{@code .} 是空。
 */
public final class LithoStructure {
    public static final int HEIGHT = 4;
    public static final int LENGTH = 4;
    public static final int WIDTH = 7;

    /** [h][l]，每行 w0..w6；与设计稿 2.3 的逐层网格一致（那边每层从 l3 往 l0 排，这里从 l0 往 l3） */
    private static final String[][] GRID = {
            {"RPP.PII", "KKSHSII", "hKSSSId", "|DSS|S|"},
            {"HHY.YGG", "KKPAP.G", "KK....G", "D|SLS|S"},
            {"PPYYYPP", "KKPUPPP", "KKPPPPP", "DDSSSFF"},
            {".......", "...F...", ".......", "......."},
    };

    public static final char LIGHT_SOURCE = 'U';
    public static final char EMPTY = '.';

    /** 主方块（对准台）的位置 */
    public static final int MASTER_H = 1;
    public static final int MASTER_L = 1;
    public static final int MASTER_W = 3;

    /** 多方块里的方块种类：成形后的方块元数据就是 {@link #ordinal()}（16 种，正好放得进 4 位） */
    public enum Part {
        STEEL_SHEET('P'),
        IRON_SHEET('I'),
        SCAFFOLDING('S'),
        HEAVY('H'),
        LIGHT('L'),
        REDSTONE('R'),
        BLAST_BRICK('K'),
        RADIATOR('D'),
        PIPE('|'),
        YELLOW_GLASS('Y'),
        GLASS('G'),
        FENCE('F'),
        HOPPER('h'),
        DROPPER('d'),
        ALIGNER('A'),
        /** 输出口的输液管：结构里也是 {@code |}，成形后单独一种（拆开时同样放回输液管） */
        PIPE_OUT('|');

        public final char symbol;

        Part(char symbol) {
            this.symbol = symbol;
        }

        public static Part bySymbol(char c) {
            for (Part p : values()) {
                if (p.symbol == c) {
                    return p;
                }
            }
            return null;
        }

        public static Part byMeta(int meta) {
            Part[] v = values();
            return meta >= 0 && meta < v.length ? v[meta] : STEEL_SHEET;
        }

        /** 从这一格右键能不能开界面（设计稿：只从小间这几格，免得拿锤子、扳手干活时误开） */
        public boolean opensGui() {
            return this == ALIGNER || this == YELLOW_GLASS;
        }
    }

    /** 接口从哪一面接：背面、左端、右端（镜像时左右对调由调用方处理）或各面 */
    public enum Face {
        BACK, LEFT, RIGHT, ANY
    }

    /** 接口（设计稿 2.4） */
    public enum Port {
        STEAM_IN(1, 3, 1, Face.BACK, 0, true),
        RESIST_IN(1, 3, 5, Face.BACK, 1, true),
        SOLVENT_IN(0, 3, 4, Face.BACK, 2, true),
        ETCHANT_IN(0, 3, 6, Face.BACK, 3, true),
        FLUID_OUT(0, 3, 0, Face.BACK, 4, false),
        ITEM_IN(0, 2, 0, Face.LEFT, -1, true),
        ITEM_OUT(0, 2, 6, Face.RIGHT, -1, false),
        ENERGY(1, 3, 3, Face.ANY, -1, true),
        REDSTONE(0, 0, 0, Face.ANY, -1, true);

        public final int h;
        public final int l;
        public final int w;
        public final Face face;
        /** 流体口对应的罐（0 水蒸气、1 光刻胶、2 溶剂、3 刻蚀液、4 输出）；不是流体口时为 −1 */
        public final int tank;
        public final boolean input;

        Port(int h, int l, int w, Face face, int tank, boolean input) {
            this.h = h;
            this.l = l;
            this.w = w;
            this.face = face;
            this.tank = tank;
            this.input = input;
        }

        public static Port at(int h, int l, int w) {
            for (Port p : values()) {
                if (p.h == h && p.l == l && p.w == w) {
                    return p;
                }
            }
            return null;
        }

        public boolean isFluid() {
            return tank >= 0;
        }
    }

    private LithoStructure() {
    }

    /** 这一格的字母 */
    public static char at(int h, int l, int w) {
        if (h < 0 || h >= HEIGHT || l < 0 || l >= LENGTH || w < 0 || w >= WIDTH) {
            return EMPTY;
        }
        return GRID[h][l].charAt(w);
    }

    /** 这一格的方块种类；空格、光源格为 null */
    public static Part part(int h, int l, int w) {
        return Part.bySymbol(at(h, l, w));
    }

    /** 格编号（同沉浸工程：h × 深 × 宽 + l × 宽 + w） */
    public static int index(int h, int l, int w) {
        return h * LENGTH * WIDTH + l * WIDTH + w;
    }

    public static int hOf(int index) {
        return index / (LENGTH * WIDTH);
    }

    public static int lOf(int index) {
        return index % (LENGTH * WIDTH) / WIDTH;
    }

    public static int wOf(int index) {
        return index % WIDTH;
    }

    /** 结构里一共几个方块（含光源） */
    public static int blockCount() {
        int n = 0;
        for (int h = 0; h < HEIGHT; h++) {
            for (int l = 0; l < LENGTH; l++) {
                for (int w = 0; w < WIDTH; w++) {
                    if (at(h, l, w) != EMPTY) {
                        n++;
                    }
                }
            }
        }
        return n;
    }

    /** 某种字母有几格 */
    public static int count(char c) {
        int n = 0;
        for (int h = 0; h < HEIGHT; h++) {
            for (int l = 0; l < LENGTH; l++) {
                for (int w = 0; w < WIDTH; w++) {
                    if (at(h, l, w) == c) {
                        n++;
                    }
                }
            }
        }
        return n;
    }
}
