package moe.qingu.youran.litho;

import moe.qingu.youran.registry.YouranTab;
import net.minecraft.block.Block;
import net.minecraft.block.SoundType;
import net.minecraft.block.material.Material;

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
}
