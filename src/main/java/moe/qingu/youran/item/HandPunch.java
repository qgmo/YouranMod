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
        if (world == null || world.isRemote) {
            return;
        }
        resolve(stack, world.rand);
    }

    /** 有标记就掷一次，去掉标记（调用的一方保证在服务端） */
    public static void resolve(ItemStack stack, java.util.Random rand) {
        if (stack.isEmpty() || !stack.hasTagCompound() || !stack.getTagCompound().hasKey(TAG, 10)) {
            return;
        }
        NBTTagCompound t = stack.getTagCompound().getCompoundTag(TAG);
        stack.getTagCompound().removeTag(TAG);
        if (stack.getTagCompound().isEmpty()) {
            stack.setTagCompound(null);
        }
        if (rand.nextInt(1000) < t.getInteger("Chance")) {
            stack.setItemDamage(t.getInteger("Broken"));
        }
    }

    /** 沉浸工程装配机设样板时用的合成格（产物另由 Mixin 在每次复制后掷） */
    static final String IE_PATTERN_INVENTORY = "blusunrize.immersiveengineering.common.util.Utils$InventoryCraftingFalse";

    private static java.lang.reflect.Field eventHandler;

    /**
     * 这个合成格是不是机器在合成（没有玩家拿“合成结果”格）：背后的界面里没有 {@link net.minecraft.inventory.SlotCrafting}。
     * 沉浸工程装配机设样板用的合成格不算（见 {@link #IE_PATTERN_INVENTORY}）；读不到界面时按玩家合成算（照旧拿走时掷）。
     */
    public static boolean isMachineCrafting(net.minecraft.inventory.InventoryCrafting inv) {
        if (inv == null || IE_PATTERN_INVENTORY.equals(inv.getClass().getName())) {
            return false;
        }
        net.minecraft.inventory.Container c = container(inv);
        if (c == null) {
            return false;
        }
        for (net.minecraft.inventory.Slot slot : c.inventorySlots) {
            if (slot instanceof net.minecraft.inventory.SlotCrafting) {
                return false;
            }
        }
        return true;
    }

    private static net.minecraft.inventory.Container container(net.minecraft.inventory.InventoryCrafting inv) {
        try {
            if (eventHandler == null) {
                // InventoryCrafting 只有一个 Container 类型的字段（eventHandler / field_70465_c），按类型找，开发环境和正式环境都对
                for (java.lang.reflect.Field f : net.minecraft.inventory.InventoryCrafting.class.getDeclaredFields()) {
                    if (f.getType() == net.minecraft.inventory.Container.class) {
                        f.setAccessible(true);
                        eventHandler = f;
                        break;
                    }
                }
                if (eventHandler == null) {
                    return null;
                }
            }
            Object o = eventHandler.get(inv);
            return o instanceof net.minecraft.inventory.Container ? (net.minecraft.inventory.Container) o : null;
        } catch (RuntimeException | IllegalAccessException e) {
            return null;
        }
    }
}
