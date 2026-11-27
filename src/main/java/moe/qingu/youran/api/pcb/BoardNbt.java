package moe.qingu.youran.api.pcb;

import moe.qingu.youran.logic.litho.ChipModel;
import moe.qingu.youran.logic.pcb.BoardType;
import net.minecraft.nbt.NBTTagCompound;
import net.minecraft.nbt.NBTTagList;

import javax.annotation.Nullable;
import java.util.List;

/**
 * 装了芯片的板子（主板、汉卡、家具电脑主板）的数据（服主 11-16：芯片品质数据都带进主板，再带进电脑方块，供以后“芯片品质接进电脑”用）。
 * 在物品 NBT 的 {@value #TAG} 里：
 * <pre>
 * {Type:"computer",
 *  Cpu:{芯片 NBT},                         // CPU（普通电脑 Z80、个人电脑 8088、家具电脑 YR-2000）
 *  Rom:{芯片 NBT…, Content:{RomContent}},   // ROM：掩模 ROM 或 EPROM（EPROM 另有 Eprom:1b、Window:{玻璃管}）
 *  Ram:[{芯片 NBT}, …],                     // 4164：普通电脑 6 颗、个人电脑 20 颗
 *  Font:[{芯片 NBT} ×4],                   // 汉卡：字库 ROM 1–4
 *  Ctrl:{芯片 NBT}}                        // ISA 扩展卡：控制芯片（网卡控制芯片；装哪种就是哪种卡）
 * </pre>
 * 芯片 NBT 同光刻机出来的芯片（{@code item.ChipNbt}，带 {@code Model}）。
 * 家具电脑主板（{@code Type:"furniture"}，服主 11-16）只有 {@code Cpu}（YR-2000），没有 ROM、内存。
 * ISA 扩展卡（{@code Type:"isa"}，2.3.1.0 第 1 期）只有 {@code Ctrl}：网卡控制芯片（个人电脑网卡 {@code nic_pc}）或加密协处理器
 * （个人电脑加密卡 {@code tls_pc}，第 4 期）；卡的规格不存，由这颗芯片的品质现算（{@link NicSpec}、{@link TlsSpec}）。
 * Z80 扩展卡（{@code Type:"z80"}，第 5 期）：{@code Ctrl} 是网卡控制芯片时另有 {@code Rom}（驱动 ROM：普通电脑网卡驱动 ROM 或 2764 EPROM，
 * 内容同主板的 {@code Content}）——普通电脑网卡 {@code nic_computer}；{@code Ctrl} 是加密协处理器时没有 ROM——普通电脑加密卡 {@code tls_computer}。
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
    /** ISA 扩展卡的控制芯片 */
    public static final String CTRL = "Ctrl";

    private BoardNbt() {
    }

    /** 这种板子要的 CPU；不要 CPU 时 null */
    @Nullable
    public static ChipModel cpu(BoardType t) {
        return t == BoardType.COMPUTER ? ChipModel.Z80 : t == BoardType.PC ? ChipModel.I8088 : t == BoardType.FURNITURE ? ChipModel.YR2000 : null;
    }

    /** 要几颗 4164（服主 11-16：普通电脑 6 颗 = 48 KB，个人电脑简化成 20 颗；家具电脑不插） */
    public static int ramCount(BoardType t) {
        return t == BoardType.COMPUTER ? 6 : t == BoardType.PC ? 20 : 0;
    }

    /**
     * 这种板子的 ROM 座认的掩模 ROM（EPROM 也认）；没有 ROM 座（汉卡、家具电脑主板、ISA 扩展卡）时 null。Z80 扩展卡的 ROM 座是网卡的驱动 ROM
     * （2.3.1.0 第 5 期；只有装网卡控制芯片的才要，见 {@link #needsRom}）
     */
    @Nullable
    public static ChipModel romMask(BoardType t) {
        return t == BoardType.COMPUTER ? ChipModel.ROM_MONITOR : t == BoardType.PC ? ChipModel.ROM_BIOS : t == BoardType.Z80 ? ChipModel.ROM_Z80NET : null;
    }

    /**
     * 装了这些芯片的板子要不要 ROM：主板要（监控程序、BIOS）；Z80 扩展卡装网卡控制芯片（普通电脑网卡）要一颗驱动 ROM，装加密协处理器（加密卡）
     * 不要、也不能装（计划附录 26：加密卡的功能都在 NET.BBC 里）；别的板子没有 ROM 座
     */
    public static boolean needsRom(NBTTagCompound b) {
        BoardType t = type(b);
        if (t == null || romMask(t) == null) {
            return false;
        }
        return t != BoardType.Z80 || controllerModel(b) == ChipModel.NIC_CTRL;
    }

    /**
     * ROM 槽装得上这种掩模 ROM 吗：普通电脑认监控程序；个人电脑认 BIOS，也认激光对准控制程序（服主 11-16：光刻机对准台用的
     * 就是个人电脑主板，只是 ROM 里的程序换成激光对准控制程序；这样的主板装进电脑开不了机）。EPROM 另算（都认）
     */
    public static boolean acceptsRomMask(BoardType t, @Nullable ChipModel m) {
        if (m == null || romMask(t) == null) {
            return false;
        }
        return m == romMask(t) || (t == BoardType.PC && m == ChipModel.ROM_LASER_ALIGN);
    }

    /** 汉卡要的四片字库 ROM（别的板子不要） */
    public static ChipModel[] fonts(BoardType t) {
        return t == BoardType.HANZI ? new ChipModel[]{ChipModel.FONT_ROM_1, ChipModel.FONT_ROM_2, ChipModel.FONT_ROM_3, ChipModel.FONT_ROM_4}
                : new ChipModel[0];
    }

    /** 这种板子能装的控制芯片（ISA 扩展卡、Z80 扩展卡：网卡控制芯片或加密协处理器，装哪种就是哪种卡）；别的板子没有 */
    public static ChipModel[] controllers(BoardType t) {
        return t == BoardType.ISA || t == BoardType.Z80 ? new ChipModel[]{ChipModel.NIC_CTRL, ChipModel.TLS_COPROC} : new ChipModel[0];
    }

    public static boolean hasController(NBTTagCompound b) {
        return b.hasKey(CTRL, 10);
    }

    /** 装着的控制芯片的型号；没装时 null */
    @Nullable
    public static ChipModel controllerModel(NBTTagCompound b) {
        return hasController(b) ? ChipModel.byId(b.getCompoundTag(CTRL).getString(ChipModel.TAG)) : null;
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
        if (controllers(t).length > 0) {
            return hasController(b) && needsRom(b) == hasRom(b);
        }
        return (cpu(t) == null || hasCpu(b)) && (romMask(t) == null || hasRom(b)) && ram(b) == ramCount(t);
    }

    /**
     * CPU 实际跑的频率（kHz）：设定的频率 {@code settingKHz}，被这片芯片的最大频率封顶（服主 11-16，家具电脑：先按标准频率，
     * 芯片的最大频率比它低就按最大频率）。没装 CPU、CPU 报废了（没有最大频率）为 0。
     */
    public static int cpuClockKHz(NBTTagCompound b, int settingKHz) {
        if (!hasCpu(b)) {
            return 0;
        }
        NBTTagCompound cpu = b.getCompoundTag(CPU);
        if (cpu.getBoolean(moe.qingu.youran.item.ChipNbt.SCRAPPED)) {
            return 0;
        }
        int max = cpu.getInteger(moe.qingu.youran.item.ChipNbt.MAX_FREQ);
        return Math.max(0, Math.min(settingKHz, max));
    }

    /** 这片 CPU 的标准频率（kHz）：芯片数据里写的，没写时按型号 */
    public static int cpuStandardKHz(NBTTagCompound b) {
        NBTTagCompound cpu = b.getCompoundTag(CPU);
        int std = cpu.getInteger(moe.qingu.youran.item.ChipNbt.STD_FREQ);
        if (std > 0) {
            return std;
        }
        ChipModel m = ChipModel.byId(cpu.getString(ChipModel.TAG));
        return m == null ? 0 : m.ratedKHz;
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
            } else if (romMask(t) != null && (acceptsRomMask(t, p.model) || p.eprom)) {
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
            } else if (java.util.Arrays.asList(controllers(t)).contains(p.model)) {
                if (hasController(b)) {
                    return null;
                }
                NBTTagCompound c = p.chip.copy();
                c.setString(ChipModel.TAG, p.model.id);
                b.setTag(CTRL, c);
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
