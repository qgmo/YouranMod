package moe.qingu.youran.item;

import net.minecraft.nbt.NBTTagCompound;
import net.minecraft.nbt.NBTTagLong;
import net.minecraft.nbt.NBTTagList;
import net.minecraft.world.World;
import net.minecraft.world.storage.MapStorage;
import net.minecraft.world.storage.WorldSavedData;
import net.minecraftforge.common.util.Constants;

import java.security.SecureRandom;
import java.util.HashSet;
import java.util.Set;

/**
 * 发过的软盘盘号：{@code <世界>/data/youranmod_floppy.dat}（主世界的 MapStorage，所有维度共用一份）。新盘号随机挑，
 * 跳过发过的，两张盘不会拿到同一个号（复制物品的漏洞、创造模式的中键复制另说）。只在服务端主线程用。
 */
public final class FloppyIds extends WorldSavedData {
    public static final String NAME = "youranmod_floppy";
    private static final SecureRandom RANDOM = new SecureRandom();

    private final Set<Long> issued = new HashSet<>();

    public FloppyIds(String name) {
        super(name);
    }

    static FloppyIds get(World world) {
        MapStorage storage = world.getMapStorage();
        FloppyIds d = (FloppyIds) storage.getOrLoadData(FloppyIds.class, NAME);
        if (d == null) {
            d = new FloppyIds(NAME);
            storage.setData(NAME, d);
        }
        return d;
    }

    /** 发一个新盘号（记下来、标记要存盘） */
    public static long issue(World world) {
        FloppyIds d = get(world);
        long id = FloppyNbt.newId(RANDOM, d.issued::contains);
        d.issued.add(id);
        d.markDirty();
        return id;
    }

    @Override
    public void readFromNBT(NBTTagCompound nbt) {
        issued.clear();
        NBTTagList l = nbt.getTagList("Issued", Constants.NBT.TAG_LONG);
        for (int i = 0; i < l.tagCount(); i++) {
            issued.add(((NBTTagLong) l.get(i)).getLong());
        }
    }

    @Override
    public NBTTagCompound writeToNBT(NBTTagCompound nbt) {
        NBTTagList l = new NBTTagList();
        for (long id : issued) {
            l.appendTag(new NBTTagLong(id));
        }
        nbt.setTag("Issued", l);
        return nbt;
    }
}
