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

    private NetworkConfig() {
    }
}
