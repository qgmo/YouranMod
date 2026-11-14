package moe.qingu.youran.recipe;

import net.minecraft.item.Item;
import net.minecraft.item.ItemStack;
import net.minecraft.nbt.NBTTagCompound;
import net.minecraft.util.ResourceLocation;
import net.minecraftforge.fml.common.registry.ForgeRegistries;

import javax.annotation.Nullable;

/**
 * 反应的输出物品：按注册名记，用到时再找物品（内置配方在物品注册之前就登记了）。
 */
public final class ReactionItem {
    private final String itemId;
    private final int meta;
    private final int count;
    @Nullable
    private final NBTTagCompound tag;

    public ReactionItem(String itemId, int meta, int count, @Nullable NBTTagCompound tag) {
        if (itemId == null || itemId.isEmpty()) {
            throw new IllegalArgumentException("输出物品的注册名不能为空");
        }
        if (count <= 0) {
            throw new IllegalArgumentException("输出物品的数量要大于 0：" + count);
        }
        this.itemId = itemId;
        this.meta = meta;
        this.count = count;
        this.tag = tag == null ? null : tag.copy();
    }

    /** 照着一个物品记下（注册名、元数据、数量、NBT） */
    public static ReactionItem of(ItemStack stack) {
        if (stack.isEmpty() || stack.getItem().getRegistryName() == null) {
            throw new IllegalArgumentException("输出物品不能为空");
        }
        return new ReactionItem(stack.getItem().getRegistryName().toString(), stack.getMetadata(), stack.getCount(), stack.getTagCompound());
    }

    public String itemId() {
        return itemId;
    }

    /** 做出来的物品；这个注册名的物品不存在时为空物品 */
    public ItemStack toStack() {
        Item item = ForgeRegistries.ITEMS.getValue(new ResourceLocation(itemId));
        if (item == null) {
            return ItemStack.EMPTY;
        }
        ItemStack stack = new ItemStack(item, count, meta);
        if (tag != null) {
            stack.setTagCompound(tag.copy());
        }
        return stack;
    }

    @Override
    public String toString() {
        return "<" + itemId + ":" + meta + ">" + (tag == null ? "" : tag.toString()) + " * " + count;
    }
}
