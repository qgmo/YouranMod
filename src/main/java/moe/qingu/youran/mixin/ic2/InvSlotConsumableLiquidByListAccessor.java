package moe.qingu.youran.mixin.ic2;

import ic2.core.block.invslot.InvSlotConsumableLiquidByList;
import net.minecraftforge.fluids.Fluid;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

import java.util.Set;

/** 取 IC2 按流体列表收容器的输入槽里那份列表（{@code HashSet}，可以往里加），给太阳能蒸馏机的输入槽加上三氯氢硅 */
@Mixin(value = InvSlotConsumableLiquidByList.class, remap = false)
public interface InvSlotConsumableLiquidByListAccessor {
    @Accessor("acceptedFluids")
    Set<Fluid> youran$acceptedFluids();
}
