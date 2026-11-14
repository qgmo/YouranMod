package moe.qingu.youran.recipe;

import moe.qingu.youran.YouranMod;
import moe.qingu.youran.logic.reaction.FluidItemReaction;
import moe.qingu.youran.logic.reaction.ItemIngredient;
import moe.qingu.youran.logic.reaction.ReactionTable;
import moe.qingu.youran.logic.reaction.StackFacts;
import net.minecraft.item.ItemStack;
import net.minecraftforge.oredict.OreDictionary;

/**
 * IC2 流体/固体装罐机（富集模式）的反应配方表：“流体 + 物品 → 流体 + 物品”。由 {@code mixin.ic2} 接进装罐机，
 * CraftTweaker 脚本经 {@code mods.youranmod.CannerReaction} 增删（{@code compat.crafttweaker}）。
 *
 * <p>两端都有（单人游戏的内置服务端要用；客户端的界面判断输入槽收不收物品也用）。不引用 IC2 的类，没装 IC2 时也能加载。
 */
public final class CannerReactions {
    /** IC2 过热蒸汽 */
    public static final String SUPERHEATED_STEAM = "ic2superheated_steam";
    /** IC2 氢气 */
    public static final String HYDROGEN = "ic2hydrogen";

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
     * （{@link BuiltinRecipes}）：脚本删得掉、改得了这些反应。
     */
    public static synchronized void registerBuiltins() {
        if (builtinsRegistered) {
            return;
        }
        builtinsRegistered = true;
        TABLE.add(smallMagnetite());
        YouranMod.LOG.info("装罐机反应配方：内置 {} 条", TABLE.reactions().size());
    }

    /** 小撮磁粉：3 小撮铁粉（dustTinyIron）+ 5000 mB 过热蒸汽 → 1 小撮磁粉 + 20 mB 氢气 */
    static FluidItemReaction<ItemStack, ReactionItem> smallMagnetite() {
        return new FluidItemReaction<>(SUPERHEATED_STEAM, 5000, ItemIngredient.ore("dustTinyIron", 3),
                HYDROGEN, 20, new ReactionItem(YouranMod.MODID + ":small_magnetite_dust", 0, 1, null));
    }
}
