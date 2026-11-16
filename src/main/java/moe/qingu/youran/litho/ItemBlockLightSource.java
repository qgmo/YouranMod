package moe.qingu.youran.litho;

import moe.qingu.youran.item.ItemLampPart;
import moe.qingu.youran.item.LampNbt;
import net.minecraft.block.Block;
import net.minecraft.client.resources.I18n;
import net.minecraft.client.util.ITooltipFlag;
import net.minecraft.item.ItemBlock;
import net.minecraft.item.ItemStack;
import net.minecraft.world.World;
import net.minecraftforge.fml.relauncher.Side;
import net.minecraftforge.fml.relauncher.SideOnly;

import javax.annotation.Nullable;
import java.util.List;

/** 光源方块的物品：不能堆叠（两盏灯的数据不会合并），悬停说明显示玻璃管（激光腔的窗口、气体）、亮度、已用 / 寿命 */
public class ItemBlockLightSource extends ItemBlock {
    public ItemBlockLightSource(Block block) {
        super(block);
        setRegistryName(block.getRegistryName());
        setMaxStackSize(1);
    }

    /**
     * 创造物品栏：汞灯光源带一盏标准的灯（没数据的是没装好的灯，不亮）；准分子激光光源照旧是空激光腔（不公开气体配比，
     * 要自己灌）。取代没有数据的那个。
     */
    @Override
    public void getSubItems(net.minecraft.creativetab.CreativeTabs tab, net.minecraft.util.NonNullList<ItemStack> items) {
        if (!isInCreativeTab(tab)) {
            return;
        }
        ItemStack s = new ItemStack(this);
        if (!(getBlock() instanceof BlockLightSource && ((BlockLightSource) getBlock()).excimer)) {
            s.setTagCompound(moe.qingu.youran.item.CreativeStandards.lamp());
        }
        items.add(s);
    }

    @SideOnly(Side.CLIENT)
    @Override
    public void addInformation(ItemStack stack, @Nullable World world, List<String> lines, ITooltipFlag flag) {
        lines.add(I18n.format(getBlock().getTranslationKey() + ".tooltip"));
        if (getBlock() instanceof BlockLightSource && ((BlockLightSource) getBlock()).excimer) {
            moe.qingu.youran.item.ItemLaserCavity.describe(moe.qingu.youran.item.LaserNbt.excimer(stack.getTagCompound()), lines);
        } else {
            ItemLampPart.describe(LampNbt.read(stack.getTagCompound()), true, lines);
        }
    }
}
