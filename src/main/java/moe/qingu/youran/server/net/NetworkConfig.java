package moe.qingu.youran.server.net;

import moe.qingu.youran.config.ServerConfig;
import net.minecraftforge.common.config.Config;

/** 游戏内网络（服务器内网）的配置，在 config/youranmod.cfg 的 network 节。只在服务端（完整包里有）。 */
@Config(modid = ServerConfig.KEY, name = ServerConfig.FILE, category = "network")
public final class NetworkConfig {
    @Config.Comment({"是否运行游戏内网络（路由器、DNS、NTP、测试网站、网卡）。关掉后电脑都连不上。默认 true。"})
    @Config.RequiresMcRestart
    public static boolean enabled = true;

    @Config.Comment({"有全局 Wi-Fi 的维度（维度 ID），默认主世界 0、下界 -1、末地 1。",
            "第一次出现的维度按这里的顺序分一个维度序号（0–255），存进存档、以后不变；从清单里删掉的维度序号保留、不再联网。",
            "不在清单里的维度（如后室那类）没有地址，设备连不上。"})
    @Config.RequiresMcRestart
    public static int[] wifiDimensions = {0, -1, 1};

    @Config.Comment({"每张网卡每秒最多发多少个包（家具电脑这类接玩家客户端的网卡、服务端模拟的网卡都算）。超过的丢掉并记日志。默认 200。"})
    @Config.RangeInt(min = 1)
    @Config.RequiresMcRestart
    public static int devicePacketsPerSecond = 200;

    @Config.Comment({"每张网卡每秒最多发多少字节（按 IPv6 包长算）。超过的丢掉并记日志。默认 131072（128 KiB/s）。"})
    @Config.RangeInt(min = 1280)
    @Config.RequiresMcRestart
    public static int deviceBytesPerSecond = 131072;

    @Config.Comment({"同一类被丢掉的包（同一个玩家或网卡、同一个原因）最多每隔多少秒记一条日志，中间的只计数。默认 10。"})
    @Config.RangeInt(min = 1)
    @Config.RequiresMcRestart
    public static int rejectLogIntervalSeconds = 10;

    @Config.Comment({"机房里每台主机（DNS、网站等）同时最多多少条 TCP 连接。默认 256。"})
    @Config.RangeInt(min = 1)
    @Config.RequiresMcRestart
    public static int hostMaxTcpConnections = 256;

    @Config.Comment({"玩家地址：玩家上线时客户端用 DHCPv6 在玩法 /64 的最后一个 /96 里租一个随机地址，只能连递归解析器（DNS）、NTP 服务器",
            "和按用途放行的主机（TCP 80 / 443），下线收回。用途如家具电脑的旁观者自己下载页面里的图片。能用 /ynet 的人不受这些限制。默认 true。"})
    @Config.RequiresMcRestart
    public static boolean playerAddresses = true;

    @Config.Comment({"玩家临时地址每秒最多发多少个包（玩家 → 服务器）。超过的丢掉并记日志。默认 300。"})
    @Config.RangeInt(min = 1)
    @Config.RequiresMcRestart
    public static int playerPacketsPerSecond = 300;

    @Config.Comment({"玩家临时地址每秒最多发多少字节（按 IPv6 包长算）。默认 49152（48 KiB/s；主要是 TCP 确认）。"})
    @Config.RangeInt(min = 1280)
    @Config.RequiresMcRestart
    public static int playerBytesPerSecond = 49152;

    @Config.Comment({"发给玩家临时地址的包每秒最多多少个（服务器 → 玩家）。超过的丢掉（TCP 会重传）。默认 300。"})
    @Config.RangeInt(min = 1)
    @Config.RequiresMcRestart
    public static int playerInboundPacketsPerSecond = 300;

    @Config.Comment({"发给玩家临时地址的包每秒最多多少字节。默认 393216（384 KiB/s）。"})
    @Config.RangeInt(min = 1280)
    @Config.RequiresMcRestart
    public static int playerInboundBytesPerSecond = 393216;

    @Config.Comment({"NTP 时间服务器报的层级（RFC 5905，2–15）。这台只是转手服务器操作系统的时钟：系统时钟一般跟公共 NTP 池的 2 层服务器同步，",
            "转手一次是 3 层。默认 3。"})
    @Config.RangeInt(min = 2, max = 15)
    @Config.RequiresMcRestart
    public static int ntpStratum = 3;

    @Config.Comment({"NTP 时间服务器的上游（服务器操作系统同步时钟用的那台 NTP 服务器）：名字或地址。参考标识按 RFC 5905 由它算：",
            "IPv4 地址是地址本身，IPv6 地址是地址 MD5 的前 4 字节；写名字时开服时在后台用操作系统的解析器查一次地址再算。",
            "空着时参考标识为 0。默认空。"})
    @Config.RequiresMcRestart
    public static String ntpUpstream = "";

    @Config.Comment({"NTP 时间服务器报的根延迟（到最上层时钟源的往返延迟，毫秒）。默认 30。"})
    @Config.RangeInt(min = 0, max = 10000)
    @Config.RequiresMcRestart
    public static int ntpRootDelayMillis = 30;

    @Config.Comment({"NTP 时间服务器报的根离差（相对最上层时钟源的最大误差，毫秒）。默认 50。"})
    @Config.RangeInt(min = 0, max = 10000)
    @Config.RequiresMcRestart
    public static int ntpRootDispersionMillis = 50;

    @Config.Comment({"HTTPS：中间 CA 的证书文件（PEM，由 QinguMoe CA 签发；后面可以接上更上层的中间证书，不用放根证书）。",
            "路径相对服务器根目录，如 config/youranmod-tls/intermediate.crt。和 tlsCaKey 都填了才开 HTTPS（网站的 443 口），空着就只有 HTTP。"})
    @Config.RequiresMcRestart
    public static String tlsCaCertificate = "";

    @Config.Comment({"HTTPS：中间 CA 的私钥文件（PEM，不加密的 PKCS#8，即 -----BEGIN PRIVATE KEY-----）。只给运行服务器的帐号读。"})
    @Config.RequiresMcRestart
    public static String tlsCaKey = "";

    @Config.Comment({"人机验证 captcha.yr 的本机端口：只绑 127.0.0.1，提供和 captcha.yr 一样的 POST /new、POST /verify，",
            "给进不了游戏内网络的游戏外网站（网页面板的后台）用；图片仍由游戏内浏览器从 captcha.yr 取。默认 false（不开）。"})
    @Config.RequiresMcRestart
    public static boolean captchaLocal = false;

    @Config.Comment({"人机验证本机端口的端口号（captchaLocal 开着时才用）。默认 8094（与网页面板 [ingame] captcha_url 的缺省值相同）。"})
    @Config.RangeInt(min = 1, max = 65535)
    @Config.RequiresMcRestart
    public static int captchaLocalPort = 8094;

    private NetworkConfig() {
    }
}
