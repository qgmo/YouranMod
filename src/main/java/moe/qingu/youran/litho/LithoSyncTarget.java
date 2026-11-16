package moe.qingu.youran.litho;

import net.minecraft.nbt.NBTTagCompound;

/** 收服务端同步数据的界面容器（客户端那一份） */
public interface LithoSyncTarget {
    void receive(NBTTagCompound data);
}
