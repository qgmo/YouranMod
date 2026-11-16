package moe.qingu.youran.config;

import net.minecraftforge.common.config.Config;

/**
 * 在家具模组的 MineBay 网站上卖的东西（服主 11-16，见 {@code compat.minebay.MineBayListings}）：掩模、芯片设计图、线路底片、
 * 正版 ROM 镜像软盘、工具盘。只在装了家具模组时上架；开服时上架一次，改了要重启服务器。
 * 价格都是 {@link #currency} 的个数（绿宝石时网页上同时写悠然元，1 绿宝石 = 2 元）。
 */
@Config(modid = ServerConfig.KEY, name = ServerConfig.FILE, category = "minebay")
public final class MineBayConfig {
    @Config.Comment("开关：false 时这些东西都不上架。")
    @Config.RequiresMcRestart
    public static boolean enabled = true;

    @Config.Comment("货币：物品注册名，可带 @数据值（如 minecraft:dye@4）。默认绿宝石。")
    @Config.RequiresMcRestart
    public static String currency = "minecraft:emerald";

    @Config.Comment({"掩模的价格，按芯片档次三档（服主 11-16 定档次，价格 Claude 定）：",
            "最便宜：Z80、4164、各种 ROM（监控程序、BIOS、激光对准控制程序、字库 ROM 1–4）、2764 EPROM。"})
    @Config.RequiresMcRestart
    @Config.RangeInt(min = 1, max = 1000000)
    public static int maskPriceLow = 64;

    @Config.Comment("掩模中等价位：8088。")
    @Config.RequiresMcRestart
    @Config.RangeInt(min = 1, max = 1000000)
    public static int maskPriceMid = 128;

    @Config.Comment("掩模最贵的一档：YR-2000。")
    @Config.RequiresMcRestart
    @Config.RangeInt(min = 1, max = 1000000)
    public static int maskPriceHigh = 256;

    @Config.Comment({"掩模每种型号每月几套（服主 11-16 定：1）。按月整批重置：每个月 1 日（北京时间）补满，卖完了要等下个月。"})
    @Config.RequiresMcRestart
    @Config.RangeInt(min = 1, max = 100000)
    public static int maskPerMonth = 1;

    @Config.Comment("芯片设计图的价格（不限量；服主定“便宜”，价格 Claude 定）。")
    @Config.RequiresMcRestart
    @Config.RangeInt(min = 1, max = 1000000)
    public static int designPrice = 2;

    @Config.Comment("线路底片的价格（普通电脑、个人电脑、汉卡三种，不限量；价格 Claude 定）。")
    @Config.RequiresMcRestart
    @Config.RangeInt(min = 1, max = 1000000)
    public static int filmPrice = 8;

    @Config.Comment({"正版 ROM 镜像软盘的价格（普通电脑的监控程序、个人电脑的 BIOS、激光对准控制程序三种，不限量；",
            "服主定“中等”，价格 Claude 定）。"})
    @Config.RequiresMcRestart
    @Config.RangeInt(min = 1, max = 1000000)
    public static int romDiskPrice = 16;

    @Config.Comment("工具盘的价格（服主 11-15 定）：BASIC 盘、汇编盘、C 语言盘、Forth 盘，依次。")
    @Config.RequiresMcRestart
    public static int[] toolDiskPrices = {16, 24, 48, 12};

    @Config.Comment({"工具盘每月几张（服主 11-15 定），依次同上。按月上限、每天补上限的 1/30（连续补），卖完就要等慢慢补回来。"})
    @Config.RequiresMcRestart
    public static int[] toolDiskPerMonth = {14, 7, 7, 7};

    private MineBayConfig() {
    }

    /** 按现在的配置算价格与数量（不合格的收进范围） */
    public static moe.qingu.youran.logic.source.MineBayOffers.Prices prices() {
        return new moe.qingu.youran.logic.source.MineBayOffers.Prices(maskPriceLow, maskPriceMid, maskPriceHigh, maskPerMonth, designPrice, filmPrice,
                romDiskPrice, toolDiskPrices, toolDiskPerMonth);
    }
}
