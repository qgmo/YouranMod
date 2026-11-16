package moe.qingu.youran.logic.pcb;

import javax.annotation.Nullable;

/**
 * 线路板的种类（服主 11-16）：每种一张线路底片（固定型号，底片不消耗）。普通电脑、个人电脑各一种主板，汉卡一种扩展卡。
 * {@link #id} 是元数据（底片、板子、主板都按它区分），定了不改，新种类往后加。
 */
public enum BoardType {
    /** 普通电脑（SCP {@code scp:computer}，Z80）的主板 */
    COMPUTER(0, "computer", true),
    /** 个人电脑（SCP {@code scp:personal_computer}，8088）的主板 */
    PC(1, "pc", true),
    /** 汉卡（扩展卡，四片字库 ROM） */
    HANZI(2, "hanzi", false);

    public final int id;
    public final String name;
    /** 主板（装芯片后做成主板）；不是的就是扩展卡 */
    public final boolean motherboard;

    BoardType(int id, String name, boolean motherboard) {
        this.id = id;
        this.name = name;
        this.motherboard = motherboard;
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
