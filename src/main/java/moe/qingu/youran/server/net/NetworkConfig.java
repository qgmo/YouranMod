package moe.qingu.youran.server.net;

import moe.qingu.youran.config.ServerConfig;
import net.minecraftforge.common.config.Config;

/** 游戏内网络（服务器内网）的配置，在 config/youranmod.cfg 的 network 节。只在服务端（完整包里有）。 */
@Config(modid = ServerConfig.KEY, name = ServerConfig.FILE, category = "network")
public final class NetworkConfig {
    @Config.Comment({"是否运行游戏内网络（路由器、DNS、NTP、测试网站、设备网卡）。关掉后设备都连不上。默认 true。"})
    @Config.RequiresMcRestart
    public static boolean enabled = true;

    @Config.Comment({"有全局 Wi-Fi 的维度（维度 ID），默认主世界 0、下界 -1、末地 1。",
            "第一次出现的维度按这里的顺序分一个维度序号（0–255），存进存档、以后不变；从清单里删掉的维度序号保留、不再联网。",
            "不在清单里的维度（如后室那类）没有地址，设备连不上。"})
    @Config.RequiresMcRestart
    public static int[] wifiDimensions = {0, -1, 1};

    @Config.Comment({"每台设备每秒最多发多少个包（玩家客户端发来的和服务端端点发的都算）。超过的丢掉并记日志。默认 200。"})
    @Config.RangeInt(min = 1)
    @Config.RequiresMcRestart
    public static int devicePacketsPerSecond = 200;

    @Config.Comment({"每台设备每秒最多发多少字节（按 IPv6 包长算）。超过的丢掉并记日志。默认 131072（128 KiB/s）。"})
    @Config.RangeInt(min = 1280)
    @Config.RequiresMcRestart
    public static int deviceBytesPerSecond = 131072;

    @Config.Comment({"同一类被丢掉的包（同一个玩家或设备、同一个原因）最多每隔多少秒记一条日志，中间的只计数。默认 10。"})
    @Config.RangeInt(min = 1)
    @Config.RequiresMcRestart
    public static int rejectLogIntervalSeconds = 10;

    @Config.Comment({"机房里每台主机（DNS、网站等）同时最多多少条 TCP 连接。默认 256。"})
    @Config.RangeInt(min = 1)
    @Config.RequiresMcRestart
    public static int hostMaxTcpConnections = 256;

    @Config.Comment({"玩家临时地址：玩家上线时在玩法 /64 的最后一个 /96 里分一个随机地址，只能连递归解析器的 DNS 和按用途放行的主机（TCP 80 / 443），",
            "下线收回。用途如家具电脑的旁观者自己下载页面里的图片。默认 true。"})
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
