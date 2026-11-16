package moe.qingu.youran.mixin.ic2;

import ic2.core.block.machine.tileentity.TileEntityStandardMachine;
import moe.qingu.youran.compat.ic2.CentrifugeHooks;
import net.minecraft.item.ItemStack;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.ModifyVariable;

import java.util.Collection;

/** IC2 标准机器做完一次时（{@code operateOnce} 开头）：热能离心机分铜矿按几率多出小撮钼粉（{@link CentrifugeHooks}）；别的机器照原来的走 */
@Mixin(value = TileEntityStandardMachine.class, remap = false)
public abstract class TileEntityStandardMachineMixin {
    @ModifyVariable(method = "operateOnce", at = @At("HEAD"), argsOnly = true)
    private Collection<ItemStack> youran$byproduct(Collection<ItemStack> processResult) {
        return CentrifugeHooks.withByproduct((TileEntityStandardMachine<?, ?, ?>) (Object) this, processResult);
    }
}
