package moe.qingu.youran.compat.ic2;

import ic2.api.item.IC2Items;
import ic2.api.recipe.Recipes;
import moe.qingu.youran.YouranMod;
import moe.qingu.youran.compat.alchemistry.AlchemistryCompat;
import moe.qingu.youran.logic.boron.Tourmaline;
import moe.qingu.youran.registry.ModItems;
import net.minecraft.item.ItemStack;
import net.minecraft.nbt.NBTTagCompound;

/** 加进 IC2 机器的配方。只有装了 IC2 时才会被加载（{@code recipe.CompatRecipes} 先查 IC2 在不在） */
public final class Ic2Recipes {
    /** 热能离心机要的最低热量：同 IC2 自己的铁相关配方（粉碎铁矿石、纯净的粉碎铁矿石，见 IC2 的 thermal_centrifuge.ini） */
    static final int CENTRIFUGE_MIN_HEAT = 1500;

    private Ic2Recipes() {
    }

    public static void register() {
        registerCentrifuge();
        registerRolling();
        registerFloppy();
        registerSiliconMacerator();
        registerTourmalineCentrifuge();
    }

    /** 电气石进热能离心机要的最低热量（Claude 11-15 定）：比铁矿石（1500）高一点 */
    static final int TOURMALINE_MIN_HEAT = 2000;
    /** 沉浸工程的铝粒（{@code immersiveengineering:metal:21}） */
    static final String IE_METAL = "immersiveengineering:metal";
    static final int IE_NUGGET_ALUMINUM = 21;

    /**
     * 热能离心机：洞穴水晶（电气石）按颜色分出金属，加 1 硼硅酸盐渣（服主 11-15，数量 Claude 定）。离心机只有 3 个输出槽：
     * <ul>
     *     <li>黑：1 小撮铁粉 + 3 铝粒 + 1 渣；</li>
     *     <li>白、绿、青、蓝（锂电气石）：1 小撮锂粉 + 4 铝粒 + 1 渣（铝比黑电气石多 1 个，6 → 7，往上取到 4 粒）；</li>
     *     <li>黄、橙（镁电气石）：3 炼金化学镁元素 + 3 铝粒 + 1 渣；</li>
     *     <li>红、紫（红碧玺）：1 小撮锂粉 + 1 炼金化学锰元素 + 1 渣（槽不够，不出铝）。</li>
     * </ul>
     * 铝粒要沉浸工程、镁和锰要炼金化学；没装时那一样不出，渣照出。没装夸克时不加。
     */
    static void registerTourmalineCentrifuge() {
        if (moe.qingu.youran.recipe.BoronRecipes.crystal(Tourmaline.WHITE, 1).isEmpty()) {
            YouranMod.LOG.info("没有夸克的洞穴水晶，不加热能离心机的电气石配方");
            return;
        }
        NBTTagCompound meta = new NBTTagCompound();
        meta.setInteger("minHeat", TOURMALINE_MIN_HEAT);
        int added = 0;
        for (Tourmaline t : Tourmaline.values()) {
            java.util.List<ItemStack> outputs = new java.util.ArrayList<>();
            switch (t.kind) {
                case SCHORL:
                    addIfPresent(outputs, IC2Items.getItem("dust", "small_iron"), 1);
                    addIfPresent(outputs, aluminumNugget(), 3);
                    break;
                case ELBAITE:
                    addIfPresent(outputs, IC2Items.getItem("dust", "small_lithium"), 1);
                    addIfPresent(outputs, aluminumNugget(), 4);
                    break;
                case DRAVITE:
                    addIfPresent(outputs, AlchemistryCompat.chemical("magnesium", 3), 3);
                    addIfPresent(outputs, aluminumNugget(), 3);
                    break;
                case RUBELLITE:
                    addIfPresent(outputs, IC2Items.getItem("dust", "small_lithium"), 1);
                    addIfPresent(outputs, AlchemistryCompat.chemical("manganese", 1), 1);
                    break;
                default:
                    break;
            }
            outputs.add(new ItemStack(ModItems.borosilicateSlag));
            ItemStack crystal = moe.qingu.youran.recipe.BoronRecipes.crystal(t, 1);
            if (Recipes.centrifuge.addRecipe(Recipes.inputFactory.forStack(crystal), meta, false, outputs.toArray(new ItemStack[0]))) {
                added++;
            } else {
                YouranMod.LOG.error("IC2 热能离心机配方（{} 洞穴水晶）没加上：已有输入相同的配方", t.name().toLowerCase(java.util.Locale.ROOT));
            }
        }
        YouranMod.LOG.info("已加 IC2 热能离心机配方：{} 种洞穴水晶（电气石），最低热量 {}", added, TOURMALINE_MIN_HEAT);
    }

    private static void addIfPresent(java.util.List<ItemStack> outputs, @javax.annotation.Nullable ItemStack stack, int count) {
        if (stack != null && !stack.isEmpty()) {
            ItemStack copy = stack.copy();
            copy.setCount(count);
            outputs.add(copy);
        }
    }

    private static ItemStack aluminumNugget() {
        net.minecraft.item.Item item = net.minecraftforge.fml.common.registry.ForgeRegistries.ITEMS.getValue(new net.minecraft.util.ResourceLocation(IE_METAL));
        return item == null ? ItemStack.EMPTY : new ItemStack(item, 1, IE_NUGGET_ALUMINUM);
    }

    /** 打粉机：1 炼金化学硅锭 → 1 硅粉（芯片线“硅”第 2 步）。要装了炼金化学 */
    static void registerSiliconMacerator() {
        ItemStack ingot = AlchemistryCompat.ingot(AlchemistryCompat.SILICON, 1);
        if (ingot.isEmpty()) {
            YouranMod.LOG.info("没有炼金化学的硅锭，不加 IC2 打粉机的硅粉配方");
            return;
        }
        if (!Recipes.macerator.addRecipe(Recipes.inputFactory.forStack(ingot), null, false, new ItemStack(ModItems.siliconDust))) {
            YouranMod.LOG.error("IC2 打粉机配方（硅锭 → 硅粉）没加上：已有输入相同的配方");
        } else {
            YouranMod.LOG.info("已加 IC2 打粉机配方：硅锭 → 硅粉");
        }
    }

    /** 热能离心机：1 铁粉（dustIron）→ 3 小撮磁粉 */
    static void registerCentrifuge() {
        NBTTagCompound meta = new NBTTagCompound();
        meta.setInteger("minHeat", CENTRIFUGE_MIN_HEAT);
        boolean ok = Recipes.centrifuge.addRecipe(Recipes.inputFactory.forOreDict("dustIron"), meta, false,
                new ItemStack(ModItems.smallMagnetiteDust, 3));
        if (!ok) {
            YouranMod.LOG.error("IC2 热能离心机配方（铁粉 → 3 小撮磁粉）没加上：已有输入相同的配方");
        }
    }

    /** 金属成型机辊压（同锭压成板）：精制盘片 → 抛光盘片，聚氯乙烯团 → 聚氯乙烯片 */
    static void registerRolling() {
        rolling(new ItemStack(ModItems.refinedPlatter), new ItemStack(ModItems.polishedPlatter));
        rolling(new ItemStack(ModItems.pvcLump), new ItemStack(ModItems.pvcSheet));
    }

    private static void rolling(ItemStack input, ItemStack output) {
        if (!Recipes.metalformerRolling.addRecipe(Recipes.inputFactory.forStack(input), null, false, output)) {
            YouranMod.LOG.error("IC2 金属成型机辊压配方（{} → {}）没加上：已有输入相同的配方", input.getItem().getRegistryName(), output.getItem().getRegistryName());
        }
    }

    /**
     * 流体/固体装罐机的固体装罐模式（容器放“罐子”槽，填充物放输入槽）：1 抛光盘片 + 6 聚氯乙烯片 → 1 软盘。
     * 套子只用聚氯乙烯（服主 11-15：聚乙烯太软、标签和衬布粘不牢，不做套子；聚乙烯可以先加氯气变成聚氯乙烯）。
     */
    static void registerFloppy() {
        if (!Recipes.cannerBottle.addRecipe(Recipes.inputFactory.forStack(new ItemStack(ModItems.polishedPlatter), 1),
                Recipes.inputFactory.forStack(new ItemStack(ModItems.pvcSheet), 6), new ItemStack(ModItems.floppyDisk), false)) {
            YouranMod.LOG.error("IC2 固体装罐配方（抛光盘片 + 6 聚氯乙烯片 → 软盘）没加上：已有输入相同的配方");
        }
    }
}
