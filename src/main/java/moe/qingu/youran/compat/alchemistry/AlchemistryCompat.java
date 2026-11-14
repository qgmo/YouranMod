package moe.qingu.youran.compat.alchemistry;

import moe.qingu.youran.YouranMod;
import net.minecraft.item.Item;
import net.minecraft.item.ItemStack;
import net.minecraft.util.ResourceLocation;
import net.minecraftforge.fml.common.Loader;
import net.minecraftforge.fml.common.registry.ForgeRegistries;

import java.awt.Color;
import java.lang.reflect.Constructor;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;

/**
 * 炼金化学（Alchemistry 1.12.2-42）的软依赖：注册四氧化三铁化合物 {@code triiron_tetroxide}（Fe₃O₄）与化合机配方
 * “16 四氧化三铁 → 1 磁粉”。不改炼金化学，用它留给外部的接口（{@code CompoundRegistry#addExternal}，同 CraftTweaker 的
 * {@code mods.alchemistry.Util.createCompound}）；按类名反射调用，不编译依赖它，没装时什么都不做。
 *
 * <p>化合物的名字在本模组 jar 里的 {@code assets/alchemistry/lang/*.lang}（键 {@code item.alchemistry:compound_triiron_tetroxide.name}），
 * 客户端加载语言文件时与炼金化学自己的合并。
 */
public final class AlchemistryCompat {
    public static final String MODID = "alchemistry";
    public static final String COMPOUND = "triiron_tetroxide";
    /**
     * 化合物的元数据：炼金化学内置的从 0 起连续编号（61 个，0–60），整合包脚本 {@code Elements and Compounds.zs} 用了 500–512，
     * 取 600 避开两边。已经有同名化合物时用它原来的元数据。
     */
    public static final int COMPOUND_META = 600;
    /** 颜色：偏黑 */
    static final Color COLOR = new Color(40, 40, 46);
    static final String ITEM_COMPOUND = "alchemistry:compound";

    private static int compoundMeta = -1;

    private AlchemistryCompat() {
    }

    /** 四氧化三铁化合物的元数据；没装炼金化学或没注册成时为 -1 */
    public static int compoundMeta() {
        return compoundMeta;
    }

    /**
     * 注册化合物。要在炼金化学的预初始化（元素、化合物表）之后、它注册物品模型之前（物品注册事件之后的模型事件）调用：
     * 在方块注册事件里调（{@code recipe.BuiltinRecipes}）。它在后初始化时按化合物表自动生成的化合机、溶解机配方也会带上这个化合物。
     */
    public static synchronized void registerCompound() {
        if (compoundMeta >= 0 || !Loader.isModLoaded(MODID)) {
            return;
        }
        try {
            Class<?> registryClass = Class.forName("al132.alchemistry.chemistry.CompoundRegistry");
            Object registry = registryClass.getField("INSTANCE").get(null);
            int existing = (Integer) registryClass.getMethod("getMeta", String.class).invoke(registry, COMPOUND);
            if (existing >= 0) {
                compoundMeta = existing;
                YouranMod.LOG.info("炼金化学里已有化合物 {}（元数据 {}），直接用", COMPOUND, existing);
                return;
            }
            if (registryClass.getMethod("get", int.class).invoke(registry, COMPOUND_META) != null) {
                YouranMod.LOG.error("炼金化学的化合物元数据 {} 已被占用，不注册 {}", COMPOUND_META, COMPOUND);
                return;
            }
            Constructor<?> pair = Class.forName("al132.alchemistry.chemistry.CompoundPair").getConstructor(String.class, int.class);
            List<Object> components = Arrays.asList(pair.newInstance("iron", 3), pair.newInstance("oxygen", 4));
            registryClass.getMethod("addExternal", int.class, String.class, Color.class, List.class)
                    .invoke(registry, COMPOUND_META, COMPOUND, COLOR, components);
            compoundMeta = (Integer) registryClass.getMethod("getMeta", String.class).invoke(registry, COMPOUND);
            YouranMod.LOG.info("已在炼金化学里注册化合物 {}（元数据 {}）", COMPOUND, compoundMeta);
        } catch (ReflectiveOperationException | RuntimeException | LinkageError e) {
            YouranMod.LOG.error("在炼金化学里注册化合物 {} 失败", COMPOUND, e);
        }
    }

    /** 化合机配方：16 四氧化三铁（第一格）→ 1 磁粉。后初始化时调用（炼金化学在载入完成时才应用 CraftTweaker 的删改，脚本删得掉） */
    public static void registerCombinerRecipe(ItemStack output) {
        if (compoundMeta < 0) {
            return;
        }
        Item compound = ForgeRegistries.ITEMS.getValue(new ResourceLocation(ITEM_COMPOUND));
        if (compound == null || output.isEmpty()) {
            YouranMod.LOG.error("找不到炼金化学的化合物物品，不加化合机配方");
            return;
        }
        try {
            Class<?> recipeClass = Class.forName("al132.alchemistry.recipes.CombinerRecipe");
            Object recipe = recipeClass.getConstructor(ItemStack.class, List.class, String.class)
                    .newInstance(output, Collections.singletonList(new ItemStack(compound, 16, compoundMeta)), "");
            Class<?> modRecipes = Class.forName("al132.alchemistry.recipes.ModRecipes");
            @SuppressWarnings("unchecked")
            List<Object> combiner = (List<Object>) modRecipes.getMethod("getCombinerRecipes").invoke(modRecipes.getField("INSTANCE").get(null));
            combiner.add(recipe);
            YouranMod.LOG.info("已加炼金化学化合机配方：16 {} → {}", COMPOUND, output.getItem().getRegistryName());
        } catch (ReflectiveOperationException | RuntimeException | LinkageError e) {
            YouranMod.LOG.error("加炼金化学的化合机配方失败", e);
        }
    }
}
