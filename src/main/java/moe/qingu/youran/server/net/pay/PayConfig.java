package moe.qingu.youran.server.net.pay;

import moe.qingu.youran.config.ServerConfig;
import net.minecraftforge.common.config.Config;

/** 聚合付款服务 pay.yr 的配置，在 config/youranmod.cfg 的 pay 节。只在服务端（完整包里有）。 */
@Config(modid = ServerConfig.KEY, name = ServerConfig.FILE, category = "pay")
public final class PayConfig {
    @Config.Comment({"是否开聚合付款服务 pay.yr（要游戏内网络开着）。关掉后 pay.yr 不登记，账本还在世界存档里（youranmod/pay/），再开时照旧。默认 true。"})
    @Config.RequiresMcRestart
    public static boolean enabled = true;

    @Config.Comment({"登录用的游戏角色一键登录服务的域名（由 YouranAuth 提供，默认 id.yr）。没有这个服务时 pay.yr 说明原因、不能付款。"})
    @Config.RequiresMcRestart
    public static String idDomain = "id.yr";

    @Config.Comment({"不能用来付款的物品（黑名单）。一行一个：模组:物品（如 minecraft:written_book）、模组:物品@数据值、模组:*（整个模组）。",
            "另外装着东西的容器（有内容的潜影盒、背包类）总是不收，空的照常收。默认空。"})
    @Config.RequiresMcRestart
    public static String[] blacklist = {};

    @Config.Comment({"收款单默认多少秒后过期（网站没给 expires_in 时）。默认 900（15 分钟）。"})
    @Config.RangeInt(min = 60, max = 86400)
    @Config.RequiresMcRestart
    public static int orderTtlSeconds = 900;

    @Config.Comment({"收款单最长多少秒过期。默认 86400（一天）。"})
    @Config.RangeInt(min = 60, max = 604800)
    @Config.RequiresMcRestart
    public static int orderMaxTtlSeconds = 86400;

    @Config.Comment({"每个收款方同时最多多少张还能付的收款单（防止网站无限创建）。默认 500。"})
    @Config.RangeInt(min = 1)
    @Config.RequiresMcRestart
    public static int maxOpenOrders = 500;

    @Config.Comment({"每把密钥每分钟最多调用几次接口（创建、查询收款单等）。默认 120。"})
    @Config.RangeInt(min = 1)
    @Config.RequiresMcRestart
    public static int apiPerMinute = 120;

    @Config.Comment({"pay.yr 的登录多久不用就失效（分钟）。登录跟着电脑走（同一台电脑的下一个人看得到），所以短一些。默认 10。"})
    @Config.RangeInt(min = 1, max = 120)
    @Config.RequiresMcRestart
    public static int sessionIdleMinutes = 10;

    @Config.Comment({"付过的收款单与付款记录保留多少天（之后不能再查、不能退款）。账户余额与收支记录（每个账户最近 200 条）一直保留。默认 365。"})
    @Config.RangeInt(min = 7)
    @Config.RequiresMcRestart
    public static int keepDays = 365;

    private PayConfig() {
    }
}
