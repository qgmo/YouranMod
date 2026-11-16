package moe.qingu.youran.litho;

import moe.qingu.youran.registry.YouranTab;
import net.minecraft.block.Block;
import net.minecraft.block.SoundType;
import net.minecraft.block.material.Material;
import net.minecraft.client.resources.I18n;
import net.minecraft.client.util.ITooltipFlag;
import net.minecraft.item.ItemStack;
import net.minecraft.world.World;
import net.minecraftforge.fml.relauncher.Side;
import net.minecraftforge.fml.relauncher.SideOnly;

import javax.annotation.Nullable;
import java.util.List;

/**
 * 对准台（设计稿 2.6）：工作台 + 显微镜 + 掩模架合在一格。是光刻机的锤子触发格（别的多方块不用这个方块，不会撞），
 * 成形后是主方块。没成形时只是一个方块。
 */
public class BlockAlignmentTable extends Block {
    public BlockAlignmentTable(String name) {
        super(Material.IRON);
        setRegistryName(name);
        setTranslationKey(name);
        setHardness(3.0F);
        setResistance(10.0F);
        setSoundType(SoundType.METAL);
        setCreativeTab(YouranTab.tab);
    }

    /** 固定说明（键 tile.alignment_table.tooltip）：缺键时不显示，不冒键名 */
    @SideOnly(Side.CLIENT)
    @Override
    public void addInformation(ItemStack stack, @Nullable World world, List<String> lines, ITooltipFlag flag) {
        String key = getTranslationKey() + ".tooltip";
        if (I18n.hasKey(key)) {
            lines.add(I18n.format(key));
        }
    }
}
