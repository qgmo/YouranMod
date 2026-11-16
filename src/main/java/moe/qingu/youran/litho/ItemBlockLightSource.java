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

/** 光源方块的物品：不能堆叠（两盏灯的数据不会合并），悬停说明显示玻璃管、亮度、已用 / 寿命 */
public class ItemBlockLightSource extends ItemBlock {
    public ItemBlockLightSource(Block block) {
        super(block);
        setRegistryName(block.getRegistryName());
        setMaxStackSize(1);
    }

    @SideOnly(Side.CLIENT)
    @Override
    public void addInformation(ItemStack stack, @Nullable World world, List<String> lines, ITooltipFlag flag) {
        lines.add(I18n.format("tile.mercury_light_source.tooltip"));
        ItemLampPart.describe(LampNbt.read(stack.getTagCompound()), true, lines);
    }
}
