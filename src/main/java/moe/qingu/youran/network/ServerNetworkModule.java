package moe.qingu.youran.network;

import net.minecraft.server.MinecraftServer;
import net.minecraftforge.fml.common.event.FMLServerStartingEvent;

/** 服务端网络的启停（实现是 {@code server.net.NetworkServer}，按类名加载，客户端包里没有它） */
public interface ServerNetworkModule {
    /** 开服时（世界已加载）：读存档里的分配表、启动网络线程、登记指令 */
    void start(MinecraftServer server, FMLServerStartingEvent event);

    /** 关服时：停网络线程（等它停干净） */
    void stop();
}
