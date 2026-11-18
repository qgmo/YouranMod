package moe.qingu.youran.compat.jei;

import io.github.jodlodi.twilighttweaks.data.recipes.ModRecipeTypes;
import io.github.jodlodi.twilighttweaks.jei.UncraftingCategory;
import io.github.jodlodi.twilighttweaks.jei.UncraftingWrapper;
import mezz.jei.api.IRecipeRegistry;
import mezz.jei.api.ingredients.IIngredients;
import mezz.jei.api.ingredients.VanillaTypes;
import mezz.jei.api.recipe.IRecipeWrapper;
import moe.qingu.youran.YouranMod;
import moe.qingu.youran.compat.twilightforest.UncraftGuard;
import moe.qingu.youran.logic.uncraft.UncraftRules;
import net.minecraft.item.ItemStack;
import net.minecraft.item.crafting.CraftingManager;
import net.minecraft.item.crafting.IRecipe;

import java.util.ArrayList;
import java.util.List;

/**
 * JEI 的“拆解”页（服主 11-18：拆解台拆不了的配方不要显示）。这一页是 Twilight Tweaks 1.0.2 加的：它在 JEI 注册时把全部工作台配方
 * （去掉它自己替换掉的与它名单里的）加上它自己的拆解配方，每条反过来列成“配方产物 → 配方材料”（每格列出全部候选，带容器的不列），
 * 不经过拆解台找配方的那一步。这里在 JEI 启动后逐条照拆解台的判定（{@link UncraftGuard#jeiView}）处理：
 * <ul>
 *     <li>拆不了的藏起来（{@code hideRecipe}）；</li>
 *     <li>有几种候选的格子（以及通配数据值，JEI 会把它展开成所有种类）换成拆解台实际给的那一种：藏起原来的，加一条只显示那一种的
 *     （分类按 {@code UncraftingWrapper} 摆格子，所以是它的子类）。这些配方在页上排到后面。</li>
 * </ul>
 * 只按代码里写死的规则判（{@link UncraftGuard#builtinRules}）：客户端不知道服务端配置（{@code config/youranmod.cfg} 的 {@code uncrafting} 节）
 * 另加的名单，也不知道服务端是不是关了限制；那些照旧按代码里的规则显示，拆的时候以服务端为准。不为此做网络同步。
 *
 * <p>只有装了 Twilight Tweaks 时才会被加载（它依赖暮色森林；JEI 插件按 {@code Loader.isModLoaded} 判断后才调用）。
 */
@SuppressWarnings("deprecation") // IRecipeRegistry.addRecipe：运行时加配方，JEI 4.16 标了不推荐但照常能用（同 Ic2CentrifugeSteps）
final class TwilightUncrafting {
    static final String MODID = "twilighttweaks";

    private TwilightUncrafting() {
    }

    static void apply(IRecipeRegistry registry) {
        UncraftRules.Rules rules = UncraftGuard.builtinRules();
        List<IRecipe> recipes = new ArrayList<>();
        for (IRecipe r : CraftingManager.REGISTRY) {
            recipes.add(r);
        }
        recipes.addAll(ModRecipeTypes.uncraftingRecipes);
        int hidden = 0;
        int fixed = 0;
        for (IRecipe recipe : recipes) {
            IRecipeWrapper wrapper;
            UncraftGuard.JeiView view;
            try {
                wrapper = registry.getRecipeWrapper(recipe, UncraftingCategory.id);
                if (wrapper == null) {
                    // 不在拆解页上（Twilight Tweaks 去掉的、放不进 3×3 的）
                    continue;
                }
                view = UncraftGuard.jeiView(rules, recipe);
            } catch (RuntimeException | LinkageError e) {
                // 配方自己出错：照旧显示，拆的时候服务端会判
                continue;
            }
            if (view.hidden) {
                registry.hideRecipe(wrapper, UncraftingCategory.id);
                hidden++;
            } else if (view.differs) {
                registry.hideRecipe(wrapper, UncraftingCategory.id);
                registry.addRecipe(new Fixed(recipe, view.slots), UncraftingCategory.id);
                fixed++;
            }
        }
        YouranMod.LOG.info("JEI 拆解页：藏起了 {} 条拆解台拆不了的配方，{} 条换成拆解台实际给的候选", hidden, fixed);
    }

    /** 候选固定以后的显示：放进去的照旧是配方产物，拆出来的每格只有拆解台给的那一种（或者不显示） */
    static final class Fixed extends UncraftingWrapper {
        private final ItemStack input;
        private final List<List<ItemStack>> slots;

        Fixed(IRecipe recipe, List<List<ItemStack>> slots) {
            super(recipe);
            this.input = recipe.getRecipeOutput();
            this.slots = slots;
        }

        @Override
        public void getIngredients(IIngredients ingredients) {
            ingredients.setInput(VanillaTypes.ITEM, input);
            ingredients.setOutputLists(VanillaTypes.ITEM, slots);
        }
    }
}
