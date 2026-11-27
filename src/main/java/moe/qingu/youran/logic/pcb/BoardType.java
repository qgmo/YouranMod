package moe.qingu.youran.logic.pcb;

import javax.annotation.Nullable;

/**
 * 线路板的种类（服主 11-16）：每种一张线路底片（固定型号，底片不消耗）。普通电脑、个人电脑各一种主板，汉卡一种扩展卡，个人电脑的 ISA 扩展卡一种（网卡等）。
 * {@link #id} 是元数据（底片、板子、主板都按它区分），定了不改，新种类往后加。
 * <p>
 * 家具电脑主板（{@link #FURNITURE}）没有自己的线路：用打好孔的个人电脑光板，只装一片 YR-2000（不另做底片、光板，
 * 见 {@link #blank}）。所以底片、光板各道工序只有 {@link #circuits()} 这几种。
 */
public enum BoardType {
    /** 普通电脑（SCP {@code scp:computer}，Z80）的主板 */
    COMPUTER(0, "computer", true, null),
    /** 个人电脑（SCP {@code scp:personal_computer}，8088）的主板 */
    PC(1, "pc", true, null),
    /** 汉卡（扩展卡，四片字库 ROM） */
    HANZI(2, "hanzi", false, null),
    /**
     * 家具电脑（{@code cfm:computer}，内置浏览器）的主板（服主 11-16）：打好孔的个人电脑光板 + 1 片 YR-2000，不插 ROM、内存。
     * 用个人电脑的光板（Claude 11-16 定）：家具电脑是个人电脑往后的样子，CPU 位就在那块板上；不为它加底片
     */
    FURNITURE(3, "furniture", true, PC),
    /**
     * ISA 扩展卡（2.3.1.0 第 1 期）：个人电脑的扩展卡光板，网卡与以后的 HTTPS 加密卡共用（计划附录 10：线路板按总线分，
     * 装哪种控制芯片就是哪种卡；{@code PcbStage} 每道工序只有 8 个种类位置）。现在只装网卡控制芯片，装出来是网卡 {@code nic_pc}
     */
    ISA(4, "isa", false, null);

    public final int id;
    public final String name;
    /** 主板（装芯片后做成主板）；不是的就是扩展卡 */
    public final boolean motherboard;
    /** 用哪种的打好孔的光板来装；null 为自己的（有自己的线路底片） */
    @Nullable
    private final BoardType blank;

    BoardType(int id, String name, boolean motherboard, @Nullable BoardType blank) {
        this.id = id;
        this.name = name;
        this.motherboard = motherboard;
        this.blank = blank;
    }

    /** 装芯片用的光板是哪种的（家具电脑主板是个人电脑的，别的是自己的） */
    public BoardType blank() {
        return blank == null ? this : blank;
    }

    /** 有自己的线路（底片、涂胶曝光蚀刻打孔的各道工序）的种类：普通电脑、个人电脑、汉卡、ISA 扩展卡 */
    public static BoardType[] circuits() {
        java.util.List<BoardType> l = new java.util.ArrayList<>();
        for (BoardType t : values()) {
            if (t.blank == null) {
                l.add(t);
            }
        }
        return l.toArray(new BoardType[0]);
    }

    /** 这种打好孔的光板能装成哪几种板子（个人电脑的光板：个人电脑主板、家具电脑主板） */
    public static java.util.List<BoardType> builtOn(BoardType blank) {
        java.util.List<BoardType> l = new java.util.ArrayList<>();
        for (BoardType t : values()) {
            if (t.blank() == blank) {
                l.add(t);
            }
        }
        return l;
    }

    /** 有自己线路的种类里，这个元数据的那种；没有为 null（底片、光板的元数据用） */
    @Nullable
    public static BoardType circuitById(int id) {
        BoardType t = byId(id);
        return t != null && t.blank == null ? t : null;
    }

    @Nullable
    public static BoardType byId(int id) {
        for (BoardType t : values()) {
            if (t.id == id) {
                return t;
            }
        }
        return null;
    }
}
