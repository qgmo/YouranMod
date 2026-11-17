package moe.qingu.youran.config;

import net.minecraftforge.common.config.Config;

/**
 * 暮色森林拆解台的限制（服主 11-17，见 {@code compat.twilightforest.UncraftGuard}）。审查找出来的刷物、洗数据的拆解已经写在代码里
 * （{@code compat.twilightforest.UncraftBuiltins}），这里只放额外加的名单。只在服务端读，单人游戏用默认值。
 * 名单格式：物品注册名，可带 {@code @数据值}，也可以写 {@code 模组ID:*}；{@code #} 开头的行是注释。
 */
@Config(modid = ServerConfig.KEY, name = ServerConfig.FILE, category = "uncrafting")
public final class UncraftConfig {
    @Config.Comment("开关：false 时拆解台照暮色森林原样（不做下面这些限制）。")
    @Config.RequiresMcRestart
    public static boolean enabled = true;

    @Config.Comment({"另外不让拆的物品（放进拆解台什么都不显示）。代码里已有的不用再写。",
            "例：quark:candle、minecraft:stone@3、chineseworkshop:*"})
    @Config.RequiresMcRestart
    public static String[] blockedItems = {};

    @Config.Comment({"另外不让拆出来的物品：拆出来的材料里有它的配方都不拿来拆（例如稀缺、只能找到或买到的东西）。代码里已有的不用再写。"})
    @Config.RequiresMcRestart
    public static String[] blockedResults = {};

    @Config.Comment({"另外不拿来拆的配方（配方注册名，如 minecraft:mapextending；CraftTweaker 加的是 crafttweaker:ct_shaped… 这种，",
            "脚本改了名字会变）。"})
    @Config.RequiresMcRestart
    public static String[] blockedRecipes = {};

    private UncraftConfig() {
    }
}
