package moe.qingu.youran.recipe;

import moe.qingu.youran.YouranMod;
import moe.qingu.youran.compat.alchemistry.AlchemistryCompat;
import moe.qingu.youran.logic.boron.Tourmaline.Portion;
import moe.qingu.youran.logic.resist.ResistNumbers;
import net.minecraft.init.Blocks;
import net.minecraft.init.Items;
import net.minecraft.item.ItemStack;

import java.util.Arrays;
import java.util.Collections;

/**
 * 萤石同时含氟化钙（服主 11-16）：中文“萤石”（glowstone）在炼金化学里是磷（掺杂照旧当磷用）。
 * 改炼金化学溶解机对萤石的溶解：萤石粉除原有的 4 个磷外，25% 概率再出 1 个氟化钙；
 * 萤石块按 4 个萤石粉折算：16 磷 + 1 氟化钙（固定）。
 *
 * <p>炼金化学自己的萤石配方在它的后初始化里才建好，所以在全部载入完成时换（{@link #loadComplete}）；整合包脚本（CraftTweaker）
 * 这时已经跑完，脚本里对萤石的溶解配方（现在没有）会被换掉。化合机（4 磷 → 萤石粉、16 磷 → 萤石块）不动。
 * 炼金化学的“绝对概率”按 {@code 概率 >= nextInt(101)} 掷，所以 25 实际是 26/101，约 25.7%。
 */
public final class FluoriteRecipes {
    private FluoriteRecipes() {
    }

    /** 全部载入完成：换萤石粉、萤石块的溶解机配方（没装炼金化学时什么都不做） */
    public static void loadComplete(boolean alchemistry) {
        if (!alchemistry) {
            return;
        }
        int dust = AlchemistryCompat.replaceDissolverRecipe(new ItemStack(Items.GLOWSTONE_DUST), Arrays.asList(
                Collections.singletonList(new Portion("phosphorus", ResistNumbers.FLUORITE_DUST_PHOSPHORUS, 100)),
                Collections.singletonList(new Portion(AlchemistryCompat.CALCIUM_FLUORIDE, 1, ResistNumbers.FLUORITE_DUST_CHANCE))));
        int block = AlchemistryCompat.replaceDissolverRecipe(new ItemStack(Blocks.GLOWSTONE), Arrays.asList(
                Collections.singletonList(new Portion("phosphorus", ResistNumbers.FLUORITE_BLOCK_PHOSPHORUS, 100)),
                Collections.singletonList(new Portion(AlchemistryCompat.CALCIUM_FLUORIDE, ResistNumbers.FLUORITE_BLOCK_CALCIUM_FLUORIDE, 100))));
        YouranMod.LOG.info("炼金化学溶解机：萤石粉换了 {} 条原配方（-1 = 没换成）、萤石块换了 {} 条；萤石粉 {} 磷 + {}% 氟化钙，萤石块 {} 磷 + {} 氟化钙",
                dust, block, ResistNumbers.FLUORITE_DUST_PHOSPHORUS, ResistNumbers.FLUORITE_DUST_CHANCE,
                ResistNumbers.FLUORITE_BLOCK_PHOSPHORUS, ResistNumbers.FLUORITE_BLOCK_CALCIUM_FLUORIDE);
    }
}
