package moe.qingu.youran.api.network;

import net.minecraft.entity.player.EntityPlayerMP;
import net.minecraft.server.MinecraftServer;
import net.minecraftforge.fml.common.FMLCommonHandler;

import java.util.UUID;

/**
 * 在线玩家现在在哪（Minecraft 的维度 ID 与坐标）。给只依赖 Sponge API 的插件（如 YouranAuth 的 {@code id.yr}）用：
 * Sponge 的世界没有数字维度 ID，而定位模块的位置证明、设备位置用的是它。
 */
public final class PlayerPosition {
    public final int dimension;
    public final double x;
    public final double y;
    public final double z;

    public PlayerPosition(int dimension, double x, double y, double z) {
        this.dimension = dimension;
        this.x = x;
        this.y = y;
        this.z = z;
    }

    /** 服务端主线程：在线玩家现在的位置；不在线（或不在服务端）为 null */
    public static PlayerPosition of(UUID player) {
        MinecraftServer s = FMLCommonHandler.instance().getMinecraftServerInstance();
        EntityPlayerMP p = s == null || player == null ? null : s.getPlayerList().getPlayerByUUID(player);
        return p == null ? null : new PlayerPosition(p.dimension, p.posX, p.posY, p.posZ);
    }

    @Override
    public String toString() {
        return dimension + " " + x + " " + y + " " + z;
    }
}
