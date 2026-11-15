package moe.qingu.youran.mixin.ic2;

import ic2.core.block.comp.Fluids;
import net.minecraftforge.fluids.Fluid;
import net.minecraftforge.fluids.FluidStack;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Redirect;

/**
 * IC2 机器、储罐对外的流体能力（{@code Fluids$FluidHandler}）抽流体时保住 NBT。
 *
 * <p>IC2 原来的 {@code drain(FluidStack, boolean)} 按流体种类找罐，却拿 {@code new FluidStack(流体, 0)} 攒结果，
 * 抽出来的流体一律没有 NBT；{@code drain(int, boolean)} 也是转到它。三氯氢硅的纯度记在 NBT 里，从太阳能蒸馏机、
 * 装罐机、IC2 储罐用管道抽出来就都成了 90%。这里把结果换成带上请求的 NBT 的（{@code new FluidStack(请求, 0)}）：
 * 按数量抽（{@code drain(int)}）时请求就是罐里那份，NBT 原样带出；别人拿不带 NBT 的请求来抽时照旧不带
 * （按种类匹配不变，不会因为 NBT 不同就抽不出来）。
 *
 * <p>请求带 NBT 时只从 NBT 相同的罐里抽（{@link #youran$sameTagOnly}）：不然拿“99.9%”的请求去抽装着 90% 的罐，
 * 抽出来的会带上请求的标记，等于把纯度洗高。请求不带 NBT 时照原来按种类抽，结果不带标记（只会变成 90%，不会变高）。
 */
@Mixin(targets = "ic2.core.block.comp.Fluids$FluidHandler", remap = false)
public abstract class FluidsHandlerMixin {
    @Redirect(method = "drain(Lnet/minecraftforge/fluids/FluidStack;Z)Lnet/minecraftforge/fluids/FluidStack;",
            at = @At(value = "NEW", target = "(Lnet/minecraftforge/fluids/Fluid;I)Lnet/minecraftforge/fluids/FluidStack;"))
    private FluidStack youran$keepTag(Fluid fluid, int amount, FluidStack resource, boolean doDrain) {
        return new FluidStack(resource, amount);
    }

    @Redirect(method = "drain(Lnet/minecraftforge/fluids/FluidStack;Z)Lnet/minecraftforge/fluids/FluidStack;",
            at = @At(value = "INVOKE", target = "Lic2/core/block/comp/Fluids$InternalFluidTank;getFluid()Lnet/minecraftforge/fluids/FluidStack;"))
    private FluidStack youran$sameTagOnly(Fluids.InternalFluidTank tank, FluidStack resource, boolean doDrain) {
        FluidStack inTank = tank.getFluid();
        if (inTank != null && resource.tag != null && !inTank.isFluidEqual(resource)) {
            return null;
        }
        return inTank;
    }
}
