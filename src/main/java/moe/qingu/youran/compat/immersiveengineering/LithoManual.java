package moe.qingu.youran.compat.immersiveengineering;

import blusunrize.immersiveengineering.api.ManualHelper;
import blusunrize.immersiveengineering.api.ManualPageMultiblock;
import blusunrize.lib.manual.ManualPages;
import moe.qingu.youran.YouranMod;
import moe.qingu.youran.registry.ModBlocks;
import moe.qingu.youran.registry.ModItems;
import net.minecraft.item.ItemStack;
import net.minecraftforge.fml.relauncher.Side;
import net.minecraftforge.fml.relauncher.SideOnly;

/**
 * 工程师手册里的光刻机条目（重型机械分类）：结构页 + 几页文字（DUV 一档：准分子激光光源、激光对准，服主 11-16）。文字在本模组的语言文件里，键照沉浸工程
 * {@code ie.manual.entry.lithography<序号>}（沉浸原油也这样）。只在客户端、装了沉浸工程时调用。
 */
@SideOnly(Side.CLIENT)
public final class LithoManual {
    private LithoManual() {
    }

    public static void register() {
        if (ManualHelper.getManual() == null) {
            YouranMod.LOG.warn("沉浸工程的手册还没建好，不加光刻机条目");
            return;
        }
        ManualHelper.addEntry("lithography", ManualHelper.CAT_HEAVYMACHINES,
                new ManualPageMultiblock(ManualHelper.getManual(), "lithography0", LithoMultiblock.INSTANCE),
                new ManualPages.Text(ManualHelper.getManual(), "lithography1"),
                new ManualPages.Text(ManualHelper.getManual(), "lithography2"),
                new ManualPages.Text(ManualHelper.getManual(), "lithography3"),
                new ManualPages.ItemDisplay(ManualHelper.getManual(), "lithography4", new ItemStack(ModBlocks.alignmentTable),
                        new ItemStack(ModBlocks.mercuryLightSource), new ItemStack(ModBlocks.excimerLightSource)),
                // DUV 一档（服主 11-16）：准分子激光光源、激光对准
                new ManualPages.ItemDisplay(ManualHelper.getManual(), "lithography5", new ItemStack(ModItems.arfLaserCavity),
                        new ItemStack(ModBlocks.excimerLightSource)),
                new ManualPages.ItemDisplay(ManualHelper.getManual(), "lithography6", new ItemStack(ModItems.motherboard, 1, 1),
                        new ItemStack(ModItems.heneLaserTube)),
                new ManualPages.Text(ManualHelper.getManual(), "lithography7"));
    }
}
