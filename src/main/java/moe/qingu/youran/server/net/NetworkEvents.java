package moe.qingu.youran.server.net;

import net.minecraft.world.chunk.Chunk;
import net.minecraftforge.event.world.ChunkEvent;
import net.minecraftforge.event.world.WorldEvent;
import net.minecraftforge.fml.common.eventhandler.SubscribeEvent;
import net.minecraftforge.fml.common.gameevent.PlayerEvent;

/** 主线程上的事件：区块加载与卸载、维度卸载、玩家退出 → 更新设备表；玩家上线、下线 → 分、收回临时地址；每 tick → 系统确认框 */
final class NetworkEvents {
    private final DeviceTable devices;
    private final TestDevices testDevices;
    /** 玩家临时地址：上线分、下线收回（没开时为 null） */
    private final java.util.function.Consumer<net.minecraft.entity.player.EntityPlayerMP> onLogin;
    private final java.util.function.Consumer<java.util.UUID> onLogoutPlayer;

    /** 每 tick（主线程，tick 末尾）：系统确认框的超时与作废 */
    private final Runnable onTick;

    NetworkEvents(DeviceTable devices, TestDevices testDevices, java.util.function.Consumer<net.minecraft.entity.player.EntityPlayerMP> onLogin,
                  java.util.function.Consumer<java.util.UUID> onLogoutPlayer, Runnable onTick) {
        this.onTick = onTick;
        this.devices = devices;
        this.testDevices = testDevices;
        this.onLogin = onLogin;
        this.onLogoutPlayer = onLogoutPlayer;
    }

    @SubscribeEvent
    public void onLogin(PlayerEvent.PlayerLoggedInEvent event) {
        if (event.player instanceof net.minecraft.entity.player.EntityPlayerMP) {
            onLogin.accept((net.minecraft.entity.player.EntityPlayerMP) event.player);
        }
    }

    @SubscribeEvent
    public void onServerTick(net.minecraftforge.fml.common.gameevent.TickEvent.ServerTickEvent event) {
        if (event.phase == net.minecraftforge.fml.common.gameevent.TickEvent.Phase.END) {
            onTick.run();
        }
    }

    @SubscribeEvent
    public void onChunkLoad(ChunkEvent.Load event) {
        if (!event.getWorld().isRemote) {
            Chunk c = event.getChunk();
            devices.onChunk(event.getWorld().provider.getDimension(), c.x, c.z, true);
        }
    }

    @SubscribeEvent
    public void onChunkUnload(ChunkEvent.Unload event) {
        if (!event.getWorld().isRemote) {
            Chunk c = event.getChunk();
            devices.onChunk(event.getWorld().provider.getDimension(), c.x, c.z, false);
        }
    }

    @SubscribeEvent
    public void onWorldUnload(WorldEvent.Unload event) {
        if (!event.getWorld().isRemote) {
            devices.onDimensionUnload(event.getWorld().provider.getDimension());
        }
    }

    @SubscribeEvent
    public void onLogout(PlayerEvent.PlayerLoggedOutEvent event) {
        devices.onPlayerLeft(event.player.getUniqueID());
        testDevices.onPlayerLeft(event.player.getUniqueID());
        onLogoutPlayer.accept(event.player.getUniqueID());
    }
}
