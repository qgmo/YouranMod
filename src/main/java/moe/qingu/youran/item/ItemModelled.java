package moe.qingu.youran.item;

import moe.qingu.youran.logic.litho.ChipModel;
import net.minecraft.creativetab.CreativeTabs;
import net.minecraft.item.ItemStack;
import net.minecraft.nbt.NBTTagCompound;
import net.minecraft.util.NonNullList;

import javax.annotation.Nullable;

/** 按型号区分的物品（掩模、芯片设计图、芯片）：NBT 里 {@code Model}，名字是“<型号> 掩模”这样，一个一组 */
public class ItemModelled extends ItemLangTooltip {
    public ItemModelled(String name) {
        super(name, 1);
    }

    public ItemStack make(ChipModel model) {
        ItemStack stack = new ItemStack(this);
        NBTTagCompound tag = new NBTTagCompound();
        tag.setString(ChipModel.TAG, model.id);
        stack.setTagCompound(tag);
        return stack;
    }

    @Nullable
    public static ChipModel model(ItemStack stack) {
        return stack.hasTagCompound() ? ChipModel.byId(stack.getTagCompound().getString(ChipModel.TAG)) : null;
    }

    @SuppressWarnings("deprecation")
    @Override
    public String getItemStackDisplayName(ItemStack stack) {
        ChipModel m = model(stack);
        String name = m == null ? "?" : net.minecraft.util.text.translation.I18n.translateToLocal("youranmod.chip.model." + m.id);
        return net.minecraft.util.text.translation.I18n.translateToLocalFormatted(getTranslationKey() + ".name", name);
    }

    @Override
    public void getSubItems(CreativeTabs tab, NonNullList<ItemStack> items) {
        if (isInCreativeTab(tab)) {
            for (ChipModel m : ChipModel.values()) {
                items.add(make(m));
            }
        }
    }
}
