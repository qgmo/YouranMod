package moe.qingu.youran.recipe;

import moe.qingu.youran.YouranMod;
import moe.qingu.youran.compat.alchemistry.AlchemistryCompat;
import moe.qingu.youran.logic.boron.Tourmaline;
import net.minecraft.item.Item;
import net.minecraft.item.ItemStack;
import net.minecraft.util.ResourceLocation;
import net.minecraftforge.fml.common.registry.ForgeRegistries;

/**
 * 硼的来源（服主 11-15）：夸克的洞穴水晶当作电气石。这里放不分机器的部分：洞穴水晶的物品、炼金化学溶解机的配方。
 * 热能离心机、电弧炉、装罐机的配方在各模组的 compat 类里。夸克、炼金化学都是软依赖，没装时什么都不加。
 */
public final class BoronRecipes {
    /** 夸克的洞穴水晶（方块物品，元数据是颜色，见 {@link Tourmaline}） */
    public static final ResourceLocation QUARK_CRYSTAL = new ResourceLocation("quark", "crystal");

    private BoronRecipes() {
    }

    /** 这种颜色的洞穴水晶；没装夸克（或夸克的水晶洞穴关掉了）时为空物品 */
    public static ItemStack crystal(Tourmaline color, int count) {
        Item item = ForgeRegistries.ITEMS.getValue(QUARK_CRYSTAL);
        return item == null ? ItemStack.EMPTY : new ItemStack(item, count, color.meta);
    }

    /** 炼金化学溶解机：每个洞穴水晶拆成结构单元与特征元素（{@link Tourmaline#dissolverOutputs()}）。后初始化时调用 */
    public static void registerCrystalDissolver() {
        if (crystal(Tourmaline.WHITE, 1).isEmpty()) {
            YouranMod.LOG.info("没有夸克的洞穴水晶，不加溶解机的电气石配方");
        } else {
            int added = 0;
            for (Tourmaline t : Tourmaline.values()) {
                if (AlchemistryCompat.addDissolverRecipe(crystal(t, 1), t.dissolverOutputs())) {
                    added++;
                }
            }
            YouranMod.LOG.info("已加炼金化学溶解机配方：{} 种洞穴水晶（电气石）", added);
        }
    }

    /**
     * 炼金化学溶解机：1 硼硅酸盐渣 → 偏硅酸根 6、硼酸根 3、氢氧根 4、钠 1（{@link Tourmaline#structuralUnits()}，
     * 热能离心机已经把金属分出去了，所以没有铝和特征元素）。后初始化时调用。
     */
    public static void registerSlagDissolver(ItemStack slag) {
        if (AlchemistryCompat.addDissolverRecipe(slag, java.util.Collections.singletonList(Tourmaline.structuralUnits()))) {
            YouranMod.LOG.info("已加炼金化学溶解机配方：硼硅酸盐渣");
        }
    }
}
