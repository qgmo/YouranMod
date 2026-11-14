package moe.qingu.youran.server;

import moe.qingu.youran.server.command.CommandHub;
import moe.qingu.youran.server.command.CommandYouran;
import moe.qingu.youran.YouranMod;
import moe.qingu.youran.logic.SharedState;
import moe.qingu.youran.network.NetworkHooks;
import moe.qingu.youran.network.ServerNetworkModule;
import net.minecraftforge.common.MinecraftForge;
import net.minecraftforge.fml.common.event.FMLServerStartingEvent;

/**
 * 只在服务端（独立服务端或单人游戏的内置服务端）运行的部分的入口：开服时算好配置、登记 tick 处理、主城区块与刷怪的处理、
 * 登录时同步配置、指令、游戏内网络（实现在完整包的 {@code server.net} 里，按类名加载，客户端包里没有），关服时撤掉。
 * 客户端不会走到这里（单人游戏时由内置服务端那一侧调用）。
 */
public final class ServerSide {
    private static PlayerTickHandler playerTick;
    private static WorldTickHandler worldTick;
    private static MainCityChunks mainCityChunks;
    private static MainCitySpawns mainCitySpawns;
    private static SettingsSync settingsSync;

    private ServerSide() {
    }

    /** 服务端启动前 */
    public static void start() {
        stop();
        Settings.reload();
        playerTick = new PlayerTickHandler();
        worldTick = new WorldTickHandler();
        MinecraftForge.EVENT_BUS.register(playerTick);
        MinecraftForge.EVENT_BUS.register(worldTick);
        mainCityChunks = new MainCityChunks();
        MinecraftForge.EVENT_BUS.register(mainCityChunks);
        mainCitySpawns = new MainCitySpawns();
        MinecraftForge.EVENT_BUS.register(mainCitySpawns);
        settingsSync = new SettingsSync();
        MinecraftForge.EVENT_BUS.register(settingsSync);
    }

    /** 顺序同 1.3.4：/hub 在前。游戏内网络也在这时启动（世界已加载，能读存档里的地址分配表），它的测试命令 /ynet 在后 */
    public static void registerCommands(FMLServerStartingEvent event) {
        event.registerServerCommand(new CommandHub());
        event.registerServerCommand(new CommandYouran());
        ServerNetworkModule network = NetworkHooks.module();
        if (network != null) {
            try {
                network.start(event.getServer(), event);
            } catch (RuntimeException e) {
                YouranMod.LOG.error("游戏内网络启动失败，网络不可用", e);
                network.stop();
            }
        }
    }

    /** 关服开始时（玩家还连着，能告诉客户端网卡断开）：停游戏内网络 */
    public static void stopping() {
        ServerNetworkModule network = NetworkHooks.loadedModule();
        if (network != null) {
            network.stop();
        }
    }

    /** 服务端停下以后（单人游戏退回标题画面时也会） */
    public static void stop() {
        stopping();
        if (playerTick != null) {
            MinecraftForge.EVENT_BUS.unregister(playerTick);
            playerTick = null;
        }
        if (worldTick != null) {
            MinecraftForge.EVENT_BUS.unregister(worldTick);
            worldTick = null;
        }
        if (mainCityChunks != null) {
            MinecraftForge.EVENT_BUS.unregister(mainCityChunks);
            mainCityChunks = null;
        }
        if (mainCitySpawns != null) {
            MinecraftForge.EVENT_BUS.unregister(mainCitySpawns);
            mainCitySpawns = null;
        }
        if (settingsSync != null) {
            MinecraftForge.EVENT_BUS.unregister(settingsSync);
            settingsSync = null;
        }
        SharedState.resetServer();
    }
}
