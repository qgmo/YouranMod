package moe.qingu.youran.mixin.immersiveengineering;

import blusunrize.immersiveengineering.common.blocks.metal.TileEntityAssembler;
import moe.qingu.youran.item.HandPunch;
import net.minecraft.item.ItemStack;
import net.minecraft.tileentity.TileEntity;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Redirect;

/**
 * 沉浸工程装配机：样板的产物只在设样板时算一次，之后每次合成复制那一份。手工冲孔（{@link HandPunch}）的产物带着“还没掷”的标记，
 * 装配机做出来的直接进箱子、不经过玩家物品栏，就永远不掷、绕过了打坏的概率。这里在每次复制产物之后掷一次。
 */
@Mixin(value = TileEntityAssembler.class, remap = false)
public abstract class TileEntityAssemblerMixin {
    @Redirect(method = "update", remap = true, at = @At(value = "INVOKE", ordinal = 0, remap = true,
            target = "Lnet/minecraft/item/ItemStack;copy()Lnet/minecraft/item/ItemStack;"))
    private ItemStack youran$rollHandPunch(ItemStack pattern) {
        ItemStack out = pattern.copy();
        HandPunch.resolve(out, ((TileEntity) (Object) this).getWorld());
        return out;
    }
}
