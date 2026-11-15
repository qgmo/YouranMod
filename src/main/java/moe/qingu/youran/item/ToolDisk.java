package moe.qingu.youran.item;

/**
 * 工具盘（服主 11-15）：盘里预先装着 079 服务的一张镜像（cmd/mkfloppies 做的）。只能在 MineBay 买（上架以后做），
 * 代码里用 {@link ItemFloppyDisk#toolDisk} 生成。
 */
public enum ToolDisk {
    /** GW-BASIC 盘（GW-BASIC + 示例，含网卡收发） */
    BASIC("basic", "GW-BASIC"),
    /** 汇编盘（MSA + DEBUG） */
    ASM("asm", "ASSEMBLY"),
    /** C 语言盘（Small-C 工具包 + 最简网页服务器的源码） */
    C("c", "SMALL-C"),
    /** Forth 盘（F83） */
    FORTH("forth", "F83");

    /** 079 服务里的镜像名 */
    public final String image;
    /** 标签上的名字（同盘的卷标） */
    public final String label;

    ToolDisk(String image, String label) {
        this.image = image;
        this.label = label;
    }
}
