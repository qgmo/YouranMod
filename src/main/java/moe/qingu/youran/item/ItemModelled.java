package moe.qingu.youran.item;

import moe.qingu.youran.logic.litho.ChipModel;
import net.minecraft.creativetab.CreativeTabs;
import net.minecraft.item.ItemStack;
import net.minecraft.util.NonNullList;

import javax.annotation.Nullable;

/**
 * 按型号区分的物品（掩模、芯片设计图、芯片）：型号就是物品元数据（{@link ChipModel#meta}；服主 11-16：掩模按元数据区分，不按 NBT），
 * 名字是“<型号> 掩模”这样，一个一组。掩模的磨损、芯片的品质照旧在 NBT 里。
 */
public class ItemModelled extends ItemLangTooltip implements MetaModelled {
    public ItemModelled(String name) {
        super(name, 1);
        setHasSubtypes(true);
        setMaxDamage(0);
    }

    public ItemStack make(ChipModel model) {
        return new ItemStack(this, 1, model.meta);
    }

    /** 这个物品的型号（看元数据）；不认得的元数据为 null */
    @Nullable
    public static ChipModel model(ItemStack stack) {
        return stack.isEmpty() || !(stack.getItem() instanceof ItemModelled) ? null : ChipModel.byMeta(stack.getMetadata());
    }

    @SuppressWarnings("deprecation")
    @Override
    public String getItemStackDisplayName(ItemStack stack) {
        ChipModel m = model(stack);
        String name = m == null ? "?" : net.minecraft.util.text.translation.I18n.translateToLocal("youranmod.chip.model." + m.id);
        return net.minecraft.util.text.translation.I18n.translateToLocalFormatted(getTranslationKey() + ".name", name);
    }

    @Override
    public int[] modelMetas() {
        int[] metas = new int[ChipModel.values().length];
        for (int i = 0; i < metas.length; i++) {
            metas[i] = ChipModel.values()[i].meta;
        }
        return metas;
    }

    /** 各型号同一个模型（贴图一样，名字、版图不一样） */
    @Override
    public String modelName(int meta) {
        return getRegistryName().getPath();
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
