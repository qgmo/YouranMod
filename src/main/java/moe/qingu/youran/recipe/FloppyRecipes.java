package moe.qingu.youran.recipe;

import moe.qingu.youran.YouranMod;
import moe.qingu.youran.compat.alchemistry.AlchemistryCompat;
import moe.qingu.youran.registry.ModItems;
import net.minecraft.item.Item;
import net.minecraft.item.ItemStack;
import net.minecraft.item.crafting.IRecipe;
import net.minecraft.util.ResourceLocation;
import net.minecraftforge.fml.common.Loader;
import net.minecraftforge.fml.common.registry.ForgeRegistries;
import net.minecraftforge.fml.common.registry.GameRegistry;
import net.minecraftforge.oredict.ShapelessOreRecipe;
import net.minecraftforge.registries.IForgeRegistry;

/**
 * 软盘加工链的工作台、熔炉配方（服主 11-14）。用到别的模组物品的按装没装决定加不加；炼金化学的化合物按名字取元数据。
 * 在配方注册事件里调用（{@link BuiltinRecipes}）：这时炼金化学的化合物（含整合包脚本用 {@code #loader alchemistry} 在它预初始化时
 * 注册的聚乙烯 {@code polyethene}）都已经在化合物表里。
 */
public final class FloppyRecipes {
    /** IC2 粘性树脂 */
    static final String IC2_RESIN = "ic2:misc_resource";
    static final int IC2_RESIN_META = 4;
    /** 炼金化学自带的聚氯乙烯 */
    static final String PVC_COMPOUND = "polyvinyl_chloride";
    /** 聚乙烯：整合包脚本 Elements and Compounds.zs 用 createCompound(512, "polyethene", ...) 注册的，按名字取 */
    static final String PE_COMPOUND = "polyethene";

    private FloppyRecipes() {
    }

    public static void register(IForgeRegistry<IRecipe> registry) {
        // 熔炉：粗制盘片 → 精制盘片
        GameRegistry.addSmelting(new ItemStack(ModItems.roughPlatter), new ItemStack(ModItems.refinedPlatter), 0.1F);
        if (!Loader.isModLoaded(AlchemistryCompat.MODID)) {
            return;
        }
        // 1 钴改性磁粉 + 1 粘性树脂 + 1 醋酸纤维素 → 1 粗制盘片（无序）
        Item resin = Loader.isModLoaded("ic2") ? ForgeRegistries.ITEMS.getValue(new ResourceLocation(IC2_RESIN)) : null;
        ItemStack acetate = AlchemistryCompat.stack(AlchemistryCompat.CELLULOSE_ACETATE, 1);
        if (resin != null && !acetate.isEmpty()) {
            shapeless(registry, "rough_platter", new ItemStack(ModItems.roughPlatter),
                    new ItemStack(ModItems.cobaltMagneticDust), new ItemStack(resin, 1, IC2_RESIN_META), acetate);
        }
        // 4 化合物 → 1 塑料团
        lump(registry, "pvc_lump", PVC_COMPOUND, new ItemStack(ModItems.pvcLump));
        lump(registry, "polyethylene_lump", PE_COMPOUND, new ItemStack(ModItems.polyethyleneLump));
    }

    private static void lump(IForgeRegistry<IRecipe> registry, String name, String compound, ItemStack output) {
        ItemStack c = AlchemistryCompat.stack(compound, 1);
        if (c.isEmpty()) {
            YouranMod.LOG.warn("炼金化学里没有化合物 {}，不加 {} 的工作台配方", compound, name);
            return;
        }
        shapeless(registry, name, output, c, c.copy(), c.copy(), c.copy());
    }

    private static void shapeless(IForgeRegistry<IRecipe> registry, String name, ItemStack output, Object... inputs) {
        ResourceLocation id = new ResourceLocation(YouranMod.MODID, name);
        registry.register(new ShapelessOreRecipe(id, output, inputs).setRegistryName(id));
    }
}
