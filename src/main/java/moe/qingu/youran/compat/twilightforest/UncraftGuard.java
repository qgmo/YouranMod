package moe.qingu.youran.compat.twilightforest;

import moe.qingu.youran.config.UncraftConfig;
import moe.qingu.youran.logic.uncraft.ItemPatterns;
import moe.qingu.youran.logic.uncraft.UncraftRules;
import net.minecraft.entity.player.EntityPlayer;
import net.minecraft.inventory.Container;
import net.minecraft.inventory.InventoryCraftResult;
import net.minecraft.inventory.InventoryCrafting;
import net.minecraft.inventory.SlotCrafting;
import net.minecraft.item.ItemStack;
import net.minecraft.item.crafting.IRecipe;
import net.minecraft.item.crafting.Ingredient;
import net.minecraft.nbt.NBTTagCompound;
import net.minecraft.nbt.NBTUtil;
import net.minecraft.util.NonNullList;
import net.minecraft.util.ResourceLocation;
import net.minecraft.world.World;
import net.minecraftforge.common.crafting.IShapedRecipe;
import net.minecraftforge.common.util.Constants;
import net.minecraftforge.oredict.OreDictionary;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import javax.annotation.Nullable;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * 暮色森林拆解台的把关（服主 11-17）：由 {@code mixin.twilightforest.ContainerTFUncraftingMixin} 在拆解台找配方、摆材料时调用。
 * 规则见 {@link UncraftRules}；这里只负责从物品、配方里取数据。
 *
 * <p>不让拆的配方直接从候选里去掉：一条都不剩时拆解台和放进一件没有配方的东西一样，什么都不显示。
 * 两端都算（拆解台的格子由服务端同步给客户端，以服务端为准）；配置的名单只在服务端读，客户端只用写死的部分。
 *
 * <p>这个类只引用原版与 Forge 的类，没装暮色森林时不会被加载（只有 Mixin 引用它）。
 */
public final class UncraftGuard {
    /** 同 {@code YouranMod.LOG}（不碰 YouranMod 类，单测里不用初始化它） */
    private static final Logger LOG = LogManager.getLogger("YouranMod");
    private static UncraftRules.Rules rules;
    private static String[][] rulesFrom;
    private static final Set<String> reported = Collections.synchronizedSet(new HashSet<>());
    private static final ItemPatterns CHEAPER = ItemPatterns.parse(UncraftBuiltins.CHEAPER_SUBSTITUTES);

    private UncraftGuard() {
    }

    /** 当前的规则：写死的名单加配置的名单（配置的数组换了才重新解析） */
    public static synchronized UncraftRules.Rules rules() {
        String[][] from = {UncraftConfig.blockedItems, UncraftConfig.blockedResults, UncraftConfig.blockedRecipes,
                {String.valueOf(UncraftConfig.enabled)}};
        if (rules == null || !sameArrays(from, rulesFrom)) {
            rules = build(UncraftConfig.enabled, UncraftConfig.blockedItems, UncraftConfig.blockedResults, UncraftConfig.blockedRecipes);
            rulesFrom = from;
        }
        return rules;
    }

    private static boolean sameArrays(String[][] a, String[][] b) {
        if (b == null || a.length != b.length) {
            return false;
        }
        for (int i = 0; i < a.length; i++) {
            if (!Arrays.equals(a[i], b[i])) {
                return false;
            }
        }
        return true;
    }

    /** 写死的名单加上额外的；名单里格式不对的行记一条日志 */
    public static UncraftRules.Rules build(boolean enabled, String[] extraInputs, String[] extraResults, String[] extraRecipes) {
        ItemPatterns own = ItemPatterns.parse(UncraftBuiltins.OWN_ITEMS);
        ItemPatterns inputs = ItemPatterns.concat(own, ItemPatterns.parse(UncraftBuiltins.BLOCKED_INPUTS), ItemPatterns.parse(extraInputs));
        ItemPatterns results = ItemPatterns.concat(own, ItemPatterns.parse(UncraftBuiltins.NEVER_YIELD), ItemPatterns.parse(extraResults));
        for (String e : inputs.errors) {
            LOG.warn("拆解台：blockedItems 跳过 {}", e);
        }
        for (String e : results.errors) {
            LOG.warn("拆解台：blockedResults 跳过 {}", e);
        }
        return new UncraftRules.Rules(enabled, inputs, results, extraRecipes == null ? Collections.emptyList() : Arrays.asList(extraRecipes),
                Arrays.asList(UncraftBuiltins.DATA_KEYS));
    }

    public static boolean enabled() {
        return rules().enabled;
    }

    /** 放进去的这件整件不让拆 */
    public static boolean inputBlocked(ItemStack input) {
        return !inputVerdict(rules(), input).allowed();
    }

    public static UncraftRules.Verdict inputVerdict(UncraftRules.Rules r, ItemStack input) {
        NBTTagCompound tag = input.getTagCompound();
        Set<String> top = tag == null ? Collections.emptySet() : tag.getKeySet();
        Set<String> be = tag != null && tag.hasKey("BlockEntityTag", Constants.NBT.TAG_COMPOUND)
                ? tag.getCompoundTag("BlockEntityTag").getKeySet() : Collections.emptySet();
        return UncraftRules.input(r, id(input), input.getMetadata(), top, be);
    }

    /** 这条配方能不能拿来拆 {@code input}：{@code shown} 是拆解台为它摆出的材料（{@code getIngredients} 的结果，每个配方材料一个） */
    public static boolean allowed(ItemStack input, IRecipe recipe, ItemStack[] shown, @Nullable World world) {
        return verdict(rules(), input, recipe, shown, world).allowed();
    }

    public static UncraftRules.Verdict verdict(UncraftRules.Rules r, ItemStack input, IRecipe recipe, ItemStack[] shown, @Nullable World world) {
        if (!r.enabled) {
            return UncraftRules.Verdict.ALLOW;
        }
        try {
            ItemStack[] grid = layout(recipe, shown);
            boolean[] accepts = acceptsInput(recipe, input);
            List<UncraftRules.Part> parts = new ArrayList<>(9);
            for (int i = 0; i < 9; i++) {
                ItemStack s = grid[i];
                boolean isInput = accepts[i] || s.getItem() == input.getItem() && s.getMetadata() == input.getMetadata()
                        && covers(input, s.getTagCompound());
                parts.add(s.isEmpty() ? null : new UncraftRules.Part(id(s), s.getMetadata(), given(s), isInput));
            }
            ItemStack out = recipe.getRecipeOutput();
            boolean outputCovered = covers(input, out.getTagCompound());
            UncraftRules.Simulation sim = simulate(input, recipe, grid, world);
            return UncraftRules.recipe(r, name(recipe), parts, outputCovered, sim);
        } catch (RuntimeException | LinkageError e) {
            if (reported.add(name(recipe))) {
                LOG.warn("拆解台：检查配方 {} 时出错，这条配方不拿来拆", name(recipe), e);
            }
            return UncraftRules.Verdict.CHECK_FAILED;
        }
    }

    /**
     * 照拆解台的摆法把材料放进 3×3（有序配方按宽高摆，无序的从左上依次放），每格一个、通配数据值换成 0（同拆解台的
     * {@code normalizeIngredient}），都是副本。
     */
    static ItemStack[] layout(IRecipe recipe, ItemStack[] shown) {
        ItemStack[] grid = new ItemStack[9];
        Arrays.fill(grid, ItemStack.EMPTY);
        for (int i = 0; i < shown.length; i++) {
            int slot = slotOf(recipe, i);
            if (slot >= 0) {
                grid[slot] = normalized(shown[i]);
            }
        }
        return grid;
    }

    /** 第 {@code i} 个配方材料在拆解格里的位置（同拆解台：有序配方按宽高摆，无序的从左上依次放）；放不下时为 -1 */
    static int slotOf(IRecipe recipe, int i) {
        if (recipe instanceof IShapedRecipe) {
            int w = ((IShapedRecipe) recipe).getRecipeWidth();
            int h = ((IShapedRecipe) recipe).getRecipeHeight();
            if (w <= 0) {
                return -1;
            }
            int x = i % w;
            int y = i / w;
            return x < 3 && y < 3 && y < h ? x + y * 3 : -1;
        }
        return i < 9 ? i : -1;
    }

    /**
     * 每一格的配方材料认不认放进去的这件（如“任意羊毛 + 白色染料 → 白羊毛”的“任意羊毛”认白羊毛）：认的话这条配方是“改造”它的，
     * 拆的时候玩家还能在候选里挑别的颜色，拆一次白得一份染料。
     */
    static boolean[] acceptsInput(IRecipe recipe, ItemStack input) {
        boolean[] out = new boolean[9];
        ItemStack one = input.copy();
        one.setCount(1);
        List<Ingredient> ings = recipe.getIngredients();
        for (int i = 0; i < ings.size(); i++) {
            int slot = slotOf(recipe, i);
            if (slot >= 0 && ings.get(i) != Ingredient.EMPTY && ings.get(i).apply(one)) {
                out[slot] = true;
            }
        }
        return out;
    }

    private static ItemStack normalized(@Nullable ItemStack s) {
        if (s == null || s.isEmpty()) {
            return ItemStack.EMPTY;
        }
        ItemStack c = s.copy();
        if (c.getCount() > 1) {
            c.setCount(1);
        }
        if (c.getItemDamage() == OreDictionary.WILDCARD_VALUE) {
            c.setItemDamage(0);
        }
        return c;
    }

    /** 拆解台会把这一格给玩家：带容器的材料（水桶等，合成时还回空容器）拆解台标成拿不走 */
    static boolean given(ItemStack s) {
        return !s.isEmpty() && !s.getItem().hasContainerItem(s);
    }

    /** {@code tag}（可以是 null）里的东西 {@code input} 的 NBT 都有 */
    static boolean covers(ItemStack input, @Nullable NBTTagCompound tag) {
        if (tag == null || tag.isEmpty()) {
            return true;
        }
        return NBTUtil.areNBTEquals(tag, input.getTagCompound(), true);
    }

    /** 把材料摆回去试合成：配方认的话，看合成出来的是不是这件、合成后还回的东西拆解台给没给玩家 */
    static UncraftRules.Simulation simulate(ItemStack input, IRecipe recipe, ItemStack[] grid, @Nullable World world) {
        InventoryCrafting inv = new InventoryCrafting(new SimContainer(), 3, 3);
        for (int i = 0; i < 9; i++) {
            inv.setInventorySlotContents(i, grid[i].copy());
        }
        if (!recipe.matches(inv, world)) {
            return UncraftRules.Simulation.NOT_RUN;
        }
        ItemStack crafted = recipe.getCraftingResult(inv);
        boolean craftsInput = crafted.isEmpty()
                || crafted.getItem() == input.getItem() && covers(input, crafted.getTagCompound());
        NonNullList<ItemStack> left = recipe.getRemainingItems(inv);
        boolean keeps = false;
        for (int i = 0; i < left.size() && i < 9; i++) {
            if (!left.get(i).isEmpty() && given(grid[i])) {
                keeps = true;
                break;
            }
        }
        return new UncraftRules.Simulation(craftsInput, keeps);
    }

    /** 一格有几种候选材料时不让玩家挑（见 {@link UncraftRules#chooseVariant}）：改 {@code shown}，与拆解台 {@code getIngredients} 的结果一一对应 */
    public static void lockVariants(IRecipe recipe, ItemStack[] shown) {
        if (shown == null || !enabled()) {
            return;
        }
        List<Ingredient> ings = recipe.getIngredients();
        for (int i = 0; i < shown.length && i < ings.size(); i++) {
            ItemStack[] matching = ings.get(i).getMatchingStacks();
            if (matching.length <= 1) {
                continue;
            }
            String[] ids = new String[matching.length];
            int[] metas = new int[matching.length];
            for (int k = 0; k < matching.length; k++) {
                ids[k] = matching[k].isEmpty() ? "" : id(matching[k]);
                metas[k] = matching[k].getMetadata();
            }
            int pick = UncraftRules.chooseVariant(CHEAPER, ids, metas);
            if (pick >= 0) {
                shown[i] = matching[pick];
            }
        }
    }

    static String id(ItemStack s) {
        ResourceLocation n = s.getItem().getRegistryName();
        return n == null ? "" : n.toString();
    }

    static String name(IRecipe r) {
        ResourceLocation n = r.getRegistryName();
        return n == null ? r.getClass().getName() : n.toString();
    }

    /** 试合成用的容器：不连任何界面；带一个合成结果格，免得被当成机器自动合成（见 {@code item.HandPunch}） */
    static final class SimContainer extends Container {
        SimContainer() {
            addSlotToContainer(new SlotCrafting(null, new InventoryCrafting(this, 1, 1), new InventoryCraftResult(), 0, 0, 0));
        }

        @Override
        public boolean canInteractWith(EntityPlayer playerIn) {
            return false;
        }

        @Override
        public void onCraftMatrixChanged(net.minecraft.inventory.IInventory inventoryIn) {
        }
    }
}
