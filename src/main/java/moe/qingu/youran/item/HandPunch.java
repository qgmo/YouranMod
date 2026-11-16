package moe.qingu.youran.item;

import net.minecraft.item.ItemStack;
import net.minecraft.nbt.NBTTagCompound;
import net.minecraft.world.World;

/**
 * 手工用螺丝刀冲孔（服主 11-16：光板打孔、软盘套冲孔都有概率打坏）：合成出来的物品先带一个标记 {@code HandPunch:{Broken:坏了的元数据,
 * Chance:千分比}}，在服务端第一次“生产出来”时（合成拿走 {@code onCreated}、在物品栏里 {@code onUpdate}、掉在地上）掷一次：
 * 坏了就把元数据换成坏了的那种，然后去掉标记。合成格里显示的是没坏的样子（拿走那一下才定好坏，同现实里打完才知道）。
 */
public final class HandPunch {
    public static final String TAG = "HandPunch";

    private HandPunch() {
    }

    /** 给合成的产物打上标记 */
    public static ItemStack mark(ItemStack out, int brokenMeta, double chance) {
        NBTTagCompound t = new NBTTagCompound();
        t.setInteger("Broken", brokenMeta);
        t.setInteger("Chance", (int) Math.round(chance * 1000));
        if (!out.hasTagCompound()) {
            out.setTagCompound(new NBTTagCompound());
        }
        out.getTagCompound().setTag(TAG, t);
        return out;
    }

    /** 服务端：有标记就掷一次，去掉标记 */
    public static void resolve(ItemStack stack, World world) {
        if (world == null || world.isRemote || !stack.hasTagCompound() || !stack.getTagCompound().hasKey(TAG, 10)) {
            return;
        }
        NBTTagCompound t = stack.getTagCompound().getCompoundTag(TAG);
        stack.getTagCompound().removeTag(TAG);
        if (stack.getTagCompound().isEmpty()) {
            stack.setTagCompound(null);
        }
        if (world.rand.nextInt(1000) < t.getInteger("Chance")) {
            stack.setItemDamage(t.getInteger("Broken"));
        }
    }
}
