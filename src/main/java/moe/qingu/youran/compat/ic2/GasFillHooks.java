package moe.qingu.youran.compat.ic2;

import ic2.api.recipe.ICannerBottleRecipeManager;
import ic2.api.recipe.MachineRecipe;
import ic2.api.recipe.MachineRecipeResult;
import ic2.core.block.machine.tileentity.TileEntityCanner;
import ic2.core.util.StackUtil;
import moe.qingu.youran.item.ItemHeNeTube;
import moe.qingu.youran.item.ItemLaserCavity;
import moe.qingu.youran.logic.litho.HeNeSpec;
import net.minecraft.item.ItemStack;
import net.minecraft.util.ResourceLocation;

/**
 * IC2 固体装罐机给激光管充气（服主 11-16），由 {@code mixin.ic2.TileEntityCannerMixin} 在固体装罐模式下调用：
 * <ul>
 *     <li>ArF 激光腔放“罐子”格，输入格每次放一个炼金化学氟元素或氖元素 → 腔里多一个（{@code item.LaserNbt}）；</li>
 *     <li>氦氖激光管放“罐子”格，每次一个氦元素或氖元素，满 250 个（合计）就不再做。</li>
 * </ul>
 * 配方表里登记了这几条（{@code recipe.LithoRecipes}，JEI 与装罐机收不收东西都靠它），产物要按罐子里那一件的数据现算，所以由这里回答。
 * 不是这几样时照 IC2 原来的走。
 */
public final class GasFillHooks {
    static final ResourceLocation ELEMENT = new ResourceLocation("alchemistry", "element");
    public static final int HELIUM = 2;
    public static final int FLUORINE = 9;
    public static final int NEON = 10;

    private GasFillHooks() {
    }

    static int element(ItemStack s) {
        return !s.isEmpty() && ELEMENT.equals(s.getItem().getRegistryName()) ? s.getMetadata() : -1;
    }

    /** 固体装罐模式下这一 tick 由不由这里回答、做的是什么（调用方已确认是固体装罐模式） */
    public static CannerReactionHooks.Outcome getOutput(TileEntityCanner canner) {
        ItemStack container = canner.canInputSlot.get();
        ItemStack fill = canner.inputSlot.get();
        if (StackUtil.isEmpty(container) || StackUtil.isEmpty(fill)) {
            return CannerReactionHooks.Outcome.NOT_HANDLED;
        }
        int z = element(fill);
        ItemStack out;
        if (container.getItem() instanceof ItemLaserCavity) {
            if (z != FLUORINE && z != NEON) {
                return CannerReactionHooks.Outcome.NOT_HANDLED;
            }
            ItemLaserCavity item = (ItemLaserCavity) container.getItem();
            out = item.make(z == FLUORINE ? ItemLaserCavity.spec(container).plusFluorine() : ItemLaserCavity.spec(container).plusNeon());
        } else if (container.getItem() instanceof ItemHeNeTube) {
            if (z != HELIUM && z != NEON) {
                return CannerReactionHooks.Outcome.NOT_HANDLED;
            }
            long now = canner.getWorld().getTotalWorldTime();
            HeNeSpec s = ItemHeNeTube.spec(container);
            if (!s.hasRoom(now)) {
                return CannerReactionHooks.Outcome.IDLE;
            }
            out = ((ItemHeNeTube) container.getItem()).make(z == HELIUM ? s.plusHelium(now) : s.plusNeon(now));
        } else {
            return CannerReactionHooks.Outcome.NOT_HANDLED;
        }
        if (!canner.outputSlot.canAdd(out)) {
            return CannerReactionHooks.Outcome.IDLE;
        }
        ICannerBottleRecipeManager.RawInput adjusted = new ICannerBottleRecipeManager.RawInput(StackUtil.copyShrunk(container, 1),
                StackUtil.copyShrunk(fill, 1));
        return new CannerReactionHooks.Outcome(true, new MachineRecipeResult<>(new MachineRecipe<Object, Object>(adjusted, out), adjusted));
    }
}
