package moe.qingu.youran.api.pcb;

import moe.qingu.youran.logic.litho.ChipModel;
import moe.qingu.youran.logic.pcb.BoardType;
import net.minecraft.nbt.NBTTagCompound;
import net.minecraft.nbt.NBTTagList;

import javax.annotation.Nullable;
import java.util.List;

/**
 * 装了芯片的板子（主板、汉卡）的数据（服主 11-16：芯片品质数据都带进主板，再带进电脑方块，供以后“芯片品质接进电脑”用）。
 * 在物品 NBT 的 {@value #TAG} 里：
 * <pre>
 * {Type:"computer",
 *  Cpu:{芯片 NBT},                         // CPU（普通电脑 Z80、个人电脑 8088）
 *  Rom:{芯片 NBT…, Content:{RomContent}},   // ROM：掩模 ROM 或 EPROM（EPROM 另有 Eprom:1b、Window:{玻璃管}）
 *  Ram:[{芯片 NBT}, …],                     // 4164：普通电脑 6 颗、个人电脑 20 颗
 *  Font:[{芯片 NBT} ×4]}                    // 汉卡：字库 ROM 1–4
 * </pre>
 * 芯片 NBT 同光刻机出来的芯片（{@code item.ChipNbt}，带 {@code Model}）。
 */
public final class BoardNbt {
    public static final String TAG = "Board";
    public static final String TYPE = "Type";
    public static final String CPU = "Cpu";
    public static final String ROM = "Rom";
    public static final String ROM_CONTENT = "Content";
    public static final String ROM_EPROM = "Eprom";
    public static final String ROM_WINDOW = "Window";
    public static final String RAM = "Ram";
    public static final String FONT = "Font";

    private BoardNbt() {
    }

    /** 这种板子要的 CPU；不要 CPU 时 null */
    @Nullable
    public static ChipModel cpu(BoardType t) {
        return t == BoardType.COMPUTER ? ChipModel.Z80 : t == BoardType.PC ? ChipModel.I8088 : null;
    }

    /** 要几颗 4164（服主 11-16：普通电脑 6 颗 = 48 KB，个人电脑简化成 20 颗） */
    public static int ramCount(BoardType t) {
        return t == BoardType.COMPUTER ? 6 : t == BoardType.PC ? 20 : 0;
    }

    /** 这种主板的 ROM 槽认的掩模 ROM（EPROM 也认）；没有 ROM 槽时 null */
    @Nullable
    public static ChipModel romMask(BoardType t) {
        return t == BoardType.COMPUTER ? ChipModel.ROM_MONITOR : t == BoardType.PC ? ChipModel.ROM_BIOS : null;
    }

    /** 汉卡要的四片字库 ROM（别的板子不要） */
    public static ChipModel[] fonts(BoardType t) {
        return t == BoardType.HANZI ? new ChipModel[]{ChipModel.FONT_ROM_1, ChipModel.FONT_ROM_2, ChipModel.FONT_ROM_3, ChipModel.FONT_ROM_4}
                : new ChipModel[0];
    }

    /** 正版 ROM 的内容 id（同掩模 ROM 型号 id）：普通电脑监控程序、个人电脑 BIOS */
    @Nullable
    public static String genuineRom(BoardType t) {
        ChipModel m = romMask(t);
        return m == null ? null : m.id;
    }

    /** 空板子的数据（打好孔的光板） */
    public static NBTTagCompound empty(BoardType t) {
        NBTTagCompound b = new NBTTagCompound();
        b.setString(TYPE, t.name);
        return b;
    }

    @Nullable
    public static BoardType type(NBTTagCompound board) {
        for (BoardType t : BoardType.values()) {
            if (t.name.equals(board.getString(TYPE))) {
                return t;
            }
        }
        return null;
    }

    public static boolean hasCpu(NBTTagCompound b) {
        return b.hasKey(CPU, 10);
    }

    public static boolean hasRom(NBTTagCompound b) {
        return b.hasKey(ROM, 10);
    }

    public static int ram(NBTTagCompound b) {
        return b.getTagList(RAM, 10).tagCount();
    }

    public static int fontCount(NBTTagCompound b) {
        return b.getTagList(FONT, 10).tagCount();
    }

    /** ROM 的内容（{@link RomContent}）；没装 ROM 或装的是空白 EPROM 时为 null */
    @Nullable
    public static NBTTagCompound romContent(NBTTagCompound b) {
        if (!hasRom(b)) {
            return null;
        }
        NBTTagCompound rom = b.getCompoundTag(ROM);
        return rom.hasKey(ROM_CONTENT, 10) ? rom.getCompoundTag(ROM_CONTENT) : null;
    }

    /** 芯片都装齐了 */
    public static boolean complete(NBTTagCompound b) {
        BoardType t = type(b);
        if (t == null) {
            return false;
        }
        if (t == BoardType.HANZI) {
            return fontCount(b) == fonts(t).length;
        }
        return hasCpu(b) && hasRom(b) && ram(b) == ramCount(t);
    }

    /** 一片要装的芯片：型号、芯片 NBT；ROM 另带内容（掩模 ROM 是那种掩模，EPROM 是烧进去的） */
    public static final class Part {
        public final ChipModel model;
        public final NBTTagCompound chip;
        /** 只有 EPROM：内容（空白为 null）、小窗 */
        @Nullable
        public final NBTTagCompound epromContent;
        @Nullable
        public final NBTTagCompound window;
        public final boolean eprom;

        public Part(ChipModel model, NBTTagCompound chip) {
            this(model, chip, false, null, null);
        }

        public Part(ChipModel model, NBTTagCompound chip, boolean eprom, @Nullable NBTTagCompound epromContent, @Nullable NBTTagCompound window) {
            this.model = model;
            this.chip = chip;
            this.eprom = eprom;
            this.epromContent = epromContent;
            this.window = window;
        }
    }

    /**
     * 往板子上装这几片（一次装的；装配台一次一套）。装不下（这个槽已经有了、内存超了、型号不对）时返回 null，板子不变。
     * 返回装好以后的新数据（不改 {@code board}）。
     */
    @Nullable
    public static NBTTagCompound install(NBTTagCompound board, List<Part> parts) {
        BoardType t = type(board);
        if (t == null || parts.isEmpty()) {
            return null;
        }
        NBTTagCompound b = board.copy();
        for (Part p : parts) {
            if (p.model == cpu(t) && p.model != null) {
                if (hasCpu(b)) {
                    return null;
                }
                b.setTag(CPU, p.chip.copy());
            } else if (p.model == ChipModel.DRAM_4164 && ramCount(t) > 0) {
                if (ram(b) >= ramCount(t)) {
                    return null;
                }
                NBTTagList l = b.getTagList(RAM, 10);
                l.appendTag(p.chip.copy());
                b.setTag(RAM, l);
            } else if (romMask(t) != null && (p.model == romMask(t) || p.eprom)) {
                if (hasRom(b)) {
                    return null;
                }
                NBTTagCompound rom = p.chip.copy();
                if (p.eprom) {
                    rom.setBoolean(ROM_EPROM, true);
                    if (p.window != null) {
                        rom.setTag(ROM_WINDOW, p.window.copy());
                    }
                    if (p.epromContent != null) {
                        rom.setTag(ROM_CONTENT, p.epromContent.copy());
                    }
                } else {
                    rom.setTag(ROM_CONTENT, RomContent.mask(p.model.id));
                }
                b.setTag(ROM, rom);
            } else if (java.util.Arrays.asList(fonts(t)).contains(p.model)) {
                NBTTagList l = b.getTagList(FONT, 10);
                for (int i = 0; i < l.tagCount(); i++) {
                    if (p.model.id.equals(l.getCompoundTagAt(i).getString(ChipModel.TAG))) {
                        return null;
                    }
                }
                NBTTagCompound c = p.chip.copy();
                c.setString(ChipModel.TAG, p.model.id);
                l.appendTag(c);
                b.setTag(FONT, l);
            } else {
                return null;
            }
        }
        return b;
    }
}
