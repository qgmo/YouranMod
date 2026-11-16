package moe.qingu.youran.compat.ic2;

import ic2.api.recipe.ICannerBottleRecipeManager;
import ic2.api.recipe.MachineRecipe;
import ic2.api.recipe.MachineRecipeResult;
import ic2.core.block.invslot.InvSlotOutput;
import ic2.core.block.machine.tileentity.TileEntityCanner;
import ic2.core.block.machine.tileentity.TileEntitySolidCanner;
import ic2.core.util.StackUtil;
import moe.qingu.youran.item.ItemHeNeTube;
import moe.qingu.youran.item.ItemLaserCavity;
import moe.qingu.youran.logic.litho.ExcimerSpec;
import moe.qingu.youran.logic.litho.GasFill;
import moe.qingu.youran.logic.litho.HeNeSpec;
import net.minecraft.item.ItemStack;
import net.minecraft.util.ResourceLocation;

/**
 * IC2 给激光管充气（服主 11-16）：<b>固体装罐机</b>（{@code TileEntitySolidCanner}，由 {@code mixin.ic2.TileEntityStandardMachineMixin} 调用），
 * 以及<b>流体 / 固体装罐机</b>的“装罐”模式（{@code TileEntityCanner}，由 {@code mixin.ic2.TileEntityCannerMixin} 调用），两台走同一套判断
 * （{@link GasFill}）：
 * <ul>
 *     <li>ArF 激光腔放“罐子”格，输入格每次放一个炼金化学氟元素或氖元素 → 腔里多一个（{@code item.LaserNbt}）；氟满 3 个、氖满 48 个就不再做
 *     （服主 11-16），元素不吞；</li>
 *     <li>氦氖激光管放“罐子”格，每次一个氦元素或氖元素，满 250 个（合计）就不再做。</li>
 * </ul>
 * 配方表（{@code Recipes.cannerBottle}）里登记了这几条（{@code recipe.LithoRecipes}），两台机器的格子收不收东西、JEI 列不列都靠它；
 * 但登记的产物只是一件展示用的空管，真正的产物要按罐子格里那一件的数据现算，所以由这里回答——不拦的话固体装罐机会照表出那件空管，原件的数据就丢了。
 * 不是这几样时照 IC2 原来的走。只在服务端被调用。
 */
public final class GasFillHooks {
    static final ResourceLocation ELEMENT = new ResourceLocation("alchemistry", "element");
    public static final int HELIUM = GasFill.HELIUM;
    public static final int FLUORINE = GasFill.FLUORINE;
    public static final int NEON = GasFill.NEON;

    private GasFillHooks() {
    }

    static int element(ItemStack s) {
        return !s.isEmpty() && ELEMENT.equals(s.getItem().getRegistryName()) ? s.getMetadata() : -1;
    }

    /** 流体 / 固体装罐机：“装罐”模式下这一 tick 由不由这里回答、做的是什么（调用方已确认是“装罐”模式） */
    public static CannerReactionHooks.Outcome getOutput(TileEntityCanner canner) {
        return getOutput(canner.canInputSlot.get(), canner.inputSlot.get(), canner.getWorld().getTotalWorldTime(), canner.outputSlot);
    }

    /** 固体装罐机：这一 tick 由不由这里回答、做的是什么 */
    public static CannerReactionHooks.Outcome getOutput(TileEntitySolidCanner canner) {
        return getOutput(canner.canInputSlot.get(), canner.inputSlot.get(), canner.getWorld().getTotalWorldTime(), canner.outputSlot);
    }

    static CannerReactionHooks.Outcome getOutput(ItemStack container, ItemStack fill, long now, InvSlotOutput output) {
        if (StackUtil.isEmpty(container) || StackUtil.isEmpty(fill)) {
            return CannerReactionHooks.Outcome.NOT_HANDLED;
        }
        int z = element(fill);
        ItemStack out;
        if (container.getItem() instanceof ItemLaserCavity) {
            GasFill.Plan<ExcimerSpec> plan = GasFill.cavity(ItemLaserCavity.spec(container), z);
            if (plan.kind == GasFill.Kind.NOT_MINE) {
                return CannerReactionHooks.Outcome.NOT_HANDLED;
            }
            // 到上限不再做，元素留在输入格（同氩满了）
            if (plan.kind == GasFill.Kind.FULL) {
                return CannerReactionHooks.Outcome.IDLE;
            }
            out = ((ItemLaserCavity) container.getItem()).make(plan.spec);
        } else if (container.getItem() instanceof ItemHeNeTube) {
            GasFill.Plan<HeNeSpec> plan = GasFill.hene(ItemHeNeTube.spec(container), z, now);
            if (plan.kind == GasFill.Kind.NOT_MINE) {
                return CannerReactionHooks.Outcome.NOT_HANDLED;
            }
            if (plan.kind == GasFill.Kind.FULL) {
                return CannerReactionHooks.Outcome.IDLE;
            }
            out = ((ItemHeNeTube) container.getItem()).make(plan.spec);
        } else {
            return CannerReactionHooks.Outcome.NOT_HANDLED;
        }
        if (!output.canAdd(out)) {
            return CannerReactionHooks.Outcome.IDLE;
        }
        ICannerBottleRecipeManager.RawInput adjusted = new ICannerBottleRecipeManager.RawInput(StackUtil.copyShrunk(container, 1),
                StackUtil.copyShrunk(fill, 1));
        return new CannerReactionHooks.Outcome(true, new MachineRecipeResult<>(new MachineRecipe<Object, Object>(adjusted, out), adjusted));
    }
}
