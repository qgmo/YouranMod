package moe.qingu.youran.logic.litho;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * 成形后光刻机的整机外形（照沉浸工程多方块的“整机模型”）：一组长方体，模型与碰撞箱都从这里来。纯逻辑，不引用 Minecraft 的类。
 *
 * <p>坐标是结构坐标、单位像素（一格 16）：{@code x} 沿 {@code w} 往右（站在正面看），{@code y} 往上，{@code z} 沿 {@code l}
 * 从正面往背面。整机 112 × 64 × 64（第 3 层只有排气管）。
 *
 * <p>每个长方体的每个面按格切开（贴图按格平铺，看不出格子），切出来的每一块交给它所在的那格方块画（光照取那一格的）；
 * 落在空格、光源格里的（小间里伸出来的目镜、湿法台里的槽、排气管根部）交给附近的一格画（{@link #host}）。
 * 碰撞箱 = 实心的长方体按格切开，只留在有方块的格里（空格本来就能走进去）。
 */
public final class LithoShape {
    public static final int PX = 16;
    public static final int SIZE_X = LithoStructure.WIDTH * PX;
    public static final int SIZE_Y = LithoStructure.HEIGHT * PX;
    public static final int SIZE_Z = LithoStructure.LENGTH * PX;

    /** 结构坐标里的六个面（{@code axis}：0 = x、1 = y、2 = z） */
    public enum Side {
        DOWN(1, -1), UP(1, 1), FRONT(2, -1), BACK(2, 1), LEFT(0, -1), RIGHT(0, 1);

        public final int axis;
        public final int sign;

        Side(int axis, int sign) {
            this.axis = axis;
            this.sign = sign;
        }
    }

    /** 渲染层 */
    public enum Layer {
        SOLID, CUTOUT, TRANSLUCENT
    }

    /** 一个面的贴图：贴图名 + 铺法（{@code period > 0} 按结构坐标平铺，周期这么多像素；0 = 整面拉伸）+ 用贴图的哪一块（0–16） */
    public static final class Tex {
        public final String sprite;
        public final int period;
        public final float u0;
        public final float v0;
        public final float u1;
        public final float v1;

        Tex(String sprite, int period, float u0, float v0, float u1, float v1) {
            this.sprite = sprite;
            this.period = period;
            this.u0 = u0;
            this.v0 = v0;
            this.u1 = u1;
            this.v1 = v1;
        }
    }

    /** 一个长方体（结构坐标，像素） */
    public static final class Box {
        public final float[] min;
        public final float[] max;
        final Tex[] tex = new Tex[6];
        public Layer layer = Layer.SOLID;
        /** 有碰撞（小零件、玻璃里的灯管等没有） */
        public boolean solid = true;
        /** 指定由哪一格画（格编号）；−1 = 照默认规则 */
        int host = -1;

        Box(float x0, float y0, float z0, float x1, float y1, float z1) {
            min = new float[]{x0, y0, z0};
            max = new float[]{x1, y1, z1};
        }

        public Tex tex(Side side) {
            return tex[side.ordinal()];
        }

        Box all(Tex t) {
            for (Side s : Side.values()) {
                tex[s.ordinal()] = t;
            }
            return this;
        }

        Box sides(Tex t) {
            return face(t, Side.FRONT, Side.BACK, Side.LEFT, Side.RIGHT);
        }

        Box face(Tex t, Side... sides) {
            for (Side s : sides) {
                tex[s.ordinal()] = t;
            }
            return this;
        }

        Box front(Tex t) {
            return face(t, Side.FRONT);
        }

        Box back(Tex t) {
            return face(t, Side.BACK);
        }

        Box left(Tex t) {
            return face(t, Side.LEFT);
        }

        Box right(Tex t) {
            return face(t, Side.RIGHT);
        }

        Box top(Tex t) {
            return face(t, Side.UP);
        }

        Box bottom(Tex t) {
            return face(t, Side.DOWN);
        }

        Box no(Side... sides) {
            return face(null, sides);
        }

        Box deco() {
            solid = false;
            return this;
        }

        Box cutout() {
            layer = Layer.CUTOUT;
            return this;
        }

        Box glass() {
            layer = Layer.TRANSLUCENT;
            return this;
        }

        Box host(int h, int l, int w) {
            host = LithoStructure.index(h, l, w);
            return this;
        }
    }

    /** 切好的一块面：属于哪个长方体、哪一面、范围（结构坐标，像素；法线方向那一维 min = max） */
    public static final class Piece {
        public final Box box;
        public final Side side;
        public final float[] min;
        public final float[] max;

        Piece(Box box, Side side, float[] min, float[] max) {
            this.box = box;
            this.side = side;
            this.min = min;
            this.max = max;
        }

        public Tex tex() {
            return box.tex(side);
        }

        public Layer layer() {
            return box.layer;
        }
    }

    // ---- 贴图 ----

    static final String IE = "immersiveengineering:blocks/";
    static final String OWN = "youranmod:blocks/litho/";

    static Tex tile(String sprite) {
        return new Tex(sprite, PX, 0, 0, 16, 16);
    }

    static Tex fit(String sprite) {
        return new Tex(sprite, 0, 0, 0, 16, 16);
    }

    static Tex fit(String sprite, float u0, float v0, float u1, float v1) {
        return new Tex(sprite, 0, u0, v0, u1, v1);
    }

    // 沉浸工程已经注册的贴图（引用资源路径，不复制文件）
    static final Tex BRICK = tile(IE + "stone_decoration_blastbrick_reinforced");
    static final Tex RADIATOR = tile(IE + "metal_decoration0_radiator");
    static final Tex SCAFFOLD = tile(IE + "metal_decoration1_steel_scaffolding_noctm");
    /** 沉浸工程多方块的电口：炼油厂贴图（128 × 128）右下角那一格 16 × 16（同铁板配一圈圈凹进去的方框、正中橙色接线点） */
    static final Tex IE_POWER = fit(IE + "metal_multiblock_refinery", 10, 14, 12, 16);
    /**
     * 沉浸工程多方块的红石口：炼油厂贴图右下角 x 64–80、y 112–128 那一格（亮灰板、正中暗红接线点）。
     * 红石口那一块长方体是 x 1–16、y 3–16（外框占了格子左边 1 像素和下面 3 像素），按这个范围取图块里对应的部分。
     */
    static final Tex IE_REDSTONE = fit(IE + "metal_multiblock_refinery", 8.125f, 14, 10, 15.625f);
    // 自己画的
    static final Tex FRAME = tile(OWN + "frame");
    static final Tex PANEL = tile(OWN + "panel");
    static final Tex PANEL_LIGHT = tile(OWN + "panel_light");
    static final Tex ROOF = tile(OWN + "roof");
    static final Tex VENT = tile(OWN + "vent");
    static final Tex GAUGE = fit(OWN + "gauge");
    static final Tex DOOR = tile(OWN + "door");
    static final Tex HAZARD = tile(OWN + "hazard");
    static final Tex GLASS_YELLOW = tile(OWN + "glass_yellow");
    static final Tex GLASS_CLEAR = tile(OWN + "glass_clear");
    static final Tex TUBE_YELLOW = tile(OWN + "tube_yellow");
    static final Tex DESK_FRONT = tile(OWN + "desk_front");
    static final Tex DESK_TOP = tile(OWN + "desk_top");
    static final Tex BENCH = tile(OWN + "bench");
    static final Tex BENCH_FRONT = tile(OWN + "bench_front");
    static final Tex HOOD_FRONT = tile(OWN + "hood_front");
    static final Tex HOOD_LIGHT = tile(OWN + "hood_light");
    static final Tex GRILLE = tile(OWN + "grille");
    static final Tex DUCT = tile(OWN + "duct");
    static final Tex PORT_IN = fit(OWN + "port_in");
    static final Tex PORT_OUT = fit(OWN + "port_out");
    static final Tex HATCH_IN = fit(OWN + "hatch_in");
    static final Tex HATCH_OUT = fit(OWN + "hatch_out");
    static final Tex MASK_RACK = fit(OWN + "mask_rack");
    static final Tex CONSOLE = fit(OWN + "console");
    static final Tex SCREEN = fit(OWN + "screen");
    static final Tex STAGE = tile(OWN + "stage");
    static final Tex STAGE_TOP = fit(OWN + "stage_top");
    static final Tex WAFER = fit(OWN + "wafer");
    static final Tex MASK = fit(OWN + "mask");
    static final Tex KNOB = tile(OWN + "knob");
    static final Tex MICRO = tile(OWN + "micro");
    static final Tex LENS = tile(OWN + "lens");
    static final Tex TANK = tile(OWN + "tank");
    static final Tex TANK_DEV = fit(OWN + "tank_dev");
    static final Tex TANK_ETCH = fit(OWN + "tank_etch");
    static final Tex TANK_RINSE = fit(OWN + "tank_rinse");
    static final Tex SPINNER = fit(OWN + "spinner");
    static final Tex CASSETTE = fit(OWN + "cassette");

    // ---- 外形 ----

    private static final List<Box> BOXES = new ArrayList<>();

    private static Box box(float x0, float y0, float z0, float x1, float y1, float z1) {
        Box b = new Box(x0, y0, z0, x1, y1, z1);
        BOXES.add(b);
        return b;
    }

    static {
        frame();
        plinth();
        furnace();
        booth();
        aligner();
        backOfBooth();
        wetBench();
        exhaust();
    }

    /** 底座：炉段、湿法段、小间后面的踢脚空腔（离地 3 像素高，门洞与小间里能看进去）填上，只画面不挡人 */
    private static void plinth() {
        box(2, 0, 2, 32, 3, 62).all(PANEL).deco();
        box(80, 0, 2, 110, 3, 62).all(PANEL).deco();
        box(32, 0, 48, 80, 3, 62).all(PANEL).deco();
    }

    /** 外框：底边、顶边、竖柱（贴着外轮廓，面板都往里缩 1 像素，看起来是一台机器的骨架） */
    private static void frame() {
        // 底边（门洞那一格不放，进出不绊脚）
        box(0, 0, 0, 48, 3, 2).all(FRAME);
        box(64, 0, 0, 112, 3, 2).all(FRAME);
        box(0, 0, 62, 112, 3, 64).all(FRAME);
        box(0, 0, 2, 2, 3, 62).all(FRAME);
        box(110, 0, 2, 112, 3, 62).all(FRAME);
        // 顶边
        box(0, 46, 0, 112, 48, 2).all(FRAME);
        box(0, 46, 62, 112, 48, 64).all(FRAME);
        box(0, 46, 2, 2, 48, 62).all(FRAME);
        box(110, 46, 2, 112, 48, 62).all(FRAME);
        // 正面、背面的竖柱：两端与分段处
        for (float x : new float[]{0, 31, 79, 110}) {
            box(x, 3, 0, x + 2, 46, 2).all(FRAME);
            box(x, 3, 62, x + 2, 46, 64).all(FRAME);
        }
        // 左端、右端中间的竖柱
        box(0, 3, 15, 2, 46, 17).all(FRAME);
        box(0, 3, 47, 2, 46, 49).all(FRAME);
        box(110, 3, 47, 112, 46, 49).all(FRAME);
    }

    /** 炉段（w0–w1）：正面炉门、控制箱，中间强化高炉砖炉膛，背面散热器（冷凝器）和两个流体口 */
    private static void furnace() {
        // 正面左下：红石口（控制面板，正面、左面都画沉浸工程的红石工程块）
        box(1, 3, 1, 16, 16, 16).front(IE_REDSTONE).left(IE_REDSTONE).top(PANEL).right(PANEL);
        // 正面右下：仪表
        box(16, 3, 1, 31, 16, 16).front(GAUGE).top(PANEL).right(PANEL);
        // 第 1 层：两扇炉门（黄黑警示条的门框）
        box(1, 16, 2, 31, 32, 16).front(DOOR).left(PANEL).right(PANEL);
        box(1, 16, 1, 31, 18, 2).all(HAZARD);
        box(1, 30, 1, 31, 32, 2).all(HAZARD);
        box(15, 18, 1, 17, 30, 2).all(HAZARD);
        box(1, 18, 1, 2, 30, 2).all(HAZARD);
        box(30, 18, 1, 31, 30, 2).all(HAZARD);
        box(11, 21, 0, 13, 27, 2).all(FRAME).deco();
        box(19, 21, 0, 21, 27, 2).all(FRAME).deco();
        // 第 2 层：散热百叶
        box(1, 32, 1, 31, 46, 16).front(VENT).left(PANEL).right(PANEL).top(ROOF);
        // 炉膛
        box(1, 3, 16, 32, 46, 48).left(BRICK).top(BRICK).right(PANEL).front(PANEL);
        box(2, 46, 17, 31, 47, 47).all(ROOF);
        // 左端进料口（漏斗，蓝 = 入）
        box(0, 4, 34, 2, 14, 46).left(HATCH_IN).face(FRAME, Side.UP, Side.DOWN, Side.FRONT, Side.BACK);
        box(0, 14, 33, 3, 16, 47).all(FRAME);
        // 背面：散热器（冷凝器），蒸汽 / 蒸馏水出口（橙）、水蒸气入口（蓝）
        box(1, 3, 48, 32, 46, 63).back(RADIATOR).left(RADIATOR).top(ROOF);
        box(3, 4, 61, 13, 14, 64).back(PORT_OUT).face(FRAME, Side.UP, Side.DOWN, Side.LEFT, Side.RIGHT);
        box(19, 19, 61, 29, 29, 64).back(PORT_IN).face(FRAME, Side.UP, Side.DOWN, Side.LEFT, Side.RIGHT);
    }

    /** 黄光小间（w2–w4 正面）：踢脚板、黄玻璃、门洞、顶上的黄光灯箱 */
    private static void booth() {
        // 门框与门楣
        box(46, 0, 0, 48, 32, 2).all(FRAME);
        box(64, 0, 0, 66, 32, 2).all(FRAME);
        box(33, 32, 0, 79, 34, 2).all(FRAME);
        // 正面下半是钢板，上半是黄玻璃
        box(33, 3, 0.5f, 46, 14, 2).all(PANEL).top(FRAME);
        box(66, 3, 0.5f, 79, 14, 2).all(PANEL).top(FRAME);
        box(33, 14, 0.75f, 46, 32, 1.25f).all(GLASS_YELLOW).glass();
        box(66, 14, 0.75f, 79, 32, 1.25f).all(GLASS_YELLOW).glass();
        // 右墙（隔着湿法台）
        box(78, 3, 2, 80, 14, 16).all(PANEL).top(FRAME);
        box(78.75f, 14, 2, 79.25f, 34, 16).all(GLASS_YELLOW).glass();
        // 顶上的黄光灯箱：黄玻璃罩着两根黄灯管
        box(33, 34, 1, 79, 46, 16).all(GLASS_YELLOW).glass();
        box(36, 38, 4, 76, 40, 6).all(TUBE_YELLOW).deco();
        box(36, 38, 10, 76, 40, 12).all(TUBE_YELLOW).deco();
        // 工作台（对准台那一排）：柜体、台面、背板、右边隔板
        box(32, 0, 17, 80, 16, 32).front(DESK_FRONT).top(DESK_TOP);
        box(32, 16, 16, 80, 18, 32).top(DESK_TOP).front(FRAME).bottom(FRAME);
        box(32, 18, 31, 79, 32, 32).front(PANEL_LIGHT).top(FRAME);
        box(79, 16, 16, 80, 32, 48).left(PANEL_LIGHT).right(BENCH).front(FRAME);
        // 左：掩模架；右：操作台与屏幕
        box(34, 18, 22, 46, 30, 30).front(MASK_RACK).sides(PANEL).front(MASK_RACK).top(PANEL);
        box(66, 18, 18, 78, 23, 30).back(PANEL).front(CONSOLE).top(fit(OWN + "console", 0, 0, 16, 6)).left(PANEL).right(PANEL);
        box(67, 23, 24, 77, 30, 30).back(PANEL).front(SCREEN).left(PANEL).right(PANEL).top(PANEL);
        // 小间顶棚两侧（中间是光源）
        box(32, 32, 16, 48, 46, 32).front(PANEL).bottom(PANEL_LIGHT).top(ROOF).right(PANEL);
        box(64, 32, 16, 80, 46, 32).front(PANEL).bottom(PANEL_LIGHT).top(ROOF).left(PANEL);
    }

    /** 对准台（主方块那格）：载物台、晶圆、掩模架、分视场显微镜 */
    private static void aligner() {
        box(50, 18, 19, 62, 20, 31).all(STAGE);
        box(51, 20, 20, 61, 21, 30).sides(STAGE).top(STAGE_TOP);
        box(53, 21, 22, 59, 21.5f, 28).all(WAFER).deco();
        // 掩模架：四根立柱 + 一圈框 + 掩模
        for (float[] p : new float[][]{{50, 20}, {61, 20}, {50, 29}, {61, 29}}) {
            box(p[0], 20, p[1], p[0] + 1, 23.5f, p[1] + 1).all(FRAME).deco();
        }
        box(50, 23.5f, 20, 62, 24.5f, 21).all(FRAME).deco();
        box(50, 23.5f, 29, 62, 24.5f, 30).all(FRAME).deco();
        box(50, 23.5f, 21, 51, 24.5f, 29).all(FRAME).deco();
        box(61, 23.5f, 21, 62, 24.5f, 29).all(FRAME).deco();
        box(51, 23.75f, 21, 61, 24.25f, 29).all(MASK).deco();
        // 旋钮
        box(48.5f, 18.5f, 23, 50, 20.5f, 25).all(KNOB).deco();
        box(62, 18.5f, 23, 63.5f, 20.5f, 25).all(KNOB).deco();
        box(53, 18.5f, 17.5f, 55, 20.5f, 19).all(KNOB).deco();
        box(57, 18.5f, 17.5f, 59, 20.5f, 19).all(KNOB).deco();
        // 显微镜：右边的立柱、悬臂上的镜头座、两个物镜、两个目镜（伸向站在小间里的人）。
        // 镜头座偏右、贴着掩模前沿：让开正中，光源的光束从灯口直照到掩模（见 LightSourceRenderer），站在小间左半边抬头能看见灯口
        box(60, 20, 17, 62, 29, 20).all(MICRO);
        box(55, 25.5f, 16, 62, 28.5f, 20.5f).all(MICRO);
        box(56, 24.5f, 17.5f, 58, 25.5f, 19.5f).all(LENS).deco();
        box(59, 24.5f, 17.5f, 61, 25.5f, 19.5f).all(LENS).deco();
        box(56, 26.5f, 13, 58, 28.5f, 16).all(LENS).deco().host(1, 1, 3);
        box(59, 26.5f, 13, 61, 28.5f, 16).all(LENS).deco().host(1, 1, 3);
    }

    /** 小间后面（w2–w4 的 l2、l3）：灯电源、能量口、溶剂入口，背面是脚手架格栅 */
    private static void backOfBooth() {
        box(32, 0, 32, 80, 16, 48).top(PANEL);
        box(32, 32, 32, 80, 46, 48).top(ROOF).bottom(PANEL).front(PANEL);
        box(32, 3, 48, 80, 46, 62).back(PANEL).top(ROOF);
        box(32, 3, 62, 80, 46, 63).back(SCAFFOLD).cutout();
        // 能量口（沉浸工程多方块电口的面）、溶剂入口
        // 电口：和沉浸工程多方块一样，整格一块与外壳齐平的面板（不凸出）
        box(48, 16, 62, 64, 32, 64).all(FRAME).back(IE_POWER);
        box(67, 4, 61, 77, 14, 64).back(PORT_IN).face(FRAME, Side.UP, Side.DOWN, Side.LEFT, Side.RIGHT);
    }

    /** 湿法段（w5–w6）：白色湿法台、台上的槽、玻璃挡板、排风罩，背面排风格栅和两个流体口，右端出芯片 */
    private static void wetBench() {
        box(80, 3, 1, 111, 14, 48).front(BENCH_FRONT).right(BENCH_FRONT).left(BENCH_FRONT);
        box(80, 14, 0.5f, 111.5f, 16, 48).top(BENCH).front(BENCH).right(BENCH).left(BENCH).bottom(BENCH_FRONT);
        // 右端出芯片口（投掷器，橙 = 出）
        box(110, 4, 34, 112, 14, 46).right(HATCH_OUT).face(FRAME, Side.UP, Side.DOWN, Side.FRONT, Side.BACK);
        // 台上：涂胶的甩胶盘、显影槽、刻蚀槽、清洗槽、晶圆花篮
        box(83, 16, 5, 93, 19, 15).sides(TANK).top(SPINNER).deco();
        box(96, 16, 5, 108, 20, 17).sides(TANK).top(TANK_DEV).deco();
        box(96, 16, 22, 108, 20, 34).sides(TANK).top(TANK_ETCH).deco();
        box(83, 16, 22, 93, 19, 34).sides(TANK).top(TANK_RINSE).deco();
        box(85, 16, 38, 93, 22, 46).all(CASSETTE).deco();
        // 玻璃挡板：正面一整块（中间一根竖框、一根拉手），右端两块
        box(80, 16, 0, 110, 18, 2).all(FRAME);
        box(80, 18, 0.75f, 110, 32, 1.25f).all(GLASS_CLEAR).glass();
        box(95, 18, 0.5f, 97, 32, 1.5f).all(FRAME);
        box(84, 22, 0, 107, 23, 0.75f).all(FRAME).deco();
        box(110, 16, 2, 112, 18, 47).all(FRAME);
        box(110.75f, 18, 2, 111.25f, 32, 47).all(GLASS_CLEAR).glass();
        // 排风罩
        box(80, 32, 1, 111, 46, 48).front(HOOD_FRONT).left(PANEL).right(PANEL).top(ROOF).bottom(HOOD_LIGHT);
        // 背面：下半面板 + 两个流体口，上半排风格栅
        box(80, 3, 48, 111, 32, 63).front(BENCH).back(PANEL).right(PANEL);
        box(80, 32, 48, 111, 46, 63).front(PANEL).back(GRILLE).right(PANEL).top(ROOF).bottom(PANEL);
        box(83, 19, 61, 93, 29, 64).back(PORT_IN).face(FRAME, Side.UP, Side.DOWN, Side.LEFT, Side.RIGHT);
        box(99, 4, 61, 109, 14, 64).back(PORT_IN).face(FRAME, Side.UP, Side.DOWN, Side.LEFT, Side.RIGHT);
    }

    /** 光源上面的排气管（第 3 层的钢栅栏那格） */
    private static void exhaust() {
        box(51, 46, 19, 61, 60, 29).sides(DUCT);
        box(49, 60, 17, 63, 62, 31).all(FRAME);
        box(52, 62, 20, 60, 63.5f, 28).all(FRAME);
    }

    // ---- 按格切 ----

    private static final int CELLS = LithoStructure.HEIGHT * LithoStructure.LENGTH * LithoStructure.WIDTH;
    private static final List<List<Piece>> PIECES = new ArrayList<>();
    private static final List<List<float[]>> COLLISION = new ArrayList<>();
    private static final List<List<float[]>> SELECTION = new ArrayList<>();

    static {
        for (int i = 0; i < CELLS; i++) {
            PIECES.add(new ArrayList<>());
            COLLISION.add(new ArrayList<>());
            SELECTION.add(new ArrayList<>());
        }
        List<List<float[]>> deco = new ArrayList<>();
        for (int i = 0; i < CELLS; i++) {
            deco.add(new ArrayList<>());
        }
        for (Box b : BOXES) {
            for (Side s : Side.values()) {
                if (b.tex(s) != null) {
                    cutFace(b, s);
                }
            }
            for (int h = 0; h < LithoStructure.HEIGHT; h++) {
                for (int l = 0; l < LithoStructure.LENGTH; l++) {
                    for (int w = 0; w < LithoStructure.WIDTH; w++) {
                        if (!hasBlock(h, l, w)) {
                            continue;
                        }
                        float[] c = clip(b, w * PX, h * PX, l * PX);
                        if (c != null) {
                            (b.solid ? COLLISION : deco).get(LithoStructure.index(h, l, w)).add(c);
                        }
                    }
                }
            }
        }
        for (int i = 0; i < CELLS; i++) {
            if (!COLLISION.get(i).isEmpty()) {
                SELECTION.get(i).addAll(COLLISION.get(i));
            } else if (!deco.get(i).isEmpty()) {
                SELECTION.get(i).addAll(deco.get(i));
            } else if (hasBlock(LithoStructure.hOf(i), LithoStructure.lOf(i), LithoStructure.wOf(i))) {
                SELECTION.get(i).add(new float[]{0, 0, 0, PX, PX, PX});
            }
        }
    }

    /** 这一格成形后是光刻机的方块（不是空格、不是光源） */
    public static boolean hasBlock(int h, int l, int w) {
        return LithoStructure.part(h, l, w) != null;
    }

    /** 长方体与 (cx, cy, cz) 起的那一格的交（格内坐标，像素）；没有体积时为 null */
    private static float[] clip(Box b, float cx, float cy, float cz) {
        float[] o = new float[6];
        float[] c = {cx, cy, cz};
        for (int a = 0; a < 3; a++) {
            float lo = Math.max(b.min[a], c[a]);
            float hi = Math.min(b.max[a], c[a] + PX);
            if (hi - lo <= 1e-4f) {
                return null;
            }
            o[a] = lo - c[a];
            o[a + 3] = hi - c[a];
        }
        return o;
    }

    private static void cutFace(Box b, Side s) {
        int a = s.axis;
        int t1 = (a + 1) % 3;
        int t2 = (a + 2) % 3;
        float plane = s.sign > 0 ? b.max[a] : b.min[a];
        for (float[] r1 : split(b.min[t1], b.max[t1])) {
            for (float[] r2 : split(b.min[t2], b.max[t2])) {
                float[] mn = new float[3];
                float[] mx = new float[3];
                mn[a] = mx[a] = plane;
                mn[t1] = r1[0];
                mx[t1] = r1[1];
                mn[t2] = r2[0];
                mx[t2] = r2[1];
                int[] cell = new int[3];
                cell[a] = (int) Math.floor((plane - s.sign * 0.01f) / PX);
                cell[t1] = (int) Math.floor((r1[0] + r1[1]) / 2 / PX);
                cell[t2] = (int) Math.floor((r2[0] + r2[1]) / 2 / PX);
                int host = hostFor(b, cell[1], cell[2], cell[0]);
                PIECES.get(host).add(new Piece(b, s, mn, mx));
            }
        }
    }

    /** 把 [lo, hi] 在 16 的整数倍处切开 */
    private static List<float[]> split(float lo, float hi) {
        List<float[]> out = new ArrayList<>();
        float start = lo;
        while (start < hi - 1e-4f) {
            float next = Math.min(hi, (float) (Math.floor(start / PX + 1e-4) + 1) * PX);
            out.add(new float[]{start, next});
            start = next;
        }
        return out;
    }

    /** 这一块面由哪一格画：所在格有方块就是它，否则按长方体指定的、或者就近找（先下、上，再后、前，再左、右） */
    private static int hostFor(Box b, int h, int l, int w) {
        if (inside(h, l, w) && hasBlock(h, l, w)) {
            return LithoStructure.index(h, l, w);
        }
        if (b.host >= 0) {
            return b.host;
        }
        int[][] dirs = {{-1, 0, 0}, {1, 0, 0}, {0, 1, 0}, {0, -1, 0}, {0, 0, -1}, {0, 0, 1}};
        for (int d = 1; d < 8; d++) {
            for (int[] v : dirs) {
                int hh = h + v[0] * d;
                int ll = l + v[1] * d;
                int ww = w + v[2] * d;
                if (inside(hh, ll, ww) && hasBlock(hh, ll, ww)) {
                    return LithoStructure.index(hh, ll, ww);
                }
            }
        }
        throw new IllegalStateException("找不到画这块面的格：" + h + "," + l + "," + w);
    }

    static boolean inside(int h, int l, int w) {
        return h >= 0 && h < LithoStructure.HEIGHT && l >= 0 && l < LithoStructure.LENGTH && w >= 0 && w < LithoStructure.WIDTH;
    }

    // ---- 对外 ----

    /** 全部长方体 */
    public static List<Box> boxes() {
        return Collections.unmodifiableList(BOXES);
    }

    /** 这一格要画的面（结构坐标，像素，整机坐标） */
    public static List<Piece> pieces(int index) {
        return Collections.unmodifiableList(PIECES.get(index));
    }

    /** 这一格的碰撞箱（格内坐标，像素，结构朝向：{x0, y0, z0, x1, y1, z1}） */
    public static List<float[]> collision(int index) {
        return Collections.unmodifiableList(COLLISION.get(index));
    }

    /** 这一格的选取框（同上）：有碰撞箱就是碰撞箱；只有小零件时是小零件；都没有时是整格 */
    public static List<float[]> selection(int index) {
        return Collections.unmodifiableList(SELECTION.get(index));
    }

    /** 用到的全部贴图 */
    public static Set<String> sprites() {
        Set<String> out = new LinkedHashSet<>();
        for (Box b : BOXES) {
            for (Tex t : b.tex) {
                if (t != null) {
                    out.add(t.sprite);
                }
            }
        }
        return out;
    }

    // ---- 朝向、镜像 ----

    /** 水平方向的单位向量，按 Minecraft 的水平编号（0 南 +z、1 西 −x、2 北 −z、3 东 +x） */
    static int[] dirVec(int horizontal) {
        switch (horizontal & 3) {
            case 0:
                return new int[]{0, 1};
            case 1:
                return new int[]{-1, 0};
            case 2:
                return new int[]{0, -1};
            default:
                return new int[]{1, 0};
        }
    }

    /** w 增加的方向的水平编号（站在正面看往右；镜像时往左） */
    public static int wDir(int facing, boolean mirrored) {
        return mirrored ? (facing + 3) & 3 : (facing + 1) & 3;
    }

    /**
     * 格内的一点（结构朝向，以格为单位，{@code a} 沿 w、{@code b} 往上、{@code c} 沿 l；这一格是 [0, 1]³）换到世界里这一格方块的坐标
     * （[0, 1]³ 对应方块本身）。{@code facing} 是从正面往背面的方向（水平编号）。
     */
    public static double[] toWorld(double a, double b, double c, int facing, boolean mirrored) {
        int[] wv = dirVec(wDir(facing, mirrored));
        int[] lv = dirVec(facing);
        double x = axis(wv[0], a) + axis(lv[0], c);
        double z = axis(wv[1], a) + axis(lv[1], c);
        return new double[]{x, b, z};
    }

    /** {@link #toWorld} 的逆 */
    public static double[] fromWorld(double x, double y, double z, int facing, boolean mirrored) {
        int[] wv = dirVec(wDir(facing, mirrored));
        int[] lv = dirVec(facing);
        double a = wv[0] != 0 ? axis(wv[0], x) : axis(wv[1], z);
        double c = lv[0] != 0 ? axis(lv[0], x) : axis(lv[1], z);
        return new double[]{a, y, c};
    }

    private static double axis(int d, double t) {
        return d > 0 ? t : d < 0 ? 1 - t : 0;
    }

    /** 格内的长方体（像素，结构朝向 {x0, y0, z0, x1, y1, z1}）换到世界里这一格方块的坐标（[0, 1]，同样的排法） */
    public static double[] boxToWorld(float[] box, int facing, boolean mirrored) {
        double[] p = toWorld(box[0] / PX, box[1] / PX, box[2] / PX, facing, mirrored);
        double[] q = toWorld(box[3] / PX, box[4] / PX, box[5] / PX, facing, mirrored);
        return new double[]{Math.min(p[0], q[0]), Math.min(p[1], q[1]), Math.min(p[2], q[2]),
                Math.max(p[0], q[0]), Math.max(p[1], q[1]), Math.max(p[2], q[2])};
    }

    /** 结构里的面在世界里朝哪（水平编号；上、下返回 −1、−2） */
    public static int worldSide(Side s, int facing, boolean mirrored) {
        switch (s) {
            case UP:
                return -1;
            case DOWN:
                return -2;
            case BACK:
                return facing & 3;
            case FRONT:
                return (facing + 2) & 3;
            case RIGHT:
                return wDir(facing, mirrored);
            default:
                return (wDir(facing, mirrored) + 2) & 3;
        }
    }

    /**
     * 一个顶点的贴图坐标（0–16，在 {@link Tex} 选的那一块里）。{@code p} 是顶点的结构坐标（像素），{@code piece} 是它所在的那块面
     * （平铺时按这块面所在的周期算，免得在周期边上从 16 跳回 0）。镜像时左右翻回来，贴图不会反着。
     */
    public static float[] uv(Piece piece, float[] p, boolean mirrored) {
        Side s = piece.side;
        Tex t = piece.tex();
        int ua;
        int va;
        int us;
        int vs;
        switch (s) {
            case FRONT: ua = 0; us = 1; va = 1; vs = -1; break;
            case BACK: ua = 0; us = -1; va = 1; vs = -1; break;
            case LEFT: ua = 2; us = 1; va = 1; vs = -1; break;
            case RIGHT: ua = 2; us = -1; va = 1; vs = -1; break;
            case UP: ua = 0; us = 1; va = 2; vs = 1; break;
            default: ua = 0; us = 1; va = 2; vs = -1; break;
        }
        if (mirrored) {
            us = -us;
        }
        float fu;
        float fv;
        if (t.period > 0) {
            fu = tileCoord(p[ua] * us, Math.min(piece.min[ua] * us, piece.max[ua] * us), t.period);
            fv = tileCoord(p[va] * vs, Math.min(piece.min[va] * vs, piece.max[va] * vs), t.period);
        } else {
            fu = fitCoord(p[ua], piece.box.min[ua], piece.box.max[ua], us);
            fv = fitCoord(p[va], piece.box.min[va], piece.box.max[va], vs);
        }
        return new float[]{t.u0 + fu * (t.u1 - t.u0), t.v0 + fv * (t.v1 - t.v0)};
    }

    /** 平铺：这块面最小的那个坐标所在的周期为基准，返回 0–1 */
    private static float tileCoord(float raw, float pieceMin, int period) {
        float base = (float) Math.floor(pieceMin / period + 1e-4) * period;
        return (raw - base) / period;
    }

    /** 拉伸：在整个面上的位置，返回 0–1 */
    private static float fitCoord(float v, float lo, float hi, int sign) {
        float f = (v - lo) / (hi - lo);
        return sign > 0 ? f : 1 - f;
    }

    private LithoShape() {
    }
}
