package moe.qingu.youran.item;

import moe.qingu.youran.registry.YouranTab;
import net.minecraft.client.resources.I18n;
import net.minecraft.client.util.ITooltipFlag;
import net.minecraft.item.Item;
import net.minecraft.item.ItemStack;
import net.minecraft.world.World;
import net.minecraftforge.fml.relauncher.Side;
import net.minecraftforge.fml.relauncher.SideOnly;

import javax.annotation.Nullable;
import java.util.List;

/** 普通物品，说明文字走语言文件（键 {@code item.<注册名>.tooltip}），光刻线的新物品用 */
public class ItemLangTooltip extends Item {
    public ItemLangTooltip(String name, int maxStackSize) {
        setRegistryName(name);
        setTranslationKey(name);
        setMaxStackSize(maxStackSize);
        setCreativeTab(YouranTab.tab);
    }

    @SideOnly(Side.CLIENT)
    @Override
    public void addInformation(ItemStack stack, @Nullable World world, List<String> lines, ITooltipFlag flag) {
        super.addInformation(stack, world, lines, flag);
        String key = getTranslationKey() + ".tooltip";
        if (I18n.hasKey(key)) {
            lines.add(I18n.format(key));
        }
    }
}
