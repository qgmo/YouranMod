package moe.qingu.youran.mixin.twilightforest;

import moe.qingu.youran.compat.twilightforest.UncraftGuard;
import net.minecraft.item.ItemStack;
import net.minecraft.item.crafting.IRecipe;
import net.minecraft.world.World;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.Redirect;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;
import twilightforest.inventory.ContainerTFUncrafting;

import java.util.ArrayList;
import java.util.List;

/**
 * 暮色森林拆解台（服主 11-17：会刷物、洗数据的拆解禁止掉；规则见 {@link UncraftGuard}）：
 * <ul>
 *     <li>拆解台为放进去的东西找配方（{@code getRecipesFor}，整合包里的 Twilight Tweaks 用 {@code @Overwrite} 改过它）之后，
 *     去掉不让拆的配方；一条都不剩时拆解台什么都不显示，同放进一件没有配方的东西；</li>
 *     <li>拆解台为一条配方摆材料（{@code getIngredients}）时，一格有几种候选材料的不让玩家挑（有便宜的替代品给它，否则给第一种）。</li>
 * </ul>
 * 只改“拆”这一侧；拆解台下半的重新组合用的是拆出来的材料，配方去掉以后也就组合不了。
 */
@Mixin(ContainerTFUncrafting.class)
public abstract class ContainerTFUncraftingMixin {
    @Shadow(remap = false)
    @Final
    private World world;

    @Shadow(remap = false)
    private static IRecipe[] getRecipesFor(ItemStack inputStack) {
        throw new AssertionError();
    }

    @Shadow(remap = false)
    private ItemStack[] getIngredients(IRecipe recipe) {
        throw new AssertionError();
    }

    @Redirect(method = "onCraftMatrixChanged", at = @At(value = "INVOKE",
            target = "Ltwilightforest/inventory/ContainerTFUncrafting;getRecipesFor(Lnet/minecraft/item/ItemStack;)[Lnet/minecraft/item/crafting/IRecipe;",
            remap = false))
    private IRecipe[] youranmod$uncraftableRecipes(ItemStack input) {
        IRecipe[] all = getRecipesFor(input);
        if (all.length == 0 || !UncraftGuard.enabled()) {
            return all;
        }
        if (UncraftGuard.inputBlocked(input)) {
            return new IRecipe[0];
        }
        List<IRecipe> kept = new ArrayList<>(all.length);
        for (IRecipe recipe : all) {
            ItemStack[] shown;
            try {
                shown = getIngredients(recipe);
            } catch (RuntimeException e) {
                // 拆解台自己摆不出这条配方的材料：不拿来拆
                continue;
            }
            if (UncraftGuard.allowed(input, recipe, shown, world)) {
                kept.add(recipe);
            }
        }
        return kept.size() == all.length ? all : kept.toArray(new IRecipe[0]);
    }

    @Inject(method = "getIngredients", at = @At("RETURN"), remap = false)
    private void youranmod$lockVariants(IRecipe recipe, CallbackInfoReturnable<ItemStack[]> cir) {
        UncraftGuard.lockVariants(recipe, cir.getReturnValue());
    }
}
