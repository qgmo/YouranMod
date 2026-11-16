package moe.qingu.youran.compat.immersiveengineering;

import blusunrize.immersiveengineering.api.ManualHelper;
import blusunrize.immersiveengineering.api.ManualPageMultiblock;
import blusunrize.lib.manual.ManualInstance;
import blusunrize.lib.manual.ManualPages;
import moe.qingu.youran.YouranMod;
import moe.qingu.youran.registry.ModBlocks;
import moe.qingu.youran.registry.ModItems;
import net.minecraft.item.ItemStack;
import net.minecraftforge.fml.relauncher.Side;
import net.minecraftforge.fml.relauncher.SideOnly;

/**
 * 工程师手册里的光刻机条目（重型机械分类），18 页。页序照玩家第一次用的顺序：搭建 → 对准台与光源的合成 → 接管接电 → 要备的料 →
 * 放料 → 界面 → 工序 → 对准 → 换光源 → 准分子激光光源 → 激光对准。文字在本模组的语言文件里，键照沉浸工程
 * {@code ie.manual.entry.lithography<序号>}（三份语言文件同步）。只在客户端、装了沉浸工程时调用。
 *
 * <p>每页放得下多少字（沉浸工程 0.12-98）：正文宽 120 像素，手册强制用 Unicode 字体，汉字宽 9 像素，一行 13 字（加粗 12 字）；
 * 纯文字页 16 行，结构页 6 行，合成页（3×3）10 行、（一行的无序配方）14 行，物品展示页（1–3 个物品）12 行。
 *
 * <p>写正文要注意：正文会被当成格式串，{@code %} 要写成 {@code %%}（否则整页显示 “Format error:”）；中文里不写半角空格
 * （原版换行会退到上一个空格处，行会变短）；原版不做避头尾，改字后看一下句号、顿号有没有落到行首。
 */
@SideOnly(Side.CLIENT)
public final class LithoManual {
    private LithoManual() {
    }

    public static void register() {
        ManualInstance m = ManualHelper.getManual();
        if (m == null) {
            YouranMod.LOG.warn("沉浸工程的手册还没建好，不加光刻机条目");
            return;
        }
        ManualHelper.addEntry("lithography", ManualHelper.CAT_HEAVYMACHINES,
                // 0 搭建
                new ManualPageMultiblock(m, "lithography0", LithoMultiblock.INSTANCE),
                // 1 对准台（工作台配方）
                new ManualPages.Crafting(m, "lithography1", new ItemStack(ModBlocks.alignmentTable)),
                // 2 汞灯光源（汞灯 + 泛光灯）
                new ManualPages.Crafting(m, "lithography2", new ItemStack(ModBlocks.mercuryLightSource)),
                // 3 背面的五个流体口
                new ManualPages.Text(m, "lithography3"),
                // 4 电、物品口、红石
                new ManualPages.Text(m, "lithography4"),
                // 5 要备的料
                new ManualPages.Text(m, "lithography5"),
                // 6 打开界面、放料
                new ManualPages.Text(m, "lithography6"),
                // 7 看懂界面
                new ManualPages.Text(m, "lithography7"),
                // 8 工序
                new ManualPages.Text(m, "lithography8"),
                // 9 显影用什么、能做多细
                new ManualPages.Text(m, "lithography9"),
                // 10–12 对准：显微镜、旋钮与键盘、对准率
                new ManualPages.Text(m, "lithography10"),
                new ManualPages.Text(m, "lithography11"),
                new ManualPages.Text(m, "lithography12"),
                // 13 换光源、拆机器
                new ManualPages.Text(m, "lithography13"),
                // 14 准分子激光光源（ArF 激光腔 + 泛光灯）
                new ManualPages.Crafting(m, "lithography14", new ItemStack(ModBlocks.excimerLightSource)),
                // 15 ArF 激光腔
                new ManualPages.ItemDisplay(m, "lithography15", new ItemStack(ModItems.arfLaserCavity)),
                // 16 激光对准要的两样东西：个人电脑主板、氦氖激光管
                new ManualPages.ItemDisplay(m, "lithography16", new ItemStack(ModItems.motherboard, 1, 1),
                        new ItemStack(ModItems.heneLaserTube)),
                // 17 激光对准怎么用
                new ManualPages.Text(m, "lithography17"));
    }
}
