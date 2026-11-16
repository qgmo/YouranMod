package moe.qingu.youran.server;

import moe.qingu.youran.fluid.FluidPurity;
import net.minecraft.entity.player.EntityPlayerMP;
import net.minecraft.init.MobEffects;
import net.minecraft.item.ItemStack;
import net.minecraft.potion.PotionEffect;
import net.minecraft.util.NonNullList;
import net.minecraftforge.fluids.FluidStack;
import net.minecraftforge.fluids.FluidUtil;
import net.minecraftforge.fluids.UniversalBucket;

/**
 * 桶装的有毒挥发液体带在身上（服主 11-16）：三氯氢硅、三氯氢硅氢气混合气、四氯化锡（{@value #TIN_TETRACHLORIDE}）。
 * <ul>
 *   <li>挥发出来的有毒气体：只要身上（物品栏、副手、盔甲栏）有这样一桶，就一直凋零 I，时长保持在 {@value #WITHER_SECONDS} 秒；</li>
 *   <li>三氯氢硅和混合气：桶是敞口的，不论哪里来的，纯度一律截到最多 {@value #MAX_NINES} 个 9（只有管道、罐子、单元这些封闭容器里才保得住高纯度）。</li>
 * </ul>
 * 每秒查一次；创造、旁观模式不管。
 */
public final class ToxicCarry {
    public static final int MAX_NINES = 3;
    public static final int WITHER_SECONDS = 30;
    /** 四氯化锡的流体名（光刻负胶链；在潮湿空气里冒氯化氢白烟） */
    public static final String TIN_TETRACHLORIDE = "tin_tetrachloride";

    private ToxicCarry() {
    }

    /** 每秒调用一次 */
    static void check(EntityPlayerMP player) {
        if (player.isSpectator() || player.capabilities.isCreativeMode) {
            return;
        }
        boolean toxic = scan(player.inventory.mainInventory) | scan(player.inventory.offHandInventory) | scan(player.inventory.armorInventory);
        if (toxic) {
            player.addPotionEffect(new PotionEffect(MobEffects.WITHER, WITHER_SECONDS * 20, 0));
        }
    }

    /** 找出桶装的有毒挥发液体，顺手把三氯氢硅、混合气的纯度截到最多 3 个 9；有就返回 true */
    private static boolean scan(NonNullList<ItemStack> slots) {
        boolean found = false;
        for (int i = 0; i < slots.size(); i++) {
            ItemStack stack = slots.get(i);
            if (stack.isEmpty() || !(stack.getItem() instanceof UniversalBucket)) {
                continue;
            }
            FluidStack fs = FluidUtil.getFluidContained(stack);
            if (fs == null) {
                continue;
            }
            if (TIN_TETRACHLORIDE.equals(fs.getFluid().getName())) {
                found = true;
                continue;
            }
            if (!FluidPurity.isTrichlorosilane(fs) && !FluidPurity.isMixture(fs)) {
                continue;
            }
            found = true;
            int nines = FluidPurity.nines(fs);
            if (nines > MAX_NINES) {
                ItemStack capped = FluidUtil.getFilledBucket(FluidPurity.withNines(fs.getFluid(), fs.amount, cap(nines)));
                if (!capped.isEmpty()) {
                    capped.setCount(stack.getCount());
                    slots.set(i, capped);
                }
            }
        }
        return found;
    }

    /** 截到最多 {@value #MAX_NINES} 个 9 */
    public static int cap(int nines) {
        return Math.min(nines, MAX_NINES);
    }
}
