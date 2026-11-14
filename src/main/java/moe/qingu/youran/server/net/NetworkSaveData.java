package moe.qingu.youran.server.net;

import net.minecraft.nbt.NBTTagCompound;
import net.minecraft.nbt.NBTTagList;
import net.minecraft.world.World;
import net.minecraft.world.storage.MapStorage;
import net.minecraft.world.storage.WorldSavedData;
import net.minecraftforge.common.util.Constants;

import java.util.Map;

/**
 * 存档里的地址分配表：{@code <世界>/data/youranmod_network.dat}（主世界的 MapStorage，所有维度共用一份）。
 * <pre>
 *   GlobalId    long                       /48 前缀的 40 位全局 ID（fdXX:XXXX:XXXX::/48）
 *   Dimensions  [{Dim:int, Index:int}]     维度 ID → 维度序号
 *   Datacenters [{Key:string, Index:long}] 机房登记名 → 机房序号
 *   Hosts       [{Key:string, Datacenter:string, Host:long}]  主机登记名 → 机房与主机号
 * </pre>
 * TCP 连接、设备表等运行时状态不存（不跨重启保留）。
 */
public final class NetworkSaveData extends WorldSavedData {
    public static final String NAME = "youranmod_network";

    public final AllocationTable table = new AllocationTable();

    public NetworkSaveData(String name) {
        super(name);
    }

    /** 主线程上调用 */
    public static NetworkSaveData get(World overworld) {
        MapStorage storage = overworld.getMapStorage();
        NetworkSaveData d = (NetworkSaveData) storage.getOrLoadData(NetworkSaveData.class, NAME);
        if (d == null) {
            d = new NetworkSaveData(NAME);
            storage.setData(NAME, d);
        }
        return d;
    }

    @Override
    public void readFromNBT(NBTTagCompound nbt) {
        table.globalId = nbt.hasKey("GlobalId", Constants.NBT.TAG_LONG) ? nbt.getLong("GlobalId") & 0xFFFFFFFFFFL : -1;
        table.dimensions.clear();
        NBTTagList dims = nbt.getTagList("Dimensions", Constants.NBT.TAG_COMPOUND);
        for (int i = 0; i < dims.tagCount(); i++) {
            NBTTagCompound c = dims.getCompoundTagAt(i);
            int idx = c.getInteger("Index");
            if (idx >= 0 && idx <= 255 && !table.dimensions.containsValue(idx)) {
                table.dimensions.put(c.getInteger("Dim"), idx);
            }
        }
        table.datacenters.clear();
        NBTTagList dcs = nbt.getTagList("Datacenters", Constants.NBT.TAG_COMPOUND);
        for (int i = 0; i < dcs.tagCount(); i++) {
            NBTTagCompound c = dcs.getCompoundTagAt(i);
            long idx = c.getLong("Index");
            if (idx >= 0 && idx <= 0xFFFFFFFFL && !c.getString("Key").isEmpty() && !table.datacenters.containsValue(idx)) {
                table.datacenters.put(c.getString("Key"), idx);
            }
        }
        table.hosts.clear();
        NBTTagList hs = nbt.getTagList("Hosts", Constants.NBT.TAG_COMPOUND);
        for (int i = 0; i < hs.tagCount(); i++) {
            NBTTagCompound c = hs.getCompoundTagAt(i);
            long part = c.getLong("Host");
            AllocationTable.Host h = new AllocationTable.Host(c.getString("Datacenter"), part);
            if (part >= 1 && part <= 0xFFFFFFFFL && !c.getString("Key").isEmpty() && !table.hosts.containsValue(h)) {
                table.hosts.put(c.getString("Key"), h);
            }
        }
        table.dirty = false;
    }

    @Override
    public NBTTagCompound writeToNBT(NBTTagCompound nbt) {
        if (table.globalId >= 0) {
            nbt.setLong("GlobalId", table.globalId);
        }
        NBTTagList dims = new NBTTagList();
        for (Map.Entry<Integer, Integer> e : table.dimensions.entrySet()) {
            NBTTagCompound c = new NBTTagCompound();
            c.setInteger("Dim", e.getKey());
            c.setInteger("Index", e.getValue());
            dims.appendTag(c);
        }
        nbt.setTag("Dimensions", dims);
        NBTTagList dcs = new NBTTagList();
        for (Map.Entry<String, Long> e : table.datacenters.entrySet()) {
            NBTTagCompound c = new NBTTagCompound();
            c.setString("Key", e.getKey());
            c.setLong("Index", e.getValue());
            dcs.appendTag(c);
        }
        nbt.setTag("Datacenters", dcs);
        NBTTagList hs = new NBTTagList();
        for (Map.Entry<String, AllocationTable.Host> e : table.hosts.entrySet()) {
            NBTTagCompound c = new NBTTagCompound();
            c.setString("Key", e.getKey());
            c.setString("Datacenter", e.getValue().datacenter);
            c.setLong("Host", e.getValue().hostPart);
            hs.appendTag(c);
        }
        nbt.setTag("Hosts", hs);
        return nbt;
    }
}
