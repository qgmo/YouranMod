package moe.qingu.youran.item;

import net.minecraft.nbt.NBTTagCompound;

import javax.annotation.Nullable;
import java.util.Random;
import java.util.function.LongPredicate;

/**
 * 软盘物品的 NBT（设计“软盘的数据存放”，服主 11-15：二进制数据不放进物品，内容存在 079 服务那边；物品只记盘号与标签）：
 * <pre>
 * Floppy: {
 *     DiskId: long    盘号（正数，全服唯一；生产出来时由服务端分配，决定这张盘的初始坏道）
 *     Label:  string  标签上写的名字（工具盘：工具的名字；可以没有）
 *     Image:  string  工具盘：盘里预先装着 079 服务的哪张镜像（basic、asm、c、forth）；空白盘没有
 * }
 * </pre>
 */
public final class FloppyNbt {
    public static final String TAG = "Floppy";
    public static final String DISK_ID = "DiskId";
    public static final String LABEL = "Label";
    public static final String IMAGE = "Image";

    private FloppyNbt() {
    }

    /** 盘号（没有时 0）：stack 的整个 NBT */
    public static long diskId(@Nullable NBTTagCompound stackTag) {
        NBTTagCompound f = floppy(stackTag);
        return f == null ? 0 : Math.max(0, f.getLong(DISK_ID));
    }

    public static String label(@Nullable NBTTagCompound stackTag) {
        NBTTagCompound f = floppy(stackTag);
        return f == null ? "" : f.getString(LABEL);
    }

    public static String image(@Nullable NBTTagCompound stackTag) {
        NBTTagCompound f = floppy(stackTag);
        return f == null ? "" : f.getString(IMAGE);
    }

    @Nullable
    private static NBTTagCompound floppy(@Nullable NBTTagCompound stackTag) {
        return stackTag != null && stackTag.hasKey(TAG, 10) ? stackTag.getCompoundTag(TAG) : null;
    }

    /** 往 stack 的 NBT（可以是 null）里写盘号，返回新的 NBT */
    public static NBTTagCompound withId(@Nullable NBTTagCompound stackTag, long id) {
        NBTTagCompound t = stackTag == null ? new NBTTagCompound() : stackTag;
        NBTTagCompound f = t.getCompoundTag(TAG);
        f.setLong(DISK_ID, id);
        t.setTag(TAG, f);
        return t;
    }

    /** 工具盘的 NBT：盘号、标签、镜像 */
    public static NBTTagCompound toolDisk(long id, String image, String label) {
        NBTTagCompound t = withId(null, id);
        t.getCompoundTag(TAG).setString(IMAGE, image);
        t.getCompoundTag(TAG).setString(LABEL, label);
        return t;
    }

    /**
     * 挑一个新盘号：随机的 63 位正数（盘号也是初始坏道的种子，随机挑才不会让相邻的盘号坏道雷同），{@code taken} 里已经发过的跳过。
     */
    public static long newId(Random random, LongPredicate taken) {
        for (; ; ) {
            long id = random.nextLong() & Long.MAX_VALUE;
            if (id != 0 && !taken.test(id)) {
                return id;
            }
        }
    }
}
