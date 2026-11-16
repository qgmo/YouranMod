package moe.qingu.youran.item;

import moe.qingu.youran.logic.litho.DuvNumbers;
import moe.qingu.youran.logic.litho.HeNeSpec;
import moe.qingu.youran.logic.litho.LampGlass;
import moe.qingu.youran.logic.litho.LampSpec;
import moe.qingu.youran.logic.purity.WaferMold;
import net.minecraft.client.resources.I18n;
import net.minecraft.client.util.ITooltipFlag;
import net.minecraft.creativetab.CreativeTabs;
import net.minecraft.item.ItemStack;
import net.minecraft.util.NonNullList;
import net.minecraft.world.World;
import net.minecraftforge.fml.relauncher.Side;
import net.minecraftforge.fml.relauncher.SideOnly;

import javax.annotation.Nullable;
import java.util.ArrayList;
import java.util.List;

/**
 * 氦氖激光管（服主 11-16，633 nm）：光刻机对准台“激光源”格用。沉浸工程工程师装配台“光源”蓝图做出来是空的，
 * 再在 IC2 固体装罐机里一个一个放氦、氖元素（{@code compat.ic2.GasFillHooks}），最多 {@value DuvNumbers#HENE_MAX_ELEMENTS} 个。
 * 数据见 {@link LaserNbt}；氦会慢慢穿过玻璃漏掉（{@link HeNeSpec}），补氦恢复。
 */
public class ItemHeNeTube extends ItemLangTooltip {
    public ItemHeNeTube(String name) {
        super(name, 1);
    }

    public static HeNeSpec spec(ItemStack s) {
        return LaserNbt.hene(s.getTagCompound());
    }

    public ItemStack make(HeNeSpec spec) {
        ItemStack s = new ItemStack(this);
        s.setTagCompound(LaserNbt.tag(spec));
        return s;
    }

    @Override
    public void getSubItems(CreativeTabs tab, NonNullList<ItemStack> items) {
        if (isInCreativeTab(tab)) {
            items.add(make(HeNeSpec.empty(LampGlass.GLASS, WaferMold.GOLD, 1)));
        }
    }

    @SideOnly(Side.CLIENT)
    @Override
    public void addInformation(ItemStack stack, @Nullable World world, List<String> lines, ITooltipFlag flag) {
        super.addInformation(stack, world, lines, flag);
        long now = world == null ? -1 : world.getTotalWorldTime();
        describe(spec(stack), now, lines);
    }

    /** 玻璃管、氦 / 氖、合计、预计输出刻度 */
    @SideOnly(Side.CLIENT)
    public static void describe(HeNeSpec s, long now, List<String> lines) {
        List<String> tube = new ArrayList<>();
        ItemLampPart.describe(LampSpec.tube(s.glass, s.mold, s.nines), false, tube);
        lines.addAll(tube);
        long t = now < 0 ? s.stamp : now;
        lines.add(I18n.format("youranmod.hene.gas", String.format(java.util.Locale.ROOT, "%.1f", s.heliumAt(t)), s.neon, s.elementsAt(t),
                DuvNumbers.HENE_MAX_ELEMENTS));
        double out = s.output(t);
        lines.add(I18n.format("youranmod.laser.output", ItemLaserCavity.gauge(out), Math.round(out * 100)));
        // 漏氦不写在提示里（服主 11-16：看不见的物理留给玩家自己发现；氦的数量会慢慢变少）
    }
}
