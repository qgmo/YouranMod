package moe.qingu.youran.logic.source;

import moe.qingu.youran.item.ToolDisk;
import moe.qingu.youran.logic.litho.ChipModel;
import moe.qingu.youran.logic.pcb.BoardType;

import javax.annotation.Nullable;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * YouranMod 在 MineBay 上卖的东西（服主 11-16）。纯逻辑：只算出上架清单（编号、分类、价格、库存、名字与说明的翻译键），
 * 真正上架（家具模组的 MineBay 接口）在 {@code compat.minebay.MineBayListings}。
 * <ul>
 *     <li>掩模：每种型号每月 {@code maskPerMonth} 套（服主：1），按月整批重置；价格按芯片档次三档（{@link Tier}）；</li>
 *     <li>芯片设计图：便宜、不限量；</li>
 *     <li>线路底片：普通电脑、个人电脑、汉卡、ISA 扩展卡四种，不限量；</li>
 *     <li>正版 ROM 镜像软盘：监控程序、BIOS、激光对准控制程序，价格中等、不限量；</li>
 *     <li>工具盘：BASIC、汇编、C、Forth 四张（服主 11-15：价格 16、24、48、12，每月 14、7、7、7，每天补）；
 *     网络工具盘（服主 11-27：16 绿宝石、每月 14 张，同 BASIC 盘）。</li>
 * </ul>
 * 编号定了不要改（玩家购物车里记的是它）。
 */
public final class MineBayOffers {
    /** 分类：掩模、设计图、底片 */
    public static final String CAT_CHIPMAKING = "youranmod:chipmaking";
    public static final String CAT_CHIPMAKING_NAME = "电脑制造";
    /** 分类：软盘（工具盘、ROM 镜像盘） */
    public static final String CAT_SOFTWARE = "youranmod:software";
    public static final String CAT_SOFTWARE_NAME = "电脑软件";

    /** 单价上限（MineBay 的上限） */
    public static final int MAX_PRICE = 1_000_000;
    /** 每月上限的上限（MineBay 的上限） */
    public static final int MAX_PER_MONTH = 100_000;

    /**
     * 掩模的价格档次（服主 11-16）：Z80、4164、各种 ROM、2764 最便宜；8088 中等；YR-2000 最贵。
     * 网卡控制芯片按中档（服主 11-27：同 8088，3 μm、7 层）
     */
    public enum Tier {
        LOW, MID, HIGH;

        public static Tier of(ChipModel m) {
            switch (m) {
                case YR2000:
                    return HIGH;
                case I8088:
                case NIC_CTRL:
                    return MID;
                default:
                    return LOW;
            }
        }
    }

    /** 上架的是哪类东西 */
    public enum Kind {
        MASK, DESIGN, FILM, ROM_DISK, TOOL_DISK
    }

    /** 价格与数量（来自配置；不合格的收进范围） */
    public static final class Prices {
        public final int maskLow, maskMid, maskHigh, maskPerMonth, design, film, romDisk;
        /** 按 {@link ToolDisk} 里工具盘的顺序（BASIC、汇编、C、Forth、网络） */
        public final int[] toolPrices, toolPerMonth;

        public Prices(int maskLow, int maskMid, int maskHigh, int maskPerMonth, int design, int film, int romDisk, int[] toolPrices, int[] toolPerMonth) {
            this.maskLow = price(maskLow);
            this.maskMid = price(maskMid);
            this.maskHigh = price(maskHigh);
            this.maskPerMonth = perMonth(maskPerMonth);
            this.design = price(design);
            this.film = price(film);
            this.romDisk = price(romDisk);
            List<ToolDisk> tools = toolDisks();
            this.toolPrices = new int[tools.size()];
            this.toolPerMonth = new int[tools.size()];
            for (int i = 0; i < tools.size(); i++) {
                this.toolPrices[i] = price(toolPrices != null && i < toolPrices.length ? toolPrices[i] : DEFAULT_TOOL_PRICES[i]);
                this.toolPerMonth[i] = perMonth(toolPerMonth != null && i < toolPerMonth.length ? toolPerMonth[i] : DEFAULT_TOOL_PER_MONTH[i]);
            }
        }

        public int mask(ChipModel m) {
            switch (Tier.of(m)) {
                case HIGH:
                    return maskHigh;
                case MID:
                    return maskMid;
                default:
                    return maskLow;
            }
        }
    }

    /** 有线路底片的板子（{@link BoardType#circuits()}：普通电脑、个人电脑、汉卡、ISA 扩展卡）；家具电脑主板没有底片、光板，不卖它的底片 */
    public static final List<BoardType> FILM_BOARDS = Collections.unmodifiableList(java.util.Arrays.asList(BoardType.circuits()));

    /** 工具盘的价格、每月几张（服主 11-15）：BASIC、汇编、C、Forth；网络工具盘（服主 11-27） */
    public static final int[] DEFAULT_TOOL_PRICES = {16, 24, 48, 12, 16};
    public static final int[] DEFAULT_TOOL_PER_MONTH = {14, 7, 7, 7, 14};

    /** 一件商品 */
    public static final class Offer {
        public final String id;
        public final Kind kind;
        public final String category;
        @Nullable
        public final ChipModel model;
        @Nullable
        public final BoardType board;
        @Nullable
        public final ToolDisk disk;
        public final int price;
        /** 每月上限；0 = 不限量 */
        public final int perMonth;
        /** 限量时：按月整批重置（掩模）；否则按月上限、每天补（工具盘） */
        public final boolean monthlyReset;
        /** 网页上的名字的翻译键；null = 用物品自己的名字（掩模、设计图、底片） */
        @Nullable
        public final String nameKey;
        /** 商品说明（一行）的翻译键 */
        public final String descKey;

        Offer(String id, Kind kind, String category, @Nullable ChipModel model, @Nullable BoardType board, @Nullable ToolDisk disk, int price,
              int perMonth, boolean monthlyReset, @Nullable String nameKey, String descKey) {
            this.id = id;
            this.kind = kind;
            this.category = category;
            this.model = model;
            this.board = board;
            this.disk = disk;
            this.price = price;
            this.perMonth = perMonth;
            this.monthlyReset = monthlyReset;
            this.nameKey = nameKey;
            this.descKey = descKey;
        }

        public boolean limited() {
            return perMonth > 0;
        }

        @Override
        public String toString() {
            return id + " " + price + (limited() ? " " + perMonth + (monthlyReset ? "/monthly" : "/daily") : "");
        }
    }

    private MineBayOffers() {
    }

    static int price(int p) {
        return Math.max(1, Math.min(MAX_PRICE, p));
    }

    static int perMonth(int n) {
        return Math.max(1, Math.min(MAX_PER_MONTH, n));
    }

    /** 工具盘（{@link ToolDisk} 里不是 ROM 镜像盘的那些，按声明顺序） */
    public static List<ToolDisk> toolDisks() {
        List<ToolDisk> out = new ArrayList<>();
        for (ToolDisk d : ToolDisk.values()) {
            if (!d.rom) {
                out.add(d);
            }
        }
        return out;
    }

    /** 全部商品：掩模（每种型号一件）、设计图（每种型号一件）、底片、ROM 镜像盘、工具盘，按这个顺序 */
    public static List<Offer> plan(Prices p) {
        List<Offer> out = new ArrayList<>();
        for (ChipModel m : ChipModel.values()) {
            out.add(new Offer("youranmod:photomask/" + m.id, Kind.MASK, CAT_CHIPMAKING, m, null, null, p.mask(m), p.maskPerMonth, true,
                    null, "youranmod.minebay.photomask.desc"));
        }
        for (ChipModel m : ChipModel.values()) {
            out.add(new Offer("youranmod:chip_design/" + m.id, Kind.DESIGN, CAT_CHIPMAKING, m, null, null, p.design, 0, false,
                    null, "youranmod.minebay.chip_design.desc"));
        }
        for (BoardType b : FILM_BOARDS) {
            out.add(new Offer("youranmod:pcb_film/" + b.name, Kind.FILM, CAT_CHIPMAKING, null, b, null, p.film, 0, false,
                    null, "youranmod.minebay.pcb_film.desc"));
        }
        for (ToolDisk d : ToolDisk.values()) {
            if (d.rom) {
                out.add(new Offer("youranmod:rom_disk/" + d.image, Kind.ROM_DISK, CAT_SOFTWARE, null, null, d, p.romDisk, 0, false,
                        "youranmod.minebay.rom_disk." + d.image + ".name", "youranmod.minebay.rom_disk." + d.image + ".desc"));
            }
        }
        List<ToolDisk> tools = toolDisks();
        for (int i = 0; i < tools.size(); i++) {
            ToolDisk d = tools.get(i);
            out.add(new Offer("youranmod:tool_disk/" + d.image, Kind.TOOL_DISK, CAT_SOFTWARE, null, null, d, p.toolPrices[i], p.toolPerMonth[i], false,
                    "youranmod.minebay.tool_disk." + d.image + ".name", "youranmod.minebay.tool_disk." + d.image + ".desc"));
        }
        return Collections.unmodifiableList(out);
    }
}
