package moe.qingu.youran.server.net;

import net.minecraft.world.chunk.Chunk;
import net.minecraftforge.event.world.ChunkEvent;
import net.minecraftforge.event.world.WorldEvent;
import net.minecraftforge.fml.common.eventhandler.SubscribeEvent;
import net.minecraftforge.fml.common.gameevent.PlayerEvent;

/** 主线程上的事件：区块加载与卸载、维度卸载、玩家退出 → 更新设备表 */
final class NetworkEvents {
    private final DeviceTable devices;
    private final TestDevices testDevices;

    NetworkEvents(DeviceTable devices, TestDevices testDevices) {
        this.devices = devices;
        this.testDevices = testDevices;
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
    }
}
