package moe.qingu.youran.server.net.nav;

import moe.qingu.youran.config.ServerConfig;
import net.minecraftforge.common.config.Config;

/** 浏览器主页 180.yr（导航站）、广告平台 platform.180.yr、帮助 help.180.yr 的配置，在 config/youranmod.cfg 的 nav 节。只在服务端。 */
@Config(modid = ServerConfig.KEY, name = ServerConfig.FILE, category = "nav")
public final class NavConfig {
    @Config.Comment({"是否开 180.yr（导航站）、platform.180.yr（广告平台）、help.180.yr（帮助）。要游戏内网络开着。默认 true。",
            "关掉后这三个名字不登记；广告的数据还在世界存档里（youranmod/180/），再开时照旧。"})
    @Config.RequiresMcRestart
    public static boolean enabled = true;

    @Config.Comment({"广告平台登录用的游戏角色一键登录服务的域名（由 YouranAuth 提供，默认 id.yr）。"})
    @Config.RequiresMcRestart
    public static String idDomain = "id.yr";

    @Config.Comment({"广告平台的管理员（玩家名或 UUID，一行一个）。管理员能下架广告（选退款或不退）、封禁投放者。"})
    @Config.RequiresMcRestart
    public static String[] admins = {};

    @Config.Comment({"OP 是不是也算广告平台的管理员。默认 true。"})
    @Config.RequiresMcRestart
    public static boolean opsAreAdmins = true;

    @Config.Comment({"顶部横幅（468×60 图片，1 个位置）每天多少绿宝石（付款时也可以付悠然元，1 绿宝石 = 2 元）。默认 4。"})
    @Config.RangeInt(min = 1, max = 10000)
    @Config.RequiresMcRestart
    public static int priceBanner = 4;

    @Config.Comment({"侧栏方块（125×125 图片）每天多少绿宝石。默认 2。"})
    @Config.RangeInt(min = 1, max = 10000)
    @Config.RequiresMcRestart
    public static int priceSquare = 2;

    @Config.Comment({"文字链（导航下面一排）每天多少绿宝石。默认 1。"})
    @Config.RangeInt(min = 1, max = 10000)
    @Config.RequiresMcRestart
    public static int priceText = 1;

    @Config.Comment({"小按钮（最底下一排 88×31 图片）每天多少绿宝石。默认 1。"})
    @Config.RangeInt(min = 1, max = 10000)
    @Config.RequiresMcRestart
    public static int priceButton = 1;

    @Config.Comment({"侧栏方块有几个位置。默认 4。"})
    @Config.RangeInt(min = 1, max = 8)
    @Config.RequiresMcRestart
    public static int squareSlots = 4;

    @Config.Comment({"文字链有几个位置。默认 6。"})
    @Config.RangeInt(min = 1, max = 12)
    @Config.RequiresMcRestart
    public static int textSlots = 6;

    @Config.Comment({"小按钮有几个位置（一排放得下 6 个）。默认 6。"})
    @Config.RangeInt(min = 1, max = 12)
    @Config.RequiresMcRestart
    public static int buttonSlots = 6;

    @Config.Comment({"每个位置在正在播的那条后面最多排几条。默认 3。"})
    @Config.RangeInt(min = 0, max = 20)
    @Config.RequiresMcRestart
    public static int queuePerSlot = 3;

    @Config.Comment({"一条广告最多买几天（按现实时间，关服时也照走）。默认 30。"})
    @Config.RangeInt(min = 1, max = 365)
    @Config.RequiresMcRestart
    public static int maxDays = 30;

    @Config.Comment({"广告平台的登录多久不用就失效（分钟）。登录跟着电脑走。默认 10。"})
    @Config.RangeInt(min = 1, max = 120)
    @Config.RequiresMcRestart
    public static int sessionIdleMinutes = 10;

    @Config.Comment({"另外放上导航的网站，一行一个：域名|分类|名字|一句话。分类写 购物 / 服务 / 工具 / 玩家网站（或 shopping / service / tool / player）。",
            "域名前加 * 表示放进“名站导航”格子。例：*wood.yr|玩家网站|老王木材|整组卖橡木。同一个域名覆盖模组登记的。域名要在游戏内 DNS 里查得到才显示。"})
    @Config.RequiresMcRestart
    public static String[] extraSites = {};

    @Config.Comment({"不放上导航的网站（域名，一行一个），即使模组登记了导航信息。"})
    @Config.RequiresMcRestart
    public static String[] hiddenSites = {};

    private NavConfig() {
    }
}
