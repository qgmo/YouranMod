package moe.qingu.youran.recipe;

import moe.qingu.youran.YouranMod;
import moe.qingu.youran.compat.alchemistry.AlchemistryCompat;
import moe.qingu.youran.logic.reaction.FluidItemReaction;
import moe.qingu.youran.logic.reaction.ItemIngredient;
import moe.qingu.youran.logic.reaction.ReactionTable;
import moe.qingu.youran.logic.reaction.StackFacts;
import net.minecraft.item.ItemStack;
import net.minecraftforge.fluids.FluidRegistry;
import net.minecraftforge.fml.common.Loader;
import net.minecraftforge.oredict.OreDictionary;

import java.util.Arrays;
import java.util.List;

/**
 * IC2 流体/固体装罐机（富集模式）的反应配方表：“流体 + 物品 → 流体 + 物品”。由 {@code mixin.ic2} 接进装罐机，
 * CraftTweaker 脚本经 {@code mods.youranmod.CannerReaction} 增删（{@code compat.crafttweaker}）。
 *
 * <p>两端都有（单人游戏的内置服务端要用；客户端的界面判断输入槽收不收物品也用）。不引用 IC2 的类，没装 IC2 时也能加载。
 */
public final class CannerReactions {
    /** IC2 过热蒸汽 */
    public static final String SUPERHEATED_STEAM = "ic2superheated_steam";
    /** IC2 蒸汽 */
    public static final String STEAM = "ic2steam";
    /** IC2 氢气 */
    public static final String HYDROGEN = "ic2hydrogen";
    /** 本模组的氯气 */
    public static final String CHLORINE = "chlorine_youran";
    /** 本模组的乙酸 */
    public static final String ACETIC_ACID = "acetic_acid";
    /** 盐酸（SCP: Lockdown 注册的 h_cl_fluid，整合包里电解机、冶炼炉、液化器都做它） */
    public static final String HYDROCHLORIC_ACID = "h_cl_fluid";
    /** 本模组的三氯氢硅（纯度见 fluid.FluidPurity；装罐机做出来的不带标记，就是 90%） */
    public static final String TRICHLOROSILANE = "trichlorosilane";
    /** 匠魂的熔融钴 */
    public static final String MOLTEN_COBALT = "cobalt";
    /** 炼金化学的元素物品、铁元素的元数据、化合物物品 */
    static final String ALCHEMISTRY_ELEMENT = "alchemistry:element";
    static final int ALCHEMISTRY_IRON = 26;
    static final String ALCHEMISTRY_COMPOUND = "alchemistry:compound";

    /** 看 {@link ItemStack} 的方式：注册名、元数据、数量、矿物词典 */
    public static final StackFacts<ItemStack> STACKS = new StackFacts<ItemStack>() {
        @Override
        public boolean isEmpty(ItemStack stack) {
            return stack.isEmpty();
        }

        @Override
        public int count(ItemStack stack) {
            return stack.getCount();
        }

        @Override
        public String itemId(ItemStack stack) {
            return stack.getItem().getRegistryName() == null ? "" : stack.getItem().getRegistryName().toString();
        }

        @Override
        public int meta(ItemStack stack) {
            return stack.getMetadata();
        }

        @Override
        public boolean hasOre(ItemStack stack, String oreName) {
            if (!OreDictionary.doesOreNameExist(oreName)) {
                return false;
            }
            int id = OreDictionary.getOreID(oreName);
            for (int i : OreDictionary.getOreIDs(stack)) {
                if (i == id) {
                    return true;
                }
            }
            return false;
        }
    };

    public static final ReactionTable<ItemStack, ReactionItem> TABLE = new ReactionTable<>(STACKS);

    private static boolean builtinsRegistered;

    private CannerReactions() {
    }

    /**
     * 登记内置的反应（服主 11-14 定的比例，3Fe + 4H₂O(g) → Fe₃O₄ + 4H₂）。在配方注册事件里、CraftTweaker 跑脚本之前调用
     * （{@link BuiltinRecipes}）：这时炼金化学的四氧化三铁化合物已经注册（{@link AlchemistryCompat}，装了炼金化学时）；脚本也删得掉、改得了这些反应。
     */
    public static synchronized void registerBuiltins() {
        if (builtinsRegistered) {
            return;
        }
        builtinsRegistered = true;
        TABLE.add(smallMagnetite());
        TABLE.add(pvcFromPolyethylene());
        int meta = AlchemistryCompat.meta(AlchemistryCompat.TRIIRON_TETROXIDE);
        if (meta >= 0) {
            for (FluidItemReaction<ItemStack, ReactionItem> r : alchemistry(meta)) {
                TABLE.add(r);
            }
        }
        int cellulose = AlchemistryCompat.meta(AlchemistryCompat.CELLULOSE);
        int acetate = AlchemistryCompat.meta(AlchemistryCompat.CELLULOSE_ACETATE);
        if (cellulose >= 0 && acetate >= 0) {
            TABLE.add(celluloseAcetate(cellulose, acetate));
        }
        if (Loader.isModLoaded("tconstruct")) {
            TABLE.add(cobaltMagnetic());
        }
        if (FluidRegistry.isFluidRegistered(HYDROCHLORIC_ACID)) {
            TABLE.add(CannerReactions.<ItemStack>trichlorosilane());
        } else {
            YouranMod.LOG.info("没有流体 {}（盐酸，SCP: Lockdown 注册），不加装罐机的三氯氢硅配方", HYDROCHLORIC_ACID);
        }
        YouranMod.LOG.info("装罐机反应配方：内置 {} 条", TABLE.reactions().size());
    }

    /** 小撮磁粉：3 小撮铁粉（dustTinyIron）+ 5000 mB 过热蒸汽 → 1 小撮磁粉 + 20 mB 氢气 */
    static FluidItemReaction<ItemStack, ReactionItem> smallMagnetite() {
        return new FluidItemReaction<>(SUPERHEATED_STEAM, 5000, ItemIngredient.ore("dustTinyIron", 3),
                HYDROGEN, 20, new ReactionItem(YouranMod.MODID + ":small_magnetite_dust", 0, 1, null));
    }

    /** 聚氯乙烯：1 聚乙烯团 + 125 mB 氯气 → 1 聚氯乙烯团，不出流体 */
    static FluidItemReaction<ItemStack, ReactionItem> pvcFromPolyethylene() {
        return new FluidItemReaction<>(CHLORINE, 125, ItemIngredient.item(YouranMod.MODID + ":polyethylene_lump", 0, 1),
                null, 0, new ReactionItem(YouranMod.MODID + ":pvc_lump", 0, 1, null));
    }

    /** 醋酸纤维素（装了炼金化学时）：375 mB 乙酸 + 2 纤维素 → 2 醋酸纤维素，不出流体 */
    static FluidItemReaction<ItemStack, ReactionItem> celluloseAcetate(int celluloseMeta, int acetateMeta) {
        return new FluidItemReaction<>(ACETIC_ACID, 375, ItemIngredient.item(ALCHEMISTRY_COMPOUND, celluloseMeta, 2),
                null, 0, new ReactionItem(ALCHEMISTRY_COMPOUND, acetateMeta, 2, null));
    }

    /** 钴改性磁粉（装了匠魂时）：1 磁粉 + 10 mB 熔融钴 → 1 钴改性磁粉，不出流体 */
    static FluidItemReaction<ItemStack, ReactionItem> cobaltMagnetic() {
        return new FluidItemReaction<>(MOLTEN_COBALT, 10, ItemIngredient.item(YouranMod.MODID + ":magnetite_dust", 0, 1),
                null, 0, new ReactionItem(YouranMod.MODID + ":cobalt_magnetic_dust", 0, 1, null));
    }

    /**
     * 三氯氢硅（芯片线“硅”第 3 步，服主 11-15）：1 硅粉 + 3000 mB 盐酸 → 1000 mB 三氯氢硅，纯度 90%，不出物品。
     * 纯度 90% 的三氯氢硅不带纯度标记（见 {@code fluid.FluidPurity}），所以输出就是普通的流体。
     */
    static <S> FluidItemReaction<S, ReactionItem> trichlorosilane() {
        return new FluidItemReaction<>(HYDROCHLORIC_ACID, 3000, ItemIngredient.item(YouranMod.MODID + ":silicon_dust", 0, 1),
                TRICHLOROSILANE, 1000, null);
    }

    /**
     * 炼金化学的两条（四氧化三铁化合物的元数据是 {@code compoundMeta}）：
     * 6 铁元素 + 5000 mB 过热蒸汽 → 2 四氧化三铁 + 20 mB 氢气；3 铁元素 + 2500 mB 蒸汽 → 1 四氧化三铁 + 10 mB 氢气。
     */
    static List<FluidItemReaction<ItemStack, ReactionItem>> alchemistry(int compoundMeta) {
        return Arrays.asList(
                new FluidItemReaction<>(SUPERHEATED_STEAM, 5000, ItemIngredient.item(ALCHEMISTRY_ELEMENT, ALCHEMISTRY_IRON, 6),
                        HYDROGEN, 20, new ReactionItem(ALCHEMISTRY_COMPOUND, compoundMeta, 2, null)),
                new FluidItemReaction<>(STEAM, 2500, ItemIngredient.item(ALCHEMISTRY_ELEMENT, ALCHEMISTRY_IRON, 3),
                        HYDROGEN, 10, new ReactionItem(ALCHEMISTRY_COMPOUND, compoundMeta, 1, null)));
    }
}
