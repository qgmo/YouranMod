package moe.qingu.youran.compat.alchemistry;

import moe.qingu.youran.YouranMod;
import net.minecraft.item.Item;
import net.minecraft.item.ItemStack;
import net.minecraft.util.ResourceLocation;
import net.minecraftforge.fml.common.Loader;
import net.minecraftforge.fml.common.registry.ForgeRegistries;

import java.awt.Color;
import java.lang.reflect.Constructor;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * 炼金化学（Alchemistry 1.12.2-42）的软依赖：注册本模组要用的化合物与配方。不改炼金化学，用它留给外部的接口
 * （{@code CompoundRegistry#addExternal}，同 CraftTweaker 的 {@code mods.alchemistry.Util.createCompound}）；
 * 按类名反射调用，不编译依赖它，没装时什么都不做。
 *
 * <p>化合物（元数据：炼金化学内置的从 0 起连续编号，61 个，0–60；整合包脚本 {@code Elements and Compounds.zs} 用了 500–512；
 * 本模组从 600 起，避开两边。已经有同名化合物时用它原来的元数据）：
 * <ul>
 *     <li>600 四氧化三铁 {@code triiron_tetroxide}：铁 3、氧 4；</li>
 *     <li>601 乙酸 {@code acetic_acid}：碳 2、氢 4、氧 2；</li>
 *     <li>602 醋酸纤维素 {@code cellulose_acetate}：碳 12、氢 16、氧 8（三醋酸纤维素的重复单元）。</li>
 * </ul>
 * 名字在本模组 jar 里的 {@code assets/alchemistry/lang/*.lang}（键 {@code item.alchemistry:compound_<名字>.name}），
 * 客户端加载语言文件时与炼金化学自己的合并。
 */
public final class AlchemistryCompat {
    public static final String MODID = "alchemistry";
    public static final String TRIIRON_TETROXIDE = "triiron_tetroxide";
    public static final String ACETIC_ACID = "acetic_acid";
    public static final String CELLULOSE_ACETATE = "cellulose_acetate";
    /** 炼金化学自带的纤维素 */
    public static final String CELLULOSE = "cellulose";
    static final String ITEM_COMPOUND = "alchemistry:compound";

    /** 要注册的化合物：名字、元数据、颜色、组成（元素名, 个数） */
    static final List<CompoundSpec> COMPOUNDS = Arrays.asList(
            new CompoundSpec(TRIIRON_TETROXIDE, 600, new Color(40, 40, 46), "iron", 3, "oxygen", 4),
            new CompoundSpec(ACETIC_ACID, 601, new Color(236, 234, 214), "carbon", 2, "hydrogen", 4, "oxygen", 2),
            new CompoundSpec(CELLULOSE_ACETATE, 602, new Color(226, 222, 204), "carbon", 12, "hydrogen", 16, "oxygen", 8));

    /** 化合物名 → 元数据（注册成的，或炼金化学里已有的） */
    private static final Map<String, Integer> METAS = new HashMap<>();
    private static boolean registered;

    private AlchemistryCompat() {
    }

    static final class CompoundSpec {
        final String name;
        final int meta;
        final Color color;
        final Object[] components;

        CompoundSpec(String name, int meta, Color color, Object... components) {
            this.name = name;
            this.meta = meta;
            this.color = color;
            this.components = components;
        }
    }

    /** 化合物 {@code name} 的元数据：本模组注册的，或炼金化学里已有的（如纤维素）；没装炼金化学或没有时为 -1 */
    public static synchronized int meta(String name) {
        Integer meta = METAS.get(name);
        if (meta != null) {
            return meta;
        }
        if (!Loader.isModLoaded(MODID)) {
            return -1;
        }
        try {
            Class<?> registryClass = Class.forName("al132.alchemistry.chemistry.CompoundRegistry");
            return (Integer) registryClass.getMethod("getMeta", String.class).invoke(registryClass.getField("INSTANCE").get(null), name);
        } catch (ReflectiveOperationException | RuntimeException | LinkageError e) {
            YouranMod.LOG.error("查不到炼金化学的化合物 {}", name, e);
            return -1;
        }
    }

    /** 化合物物品；没有时为空物品 */
    public static ItemStack stack(String name, int count) {
        int meta = meta(name);
        Item compound = ForgeRegistries.ITEMS.getValue(new ResourceLocation(ITEM_COMPOUND));
        return meta < 0 || compound == null ? ItemStack.EMPTY : new ItemStack(compound, count, meta);
    }

    /**
     * 注册化合物。要在炼金化学的预初始化（元素、化合物表）之后、它注册物品模型之前（物品注册事件之后的模型事件）调用：
     * 在方块注册事件里调（{@code recipe.BuiltinRecipes}）。它在后初始化时按化合物表自动生成的化合机、溶解机配方也会带上这些化合物。
     */
    public static synchronized void registerCompounds() {
        if (registered || !Loader.isModLoaded(MODID)) {
            return;
        }
        registered = true;
        try {
            Class<?> registryClass = Class.forName("al132.alchemistry.chemistry.CompoundRegistry");
            Object registry = registryClass.getField("INSTANCE").get(null);
            Constructor<?> pair = Class.forName("al132.alchemistry.chemistry.CompoundPair").getConstructor(String.class, int.class);
            for (CompoundSpec spec : COMPOUNDS) {
                int existing = (Integer) registryClass.getMethod("getMeta", String.class).invoke(registry, spec.name);
                if (existing >= 0) {
                    METAS.put(spec.name, existing);
                    YouranMod.LOG.info("炼金化学里已有化合物 {}（元数据 {}），直接用", spec.name, existing);
                    continue;
                }
                if (registryClass.getMethod("get", int.class).invoke(registry, spec.meta) != null) {
                    YouranMod.LOG.error("炼金化学的化合物元数据 {} 已被占用，不注册 {}", spec.meta, spec.name);
                    continue;
                }
                List<Object> components = new ArrayList<>();
                for (int i = 0; i < spec.components.length; i += 2) {
                    components.add(pair.newInstance(spec.components[i], spec.components[i + 1]));
                }
                registryClass.getMethod("addExternal", int.class, String.class, Color.class, List.class)
                        .invoke(registry, spec.meta, spec.name, spec.color, components);
                int meta = (Integer) registryClass.getMethod("getMeta", String.class).invoke(registry, spec.name);
                if (meta >= 0) {
                    METAS.put(spec.name, meta);
                    YouranMod.LOG.info("已在炼金化学里注册化合物 {}（元数据 {}）", spec.name, meta);
                }
            }
        } catch (ReflectiveOperationException | RuntimeException | LinkageError e) {
            YouranMod.LOG.error("在炼金化学里注册化合物失败", e);
        }
    }

    /** 化合机配方：16 四氧化三铁（第一格）→ 1 磁粉。后初始化时调用（炼金化学在载入完成时才应用 CraftTweaker 的删改，脚本删得掉） */
    public static void registerCombinerRecipe(ItemStack output) {
        ItemStack input = stack(TRIIRON_TETROXIDE, 16);
        if (input.isEmpty() || output.isEmpty()) {
            
            return;
        }
        try {
            Class<?> recipeClass = Class.forName("al132.alchemistry.recipes.CombinerRecipe");
            Object recipe = recipeClass.getConstructor(ItemStack.class, List.class, String.class)
                    .newInstance(output, Collections.singletonList(input), "");
            Class<?> modRecipes = Class.forName("al132.alchemistry.recipes.ModRecipes");
            @SuppressWarnings("unchecked")
            List<Object> combiner = (List<Object>) modRecipes.getMethod("getCombinerRecipes").invoke(modRecipes.getField("INSTANCE").get(null));
            combiner.add(recipe);
            YouranMod.LOG.info("已加炼金化学化合机配方：16 {} → {}", TRIIRON_TETROXIDE, output.getItem().getRegistryName());
        } catch (ReflectiveOperationException | RuntimeException | LinkageError e) {
            YouranMod.LOG.error("加炼金化学的化合机配方失败", e);
        }
    }

    /**
     * 乙酸化合物与乙酸流体互换：液化器 8 乙酸化合物 → 500 mB 乙酸，蒸发器 500 mB 乙酸 → 8 乙酸化合物。
     * 两台机器都按数量处理（液化器每次扣输入的数量，蒸发器每次出整组产物），比例照原样。后初始化时调用。
     */
    public static void registerAceticAcidConversion(net.minecraftforge.fluids.Fluid aceticAcid) {
        ItemStack compound = stack(ACETIC_ACID, 8);
        if (compound.isEmpty()) {
            return;
        }
        try {
            Class<?> modRecipesClass = Class.forName("al132.alchemistry.recipes.ModRecipes");
            Object modRecipes = modRecipesClass.getField("INSTANCE").get(null);
            Object liquifier = Class.forName("al132.alchemistry.recipes.LiquifierRecipe")
                    .getConstructor(ItemStack.class, net.minecraftforge.fluids.FluidStack.class)
                    .newInstance(compound.copy(), new net.minecraftforge.fluids.FluidStack(aceticAcid, 500));
            Object evaporator = Class.forName("al132.alchemistry.recipes.EvaporatorRecipe")
                    .getConstructor(net.minecraftforge.fluids.FluidStack.class, ItemStack.class)
                    .newInstance(new net.minecraftforge.fluids.FluidStack(aceticAcid, 500), compound.copy());
            listOf(modRecipesClass, modRecipes, "getLiquifierRecipes").add(liquifier);
            listOf(modRecipesClass, modRecipes, "getEvaporatorRecipes").add(evaporator);
            YouranMod.LOG.info("已加炼金化学液化器、蒸发器配方：8 {} ⇄ 500 mB 乙酸", ACETIC_ACID);
        } catch (ReflectiveOperationException | RuntimeException | LinkageError e) {
            YouranMod.LOG.error("加炼金化学的乙酸液化器、蒸发器配方失败", e);
        }
    }

    @SuppressWarnings("unchecked")
    private static List<Object> listOf(Class<?> modRecipesClass, Object modRecipes, String getter) throws ReflectiveOperationException {
        return (List<Object>) modRecipesClass.getMethod(getter).invoke(modRecipes);
    }
}
