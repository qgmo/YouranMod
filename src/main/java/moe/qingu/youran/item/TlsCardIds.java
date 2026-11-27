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
 * 发过的加密卡卡号（2.3.1.0 第 4 期）：{@code <世界>/data/youranmod_tlscard.dat}（主世界的 MapStorage，所有维度共用一份），照软盘盘号的办法
 * （{@link FloppyIds}）：新卡号随机挑（63 位正数），跳过发过的。只在服务端主线程用。
 */
public final class TlsCardIds extends WorldSavedData {
    public static final String NAME = "youranmod_tlscard";
    private static final SecureRandom RANDOM = new SecureRandom();

    private final Set<Long> issued = new HashSet<>();

    public TlsCardIds(String name) {
        super(name);
    }

    static TlsCardIds get(World world) {
        MapStorage storage = world.getMapStorage();
        TlsCardIds d = (TlsCardIds) storage.getOrLoadData(TlsCardIds.class, NAME);
        if (d == null) {
            d = new TlsCardIds(NAME);
            storage.setData(NAME, d);
        }
        return d;
    }

    /** 发一个新卡号（记下来、标记要存盘） */
    public static long issue(World world) {
        TlsCardIds d = get(world);
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
