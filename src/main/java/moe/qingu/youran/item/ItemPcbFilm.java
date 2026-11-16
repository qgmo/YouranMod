package moe.qingu.youran.item;

import moe.qingu.youran.logic.pcb.BoardType;
import moe.qingu.youran.registry.YouranTab;
import net.minecraft.client.resources.I18n;
import net.minecraft.client.util.ITooltipFlag;
import net.minecraft.creativetab.CreativeTabs;
import net.minecraft.item.Item;
import net.minecraft.item.ItemStack;
import net.minecraft.util.NonNullList;
import net.minecraft.world.World;
import net.minecraftforge.fml.relauncher.Side;
import net.minecraftforge.fml.relauncher.SideOnly;

import javax.annotation.Nullable;
import java.util.List;

/**
 * 线路底片（服主 11-16）：每种板子一张（固定型号，元数据 = {@link BoardType#id}），在紫外擦除器里和涂胶主板一起曝光，底片不消耗。
 * 来源：MineBay 常年有卖（服主随整合包上架）；以后可在紫外擦除器里复制（空白胶片以后再定）。
 */
public class ItemPcbFilm extends Item implements MetaModelled {
    public ItemPcbFilm() {
        setRegistryName("pcb_film");
        setTranslationKey("pcb_film");
        setHasSubtypes(true);
        setMaxDamage(0);
        setMaxStackSize(1);
        setCreativeTab(YouranTab.tab);
    }

    @Nullable
    public static BoardType type(ItemStack stack) {
        return stack.isEmpty() || !(stack.getItem() instanceof ItemPcbFilm) ? null : BoardType.circuitById(stack.getMetadata());
    }

    @SuppressWarnings("deprecation")
    @Override
    public String getItemStackDisplayName(ItemStack stack) {
        BoardType t = type(stack);
        return net.minecraft.util.text.translation.I18n.translateToLocalFormatted("item.pcb_film.name",
                net.minecraft.util.text.translation.I18n.translateToLocal("youranmod.board." + (t == null ? "computer" : t.name)));
    }

    @SideOnly(Side.CLIENT)
    @Override
    public void addInformation(ItemStack stack, @Nullable World world, List<String> lines, ITooltipFlag flag) {
        lines.add(I18n.format("item.pcb_film.tooltip"));
    }

    @Override
    public void getSubItems(CreativeTabs tab, NonNullList<ItemStack> items) {
        if (isInCreativeTab(tab)) {
            for (BoardType t : BoardType.circuits()) {
                items.add(new ItemStack(this, 1, t.id));
            }
        }
    }

    @Override
    public int[] modelMetas() {
        BoardType[] types = BoardType.circuits();
        int[] m = new int[types.length];
        for (int i = 0; i < m.length; i++) {
            m[i] = types[i].id;
        }
        return m;
    }

    @Override
    public String modelName(int meta) {
        BoardType t = BoardType.circuitById(meta);
        return "pcb_film_" + (t == null ? "computer" : t.name);
    }
}
