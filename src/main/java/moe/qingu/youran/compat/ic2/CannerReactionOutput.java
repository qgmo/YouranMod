package moe.qingu.youran.compat.ic2;

import net.minecraft.item.ItemStack;
import net.minecraftforge.fluids.FluidStack;

import javax.annotation.Nullable;
import java.util.Collection;
import java.util.Collections;

/**
 * 装罐机做反应配方时 {@code MachineRecipeResult#getOutput()} 的值：流体输出罐要装的流体与物品输出槽要放的物品。
 * IC2 自己的富集配方的输出是 {@link FluidStack}，用不同的类型区分开（{@code mixin.ic2.TileEntityCannerMixin}）。
 */
public final class CannerReactionOutput {
    @Nullable
    private final FluidStack fluid;
    private final ItemStack item;

    CannerReactionOutput(@Nullable FluidStack fluid, ItemStack item) {
        this.fluid = fluid;
        this.item = item;
    }

    /** 不出流体时为 null */
    @Nullable
    public FluidStack fluid() {
        return fluid == null ? null : fluid.copy();
    }

    /** 物品输出槽要放的东西（每次一份新的，同 IC2 的 {@code StackUtil.copy}）；不出物品时为空列表 */
    public Collection<ItemStack> items() {
        return item.isEmpty() ? Collections.<ItemStack>emptyList() : Collections.singletonList(item.copy());
    }
}
