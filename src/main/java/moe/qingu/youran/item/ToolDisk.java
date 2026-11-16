package moe.qingu.youran.item;

/**
 * 预先装好内容的软盘：盘里装着 079 服务的一张镜像（YouranSCP 的 {@code cmd/mkfloppies} 做的），物品 NBT 只记镜像名。
 * 只能在 MineBay 买（{@code compat.minebay.MineBayListings} 上架），代码里用 {@link ItemFloppyDisk#toolDisk} 生成。
 * <ul>
 *     <li>工具盘（服主 11-15）：BASIC、汇编、C、Forth 四张；</li>
 *     <li>正版 ROM 镜像盘（服主 11-16）：普通电脑的监控程序（4 KB）、个人电脑的 BIOS（8 KB）、激光对准控制程序（8 KB），
 *     买到后玩家自己在电脑里用 EPROM 烧录器烧。</li>
 * </ul>
 */
public enum ToolDisk {
    /** GW-BASIC 盘（GW-BASIC + 示例，含网卡收发） */
    BASIC("basic", "GW-BASIC", false),
    /** 汇编盘（MSA + DEBUG） */
    ASM("asm", "ASSEMBLY", false),
    /** C 语言盘（Small-C 工具包 + 最简网页服务器的源码） */
    C("c", "SMALL-C", false),
    /** Forth 盘（F83） */
    FORTH("forth", "F83", false),
    /** 普通电脑的监控程序（MONITOR.ROM，4096 字节，和 079 服务的 {@code rom/monitor.bin} 一样） */
    MONITOR_ROM("monitor", "MONITOR", true),
    /** 个人电脑的 BIOS（BIOS.ROM，8192 字节，和 079 服务的 F000:E000–FFFF 一样） */
    BIOS_ROM("bios", "PC-BIOS", true),
    /** 激光对准控制程序（ALIGN.ROM，8192 字节，和 {@link moe.qingu.youran.api.pcb.LaserAlignProgram} 的镜像一样） */
    ALIGN_ROM("align", "ALIGNCTL", true);

    /** 079 服务里的镜像名 */
    public final String image;
    /** 标签上的名字（同盘的卷标） */
    public final String label;
    /** 正版 ROM 镜像盘（否则是工具盘） */
    public final boolean rom;

    ToolDisk(String image, String label, boolean rom) {
        this.image = image;
        this.label = label;
        this.rom = rom;
    }
}
