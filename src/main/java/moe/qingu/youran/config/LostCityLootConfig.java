package moe.qingu.youran.config;

import net.minecraftforge.common.config.Config;

/**
 * 失落的城市（Lost Cities）建筑箱子里刷掩模、芯片设计图（服主 11-16，见 {@code server.loot.LostCityLoot}）。
 * 不改 Lost Cities 的配置：往它的战利品表里加一个池。表是开服后第一次用到时读的，改了要重启服务器。
 */
@Config(modid = ServerConfig.KEY, name = ServerConfig.FILE, category = "lost_city_loot")
public final class LostCityLootConfig {
    @Config.Comment("开关：false 时不往任何战利品表里加东西。")
    @Config.RequiresMcRestart
    public static boolean enabled = true;

    @Config.Comment({"每个箱子出一件的概率（0–1）。服主 11-16 定：1%（期望开 100 个箱子出 1 件）。",
            "出了以后在掩模、设计图里按 maskShare 分，型号从全部型号里等概率抽。"})
    @Config.RangeDouble(min = 0, max = 1)
    public static double chance = 0.01;

    @Config.Comment("出了一件时是掩模的概率（0–1），其余是芯片设计图。Claude 定：一半。")
    @Config.RangeDouble(min = 0, max = 1)
    public static double maskShare = 0.5;

    @Config.Comment({"失落的城市的维度号（Lost Cities 配置 general.dimensionId，默认 111）。",
            "下面 vanillaTables 里的表只在这些维度里刷。"})
    @Config.RequiresMcRestart
    public static int[] dimensions = {111};

    @Config.Comment({"建筑箱子专用的表，在哪个维度都刷（Lost Cities citydata/conditions.json 的 chestloot：建筑 4 楼以上和地下室用它）。",
            "铁路地牢的 lostcities:chests/raildungeonchest 不在建筑里，不加。"})
    @Config.RequiresMcRestart
    public static String[] buildingTables = {"lostcities:chests/lostcitychest"};

    @Config.Comment({"失落的城市里少量建筑箱子用的原版表：只在上面的维度里刷（免得主世界的原版结构也刷）。"})
    @Config.RequiresMcRestart
    public static String[] vanillaTables = {
            "minecraft:chests/nether_bridge",
            "minecraft:chests/abandoned_mineshaft",
            "minecraft:chests/village_blacksmith",
            "minecraft:chests/desert_pyramid",
            "minecraft:chests/jungle_temple",
            "minecraft:chests/simple_dungeon"};

    private LostCityLootConfig() {
    }
}
