package moe.qingu.youran.item;

import net.minecraft.client.util.ITooltipFlag;
import net.minecraft.item.ItemStack;
import net.minecraft.nbt.NBTTagCompound;
import net.minecraft.world.World;
import net.minecraftforge.fml.relauncher.Side;
import net.minecraftforge.fml.relauncher.SideOnly;

import javax.annotation.Nullable;
import java.util.List;

/**
 * 5.25 英寸软盘。现在只是物品；以后 SCP 个人电脑的软驱读它。
 *
 * <p>NBT（先留好位置，现在不读不写）：
 * <pre>
 * Floppy: {
 *     DiskId: long   盘编号（格式化时分配，全服唯一；没有这个键 = 未格式化）
 *     Image:  string 镜像引用（盘里的内容存在服务端的哪个镜像，由软驱那边定格式）
 * }
 * </pre>
 * 未格式化的软盘没有 NBT，可以堆叠；格式化以后各自带编号，不再堆叠。
 */
public class ItemFloppyDisk extends ItemWithTooltip {
    /** 软盘数据的复合标签名 */
    public static final String TAG = "Floppy";
    /** 盘编号 */
    public static final String DISK_ID = "DiskId";
    /** 镜像引用 */
    public static final String IMAGE = "Image";

    public ItemFloppyDisk() {
        super("floppy_disk", 16, "5.25 英寸，未格式化");
    }

    /** 格式化过（有盘编号） */
    public static boolean isFormatted(ItemStack stack) {
        NBTTagCompound tag = stack.getSubCompound(TAG);
        return tag != null && tag.hasKey(DISK_ID);
    }

    @SideOnly(Side.CLIENT)
    @Override
    public void addInformation(ItemStack stack, @Nullable World world, List<String> lines, ITooltipFlag flag) {
        if (isFormatted(stack)) {
            lines.add("5.25 英寸");
        } else {
            super.addInformation(stack, world, lines, flag);
        }
    }
}
