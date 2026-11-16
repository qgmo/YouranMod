package moe.qingu.youran.compat.ic2;

import ic2.api.recipe.ICannerEnrichRecipeManager;
import ic2.api.recipe.MachineRecipe;
import ic2.api.recipe.MachineRecipeResult;
import ic2.core.block.machine.tileentity.TileEntityCanner;
import ic2.core.util.StackUtil;
import moe.qingu.youran.fluid.FluidVariants;
import moe.qingu.youran.logic.litho.DuvNumbers;
import moe.qingu.youran.logic.litho.ResistType;
import net.minecraft.item.ItemStack;
import net.minecraftforge.fluids.FluidStack;
import net.minecraftforge.fluids.FluidUtil;
import net.minecraftforge.fluids.capability.IFluidHandlerItem;

import javax.annotation.Nullable;

/**
 * 改良正胶（服主 11-16）在 IC2 装罐机里做：富集模式，输入罐里是正胶，输入格放一个装着 UU 物质的流体容器（IC2 单元、沉浸工程油桶等，
 * 一次放一个）。每 {@value DuvNumbers#IMPROVED_RESIST_MB} mB 正胶从容器里抽 {@value DuvNumbers#IMPROVED_UU_MB} mB UU 物质，出同样多的改良正胶，
 * 容器留在格子里接着用（抽空了就是空容器）。
 * <p>UU 物质是流体，装罐机的富集配方是“流体 + 物品”，两种流体放不进同一个罐；这里把 UU 物质当成“滴管里的添加物”。
 * 变质的正胶加 UU 物质还是变质的（照常出“变质的改良正胶”，光刻机照样收、做出来照样报废）。
 */
public final class UuDosingHooks {
    public static final String UU = "ic2uu_matter";

    private UuDosingHooks() {
    }

    /** 这个容器里有多少 UU 物质（mB）；不是装 UU 的容器为 0 */
    static int uuIn(ItemStack s) {
        if (StackUtil.isEmpty(s)) {
            return 0;
        }
        IFluidHandlerItem h = FluidUtil.getFluidHandler(StackUtil.copyWithSize(s, 1));
        if (h == null) {
            return 0;
        }
        FluidStack f = h.drain(Integer.MAX_VALUE, false);
        return f != null && f.getFluid() != null && UU.equals(f.getFluid().getName()) ? f.amount : 0;
    }

    /** 抽掉 {@code mB} UU 物质以后的容器；抽不出这么多时为 null */
    @Nullable
    static ItemStack drained(ItemStack s, int mB) {
        IFluidHandlerItem h = FluidUtil.getFluidHandler(StackUtil.copyWithSize(s, 1));
        if (h == null) {
            return null;
        }
        FluidStack f = h.drain(mB, true);
        if (f == null || f.amount < mB || f.getFluid() == null || !UU.equals(f.getFluid().getName())) {
            return null;
        }
        return h.getContainer();
    }

    static boolean positive(@Nullable FluidStack tank) {
        ResistType t = FluidVariants.resist(tank);
        return t == ResistType.POSITIVE;
    }

    /** 富集模式下这一 tick 由不由这里回答（调用方已确认是富集模式） */
    public static CannerReactionHooks.Outcome getOutput(TileEntityCanner canner) {
        FluidStack tank = canner.inputTank.getFluid();
        ItemStack additive = canner.inputSlot.get();
        if (!positive(tank) || uuIn(additive) <= 0) {
            return CannerReactionHooks.Outcome.NOT_HANDLED;
        }
        if (tank.amount < DuvNumbers.IMPROVED_RESIST_MB || StackUtil.getSize(additive) != 1) {
            return CannerReactionHooks.Outcome.IDLE;
        }
        ItemStack left = drained(additive, DuvNumbers.IMPROVED_UU_MB);
        if (left == null) {
            return CannerReactionHooks.Outcome.IDLE;
        }
        FluidStack out = FluidVariants.photoresist(ResistType.IMPROVED_POSITIVE, DuvNumbers.IMPROVED_RESIST_MB, FluidVariants.isExposedResist(tank));
        if (canner.outputTank.fill(out, false) != out.amount) {
            return CannerReactionHooks.Outcome.IDLE;
        }
        FluidStack rest = tank.copy();
        rest.amount -= DuvNumbers.IMPROVED_RESIST_MB;
        ICannerEnrichRecipeManager.RawInput adjusted = new ICannerEnrichRecipeManager.RawInput(rest.amount > 0 ? rest : null, left);
        CannerReactionOutput output = new CannerReactionOutput(out, ItemStack.EMPTY);
        return new CannerReactionHooks.Outcome(true, new MachineRecipeResult<>(new MachineRecipe<Object, Object>(adjusted, output), adjusted));
    }

    /** 富集模式下输入格收不收这个容器：装着 UU 物质、罐里是正胶或空着 */
    public static boolean accepts(TileEntityCanner canner, ItemStack stack) {
        FluidStack tank = canner.inputTank.getFluid();
        return uuIn(stack) > 0 && (tank == null || tank.amount <= 0 || positive(tank));
    }
}
