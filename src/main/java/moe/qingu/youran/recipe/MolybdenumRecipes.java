package moe.qingu.youran.recipe;

import moe.qingu.youran.YouranMod;
import moe.qingu.youran.compat.alchemistry.AlchemistryCompat;
import moe.qingu.youran.registry.ModItems;
import net.minecraft.item.ItemStack;
import net.minecraftforge.fml.common.registry.GameRegistry;
import net.minecraftforge.oredict.OreDictionary;

/**
 * 钼（服主 11-16）：铜矿的副产物。IC2 热能离心机分粉碎 / 纯净的粉碎铜矿石时有几率多出 1 小撮钼粉（Mixin，见
 * {@code compat.ic2.CentrifugeHooks}）；沉浸工程粉碎机粉碎铜矿石时有几率出锡粉、小撮钼粉（同 IC2 一样能得到锡和钼，原有的 10% 金粉保留）。
 * 9 小撮钼粉 = 1 钼粉（工作台，JSON 配方），钼粉在熔炉里烧成炼金化学的钼锭（{@code ingotMolybdenum}，= 16 钼元素）。
 * 炼金化学按矿物词典 {@code dustMolybdenum} 自动给钼粉生成溶解机配方（1 → 16 钼）；小撮的没有（它只认 dust / nugget 等几种前缀）。
 */
public final class MolybdenumRecipes {
    /** 钼的原子序数（钼锭 {@code alchemistry:ingot:42}） */
    public static final int MOLYBDENUM = 42;

    private MolybdenumRecipes() {
    }

    /** 后初始化：熔炉 */
    public static void postInit() {
        ItemStack ingot = AlchemistryCompat.ingot(MOLYBDENUM, 1);
        if (!ingot.isEmpty()) {
            GameRegistry.addSmelting(ModItems.molybdenumDust, ingot, 0.3F);
        }
    }

    /** 全部载入完成（沉浸工程的矿石粉碎配方都有了）：给铜矿石的粉碎配方加副产物 */
    public static void loadComplete(boolean ie) {
        if (ie) {
            Ie.register();
        }
    }

    static final class Ie {
        static void register() {
            int n = 0;
            for (ItemStack ore : OreDictionary.getOres("oreCopper", false)) {
                blusunrize.immersiveengineering.api.crafting.CrusherRecipe r = blusunrize.immersiveengineering.api.crafting.CrusherRecipe.findRecipe(ore);
                if (r == null || hasMolybdenum(r)) {
                    continue;
                }
                r.addToSecondaryOutput("dustTin", moe.qingu.youran.logic.litho.LithoNumbers.CRUSHER_COPPER_TIN,
                        new ItemStack(ModItems.smallMolybdenumDust), moe.qingu.youran.logic.litho.LithoNumbers.CRUSHER_COPPER_MO);
                n++;
            }
            YouranMod.LOG.info("沉浸工程粉碎机：{} 条铜矿石配方加了锡粉、小撮钼粉副产物", n);
        }

        static boolean hasMolybdenum(blusunrize.immersiveengineering.api.crafting.CrusherRecipe r) {
            if (r.secondaryOutput == null) {
                return false;
            }
            for (ItemStack s : r.secondaryOutput) {
                if (s != null && s.getItem() == ModItems.smallMolybdenumDust) {
                    return true;
                }
            }
            return false;
        }
    }
}
