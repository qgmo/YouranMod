package moe.qingu.youran.litho;

import moe.qingu.youran.net.LithoMessages;
import moe.qingu.youran.net.YouranNetwork;
import net.minecraft.entity.player.EntityPlayer;
import net.minecraft.entity.player.EntityPlayerMP;
import net.minecraft.inventory.Container;
import net.minecraft.inventory.IContainerListener;
import net.minecraft.nbt.NBTTagCompound;

import java.util.HashMap;
import java.util.Map;

/**
 * 对准显微镜的容器（没有物品格）：开着它的玩家先开的是操作者，只有操作者能拧旋钮；旁人能看同样的画面（服主 11-15）。
 * 显微镜画面要的数据每 2 tick 发一次（变了才发）。
 */
public class ContainerMicroscope extends Container implements LithoSyncTarget {
    public final TileLithoMaster master;
    private final EntityPlayer player;
    private final Map<EntityPlayerMP, NBTTagCompound> lastSent = new HashMap<>();
    private int ticks;
    public NBTTagCompound data = new NBTTagCompound();

    public ContainerMicroscope(EntityPlayer player, TileLithoMaster master) {
        this.master = master;
        this.player = player;
        if (!player.world.isRemote) {
            master.openMicroscope(player);
        }
    }

    @Override
    public boolean canInteractWith(EntityPlayer player) {
        return master.usableBy(player);
    }

    @Override
    public void onContainerClosed(EntityPlayer player) {
        super.onContainerClosed(player);
        if (!player.world.isRemote) {
            master.closeMicroscope(player);
        }
    }

    @Override
    public void detectAndSendChanges() {
        super.detectAndSendChanges();
        if (master.getWorld().isRemote || ticks++ % 2 != 0) {
            return;
        }
        for (IContainerListener l : listeners) {
            if (l instanceof EntityPlayerMP) {
                EntityPlayerMP p = (EntityPlayerMP) l;
                NBTTagCompound now = master.microscopeData(p);
                if (!now.equals(lastSent.get(p))) {
                    lastSent.put(p, now);
                    YouranNetwork.CHANNEL.sendTo(new LithoMessages.Sync(windowId, now), p);
                }
            }
        }
    }

    @Override
    public void receive(NBTTagCompound data) {
        this.data = data;
    }

    public EntityPlayer player() {
        return player;
    }
}
