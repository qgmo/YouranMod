package moe.qingu.youran.compat.twilightforest;

import moe.qingu.youran.logic.uncraft.UncraftRules;
import moe.qingu.youran.logic.uncraft.UncraftRules.Verdict;
import net.minecraft.init.Bootstrap;
import net.minecraft.init.Items;
import net.minecraft.inventory.InventoryCrafting;
import net.minecraft.item.ItemStack;
import net.minecraft.item.crafting.IRecipe;
import net.minecraft.item.crafting.Ingredient;
import net.minecraft.item.crafting.ShapedRecipes;
import net.minecraft.item.crafting.ShapelessRecipes;
import net.minecraft.nbt.NBTTagCompound;
import net.minecraft.util.NonNullList;
import net.minecraft.util.ResourceLocation;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** 用原版的物品与配方类，照拆解台的摆法把材料交给 {@link UncraftGuard}（不用世界：这些配方的 matches 不看世界） */
class UncraftGuardTest {
    static UncraftRules.Rules rules;

    @BeforeAll
    static void boot() {
        Bootstrap.register();
        rules = UncraftGuard.build(true, new String[]{"minecraft:cookie"}, new String[]{"minecraft:nether_star"}, new String[]{"test:blocked"});
    }

    static NonNullList<Ingredient> ings(Object... o) {
        NonNullList<Ingredient> l = NonNullList.create();
        for (Object x : o) {
            l.add(x == null ? Ingredient.EMPTY : Ingredient.fromStacks((ItemStack) x));
        }
        return l;
    }

    static <T extends IRecipe> T named(T r, String name) {
        r.setRegistryName(new ResourceLocation(name));
        return r;
    }

    /** 拆解台 getIngredients 的结果：每个配方材料取第一种候选 */
    static ItemStack[] shown(IRecipe r) {
        ItemStack[] s = new ItemStack[r.getIngredients().size()];
        for (int i = 0; i < s.length; i++) {
            ItemStack[] m = r.getIngredients().get(i).getMatchingStacks();
            s[i] = m.length > 0 ? m[0] : ItemStack.EMPTY;
        }
        return s;
    }

    static Verdict verdict(ItemStack input, IRecipe r) {
        return UncraftGuard.verdict(rules, input, r, shown(r), null);
    }

    static ItemStack stack(net.minecraft.item.Item i) {
        return new ItemStack(i);
    }

    static IRecipe sword() {
        return named(new ShapedRecipes("", 1, 3, ings(stack(Items.IRON_INGOT), stack(Items.IRON_INGOT), stack(Items.STICK)),
                new ItemStack(Items.IRON_SWORD)), "minecraft:iron_sword");
    }

    /** 原版铁剑照常能拆（附了魔的也行：配方产物没有 NBT） */
    @Test
    void vanillaToolIsAllowed() {
        assertEquals(Verdict.ALLOW, verdict(new ItemStack(Items.IRON_SWORD), sword()));
        ItemStack ench = new ItemStack(Items.IRON_SWORD);
        ench.addEnchantment(net.minecraft.init.Enchantments.SHARPNESS, 2);
        assertEquals(Verdict.ALLOW, verdict(ench, sword()));
        assertEquals(Verdict.ALLOW, UncraftGuard.inputVerdict(rules, ench));
    }

    /** 写死的与额外的名单、带本服数据的 NBT */
    @Test
    void inputLists() {
        assertEquals(Verdict.INPUT_BLOCKED, UncraftGuard.inputVerdict(rules, new ItemStack(net.minecraft.init.Blocks.RAIL)));
        assertEquals(Verdict.INPUT_BLOCKED, UncraftGuard.inputVerdict(rules, new ItemStack(Items.COOKIE)), "配置额外加的");
        assertEquals(Verdict.INPUT_BLOCKED, UncraftGuard.inputVerdict(rules, new ItemStack(Items.DYE, 1, 15)), "骨粉");
        assertEquals(Verdict.ALLOW, UncraftGuard.inputVerdict(rules, new ItemStack(Items.DYE, 1, 4)), "青金石不在名单里");
        ItemStack board = new ItemStack(Items.PAPER);
        board.setTagCompound(new NBTTagCompound());
        board.getTagCompound().setTag("Board", new NBTTagCompound());
        assertEquals(Verdict.CARRIES_DATA, UncraftGuard.inputVerdict(rules, board));
        ItemStack block = new ItemStack(Items.PAPER);
        NBTTagCompound be = new NBTTagCompound();
        be.setTag("Floppy", new NBTTagCompound());
        block.setTagCompound(new NBTTagCompound());
        block.getTagCompound().setTag("BlockEntityTag", be);
        assertEquals(Verdict.CARRIES_DATA, UncraftGuard.inputVerdict(rules, block));
        assertEquals(Verdict.ALLOW, UncraftGuard.inputVerdict(UncraftGuard.build(false, null, null, null), new ItemStack(net.minecraft.init.Blocks.RAIL)));
    }

    /** 染色这类“它自己 + 一样东西 → 它自己”：拆一次白得那样东西 */
    @Test
    void recipeReturningTheInputIsBlocked() {
        IRecipe dye = named(new ShapelessRecipes("", new ItemStack(Items.ELYTRA), ings(stack(Items.ELYTRA), new ItemStack(Items.DYE, 1, 4))),
                "quark:elytra_dyeing");
        assertEquals(Verdict.RETURNS_INPUT, verdict(new ItemStack(Items.ELYTRA), dye));
    }

    /** “任意羊毛 + 染料 → 某色羊毛”：就算拆解格里挑的是别的颜色，这一格本来就认放进去的羊毛，来回拆能拆出无限的染料 */
    @Test
    void recipeWhoseIngredientAcceptsTheInputIsBlocked() {
        ItemStack anyWool = new ItemStack(net.minecraft.init.Blocks.WOOL, 1, net.minecraftforge.oredict.OreDictionary.WILDCARD_VALUE);
        IRecipe white = named(new ShapelessRecipes("", new ItemStack(net.minecraft.init.Blocks.WOOL, 1, 0),
                ings(anyWool, new ItemStack(Items.DYE, 1, 15))), "quark:wool");
        ItemStack[] orange = {new ItemStack(net.minecraft.init.Blocks.WOOL, 1, 1), new ItemStack(Items.DYE, 1, 15)};
        assertEquals(Verdict.RETURNS_INPUT, UncraftGuard.verdict(rules, new ItemStack(net.minecraft.init.Blocks.WOOL, 1, 0), white, orange, null));
        IRecipe string = named(new ShapedRecipes("", 2, 2, ings(stack(Items.STRING), stack(Items.STRING), stack(Items.STRING), stack(Items.STRING)),
                new ItemStack(net.minecraft.init.Blocks.WOOL)), "minecraft:string_to_wool");
        assertEquals(Verdict.ALLOW, verdict(new ItemStack(net.minecraft.init.Blocks.WOOL), string), "四根线做的照常拆回四根线");
    }

    /** 配方产物带 NBT（拆解台不比）：放进去的物品要有这些 NBT 才按这条拆 */
    @Test
    void outputNbtMustBeOnTheInput() {
        ItemStack out = new ItemStack(Items.PAPER);
        out.setTagCompound(new NBTTagCompound());
        out.getTagCompound().setString("blueprint", "light_source");
        IRecipe r = named(new ShapelessRecipes("", out, ings(stack(Items.BOOK), stack(Items.DIAMOND))), "test:blueprint");
        ItemStack plain = new ItemStack(Items.PAPER);
        assertEquals(Verdict.OUTPUT_NBT_MISMATCH, verdict(plain, r));
        ItemStack other = plain.copy();
        other.setTagCompound(new NBTTagCompound());
        other.getTagCompound().setString("blueprint", "molds");
        assertEquals(Verdict.OUTPUT_NBT_MISMATCH, verdict(other, r));
        ItemStack same = out.copy();
        same.getTagCompound().setInteger("extra", 1);
        assertEquals(Verdict.ALLOW, verdict(same, r), "多出来的 NBT 不碍事");
    }

    /** 合成结果由材料算出来（线圈长度、平板里的模块）：摆回去合成出来的要就是这件 */
    @Test
    void craftedResultMustMatchTheInput() {
        IRecipe coil = named(new ShapelessRecipes("", new ItemStack(Items.STRING), ings(stack(Items.STRING), stack(Items.STRING))) {
            @Override
            public ItemStack getCraftingResult(InventoryCrafting inv) {
                ItemStack s = new ItemStack(Items.STRING);
                s.setTagCompound(new NBTTagCompound());
                s.getTagCompound().setInteger("wireLength", 2);
                return s;
            }
        }, "test:coil");
        ItemStack shortCoil = new ItemStack(Items.STRING);
        shortCoil.setTagCompound(new NBTTagCompound());
        shortCoil.getTagCompound().setInteger("wireLength", 1);
        // 这条配方的材料里有它自己，先由“拆出它自己”挡住；换一种材料再看“合成出来不一样”
        assertEquals(Verdict.RETURNS_INPUT, verdict(shortCoil, coil));
        IRecipe coil2 = named(new ShapelessRecipes("", new ItemStack(Items.LEAD), ings(stack(Items.STRING), stack(Items.SLIME_BALL))) {
            @Override
            public ItemStack getCraftingResult(InventoryCrafting inv) {
                ItemStack s = new ItemStack(Items.LEAD);
                s.setTagCompound(new NBTTagCompound());
                s.getTagCompound().setInteger("wireLength", 2);
                return s;
            }
        }, "test:coil2");
        ItemStack lead1 = new ItemStack(Items.LEAD);
        lead1.setTagCompound(new NBTTagCompound());
        lead1.getTagCompound().setInteger("wireLength", 1);
        assertEquals(Verdict.CRAFTED_DIFFERS, verdict(lead1, coil2));
        ItemStack lead2 = lead1.copy();
        lead2.getTagCompound().setInteger("wireLength", 2);
        assertEquals(Verdict.ALLOW, verdict(lead2, coil2));
    }

    /** 合成时原样还回的材料（斧子、螺丝刀），拆解台却当材料给玩家 */
    @Test
    void ingredientKeptOnCraftingIsBlocked() {
        IRecipe cut = named(new ShapelessRecipes("", new ItemStack(Items.STICK, 8), ings(new ItemStack(net.minecraft.init.Blocks.LOG),
                stack(Items.DIAMOND_AXE))) {
            @Override
            public NonNullList<ItemStack> getRemainingItems(InventoryCrafting inv) {
                NonNullList<ItemStack> l = NonNullList.withSize(inv.getSizeInventory(), ItemStack.EMPTY);
                for (int i = 0; i < inv.getSizeInventory(); i++) {
                    if (inv.getStackInSlot(i).getItem() == Items.DIAMOND_AXE) {
                        l.set(i, inv.getStackInSlot(i).copy());
                    }
                }
                return l;
            }
        }, "test:cut_log");
        assertEquals(Verdict.KEEPS_INGREDIENT, verdict(new ItemStack(Items.STICK, 8), cut));
    }

    /** 带容器的材料（水桶）拆解台不给，合成时还回空桶也不算 */
    @Test
    void containerIngredientsAreFine() {
        IRecipe r = named(new ShapelessRecipes("", new ItemStack(Items.CAKE), ings(stack(Items.MILK_BUCKET), stack(Items.SUGAR), stack(Items.EGG))),
                "test:cake");
        assertEquals(Verdict.ALLOW, verdict(new ItemStack(Items.CAKE), r));
    }

    /** 不让拆出来的物品、配置的配方名 */
    @Test
    void resultsAndRecipeNames() {
        IRecipe star = named(new ShapelessRecipes("", new ItemStack(net.minecraft.init.Blocks.BEACON), ings(stack(Items.NETHER_STAR), stack(Items.GLASS_BOTTLE))),
                "test:beacon");
        assertEquals(Verdict.YIELDS_BLOCKED, verdict(new ItemStack(net.minecraft.init.Blocks.BEACON), star));
        IRecipe map = named(new ShapelessRecipes("", new ItemStack(Items.MAP), ings(stack(Items.PAPER), new ItemStack(Items.FILLED_MAP))),
                "test:mapext");
        assertEquals(Verdict.YIELDS_BLOCKED, verdict(new ItemStack(Items.MAP), map), "写死的：已绘制地图");
        IRecipe blocked = named(new ShapelessRecipes("", new ItemStack(Items.BOWL), ings(stack(Items.STICK))), "test:blocked");
        assertEquals(Verdict.RECIPE_BLOCKED, verdict(new ItemStack(Items.BOWL), blocked));
    }

    /** 配方自己出错：不拿来拆 */
    @Test
    void failingRecipeIsBlocked() {
        IRecipe bad = named(new ShapelessRecipes("", new ItemStack(Items.BOWL), ings(stack(Items.STICK))) {
            @Override
            public boolean matches(InventoryCrafting inv, net.minecraft.world.World worldIn) {
                throw new IllegalStateException("测试");
            }
        }, "test:bad");
        assertEquals(Verdict.CHECK_FAILED, verdict(new ItemStack(Items.BOWL), bad));
    }

    /** 有序配方照宽高摆进 3×3，通配数据值换成 0，每格一个 */
    @Test
    void layoutFollowsTheTable() {
        IRecipe r = new ShapedRecipes("", 2, 2, ings(new ItemStack(Items.DYE, 3, net.minecraftforge.oredict.OreDictionary.WILDCARD_VALUE), null,
                null, stack(Items.STICK)), new ItemStack(Items.BOWL));
        ItemStack[] g = UncraftGuard.layout(r, shown(r));
        assertEquals(Items.DYE, g[0].getItem());
        assertEquals(0, g[0].getMetadata());
        assertEquals(1, g[0].getCount());
        assertTrue(g[1].isEmpty());
        assertTrue(g[3].isEmpty());
        assertEquals(Items.STICK, g[4].getItem());
    }

    /** 一格有几种候选不让玩家挑：给第一种（金苹果，不是附魔金苹果；箱子，不是末影箱），有便宜的替代品时给替代品 */
    @Test
    void variantsAreLocked() {
        ItemStack[] s = {new ItemStack(Items.GOLDEN_APPLE, 1, 1)};
        UncraftGuard.lockVariants(oneSlot(new ItemStack(Items.GOLDEN_APPLE, 1, 0), new ItemStack(Items.GOLDEN_APPLE, 1, 1)), s);
        assertEquals(0, s[0].getMetadata());

        ItemStack[] chest = {new ItemStack(net.minecraft.init.Blocks.ENDER_CHEST)};
        UncraftGuard.lockVariants(oneSlot(new ItemStack(net.minecraft.init.Blocks.CHEST), new ItemStack(net.minecraft.init.Blocks.ENDER_CHEST)), chest);
        assertEquals(net.minecraft.item.Item.getItemFromBlock(net.minecraft.init.Blocks.CHEST), chest[0].getItem());

        ItemStack only = new ItemStack(Items.BONE);
        ItemStack[] t = {only};
        UncraftGuard.lockVariants(oneSlot(new ItemStack(Items.BONE)), t);
        assertSame(only, t[0], "只有一种候选的不动");
    }

    static IRecipe oneSlot(ItemStack... candidates) {
        return new ShapelessRecipes("", new ItemStack(Items.SUGAR), ings(stack(Items.SUGAR))) {
            final NonNullList<Ingredient> l = NonNullList.from(Ingredient.EMPTY, Ingredient.fromStacks(candidates));

            @Override
            public NonNullList<Ingredient> getIngredients() {
                return l;
            }
        };
    }

    // ---- JEI 拆解页（服主 11-18）：页上把配方产物当作放进去的东西，只用写死的规则 ----

    static UncraftGuard.JeiView jei(IRecipe r) {
        return UncraftGuard.jeiView(UncraftGuard.builtinRules(), r);
    }

    /** 铁剑照常显示，显示的与 Twilight Tweaks 原来的一样（不用换） */
    @Test
    void jeiShowsOrdinaryRecipes() {
        UncraftGuard.JeiView v = jei(sword());
        assertEquals(false, v.hidden);
        assertEquals(false, v.differs);
        assertEquals(3, v.slots.size());
        assertEquals(Items.IRON_INGOT, v.slots.get(0).get(0).getItem());
        assertEquals(Items.STICK, v.slots.get(2).get(0).getItem());
    }

    /** 产物整件不让拆的（铁锭、骨粉、铁轨）：藏 */
    @Test
    void jeiHidesBlockedOutputs() {
        IRecipe ingot = named(new ShapelessRecipes("", new ItemStack(Items.IRON_INGOT, 9), ings(new ItemStack(net.minecraft.init.Blocks.IRON_BLOCK))),
                "minecraft:iron_ingot_from_block");
        assertTrue(jei(ingot).hidden);
        IRecipe bonemeal = named(new ShapelessRecipes("", new ItemStack(Items.DYE, 3, 15), ings(stack(Items.BONE))), "minecraft:bone_meal_from_bone");
        assertTrue(jei(bonemeal).hidden);
        IRecipe lapis = named(new ShapelessRecipes("", new ItemStack(Items.DYE, 9, 4), ings(new ItemStack(net.minecraft.init.Blocks.LAPIS_BLOCK))),
                "minecraft:lapis_lazuli");
        assertEquals(false, jei(lapis).hidden, "同一种物品别的数据值（青金石）照常");
        assertNull(jei(ingot).slots);
    }

    /** 按机制挡住的配方：拆出它自己、摆回去合成出来不一样、合成时还回的工具，都藏 */
    @Test
    void jeiHidesRecipesTheTableRejects() {
        IRecipe dye = named(new ShapelessRecipes("", new ItemStack(Items.ELYTRA), ings(stack(Items.ELYTRA), new ItemStack(Items.DYE, 1, 4))),
                "quark:elytra_dyeing");
        assertTrue(jei(dye).hidden);
        ItemStack anyWool = new ItemStack(net.minecraft.init.Blocks.WOOL, 1, net.minecraftforge.oredict.OreDictionary.WILDCARD_VALUE);
        IRecipe white = named(new ShapelessRecipes("", new ItemStack(net.minecraft.init.Blocks.WOOL, 1, 0),
                ings(anyWool, new ItemStack(Items.DYE, 1, 15))), "quark:wool");
        assertTrue(jei(white).hidden, "任意羊毛认产物本身");
        IRecipe coil = named(new ShapelessRecipes("", new ItemStack(Items.LEAD), ings(stack(Items.STRING), stack(Items.SLIME_BALL))) {
            @Override
            public ItemStack getCraftingResult(InventoryCrafting inv) {
                ItemStack s = new ItemStack(Items.LEAD);
                s.setTagCompound(new NBTTagCompound());
                s.getTagCompound().setInteger("wireLength", 2);
                return s;
            }
        }, "test:coil2");
        assertTrue(jei(coil).hidden, "产物没写的 NBT 是合成时算出来的");
        IRecipe cut = named(new ShapelessRecipes("", new ItemStack(Items.STICK, 8), ings(new ItemStack(net.minecraft.init.Blocks.LOG),
                stack(Items.DIAMOND_AXE))) {
            @Override
            public NonNullList<ItemStack> getRemainingItems(InventoryCrafting inv) {
                NonNullList<ItemStack> l = NonNullList.withSize(inv.getSizeInventory(), ItemStack.EMPTY);
                for (int i = 0; i < inv.getSizeInventory(); i++) {
                    if (inv.getStackInSlot(i).getItem() == Items.DIAMOND_AXE) {
                        l.set(i, inv.getStackInSlot(i).copy());
                    }
                }
                return l;
            }
        }, "test:cut_log");
        assertTrue(jei(cut).hidden);
        IRecipe map = named(new ShapelessRecipes("", new ItemStack(Items.MAP), ings(stack(Items.PAPER), new ItemStack(Items.FILLED_MAP))),
                "test:mapext");
        assertTrue(jei(map).hidden, "拆出已绘制地图");
    }

    /** 产物带 NBT 的配方：页上放进去的就是这件带 NBT 的产物，照常显示（拆解台挡的是不带这些 NBT 的） */
    @Test
    void jeiShowsOutputWithNbt() {
        ItemStack out = new ItemStack(Items.PAPER);
        out.setTagCompound(new NBTTagCompound());
        out.getTagCompound().setString("blueprint", "light_source");
        IRecipe r = named(new ShapelessRecipes("", out, ings(stack(Items.BOOK), stack(Items.DIAMOND))), "test:blueprint");
        assertEquals(false, jei(r).hidden);
    }

    /** 只用写死的规则：配置另加的名单（这个测试类的 rules 里有饼干）客户端不知道，照旧显示 */
    @Test
    void jeiIgnoresConfiguredLists() {
        IRecipe cookie = named(new ShapelessRecipes("", new ItemStack(Items.COOKIE, 8), ings(stack(Items.WHEAT), new ItemStack(Items.DYE, 1, 3),
                stack(Items.WHEAT))), "minecraft:cookie");
        assertEquals(Verdict.INPUT_BLOCKED, UncraftGuard.inputVerdict(rules, new ItemStack(Items.COOKIE)));
        assertEquals(false, jei(cookie).hidden);
    }

    /** 检查时配方出错（客户端没有世界，可能只是因为这个）：照旧显示 */
    @Test
    void jeiShowsFailingRecipes() {
        IRecipe bad = named(new ShapelessRecipes("", new ItemStack(Items.BOWL), ings(stack(Items.STICK))) {
            @Override
            public boolean matches(InventoryCrafting inv, net.minecraft.world.World worldIn) {
                throw new IllegalStateException("测试");
            }
        }, "test:bad");
        UncraftGuard.JeiView v = jei(bad);
        assertEquals(false, v.hidden);
        assertEquals(Items.STICK, v.slots.get(0).get(0).getItem());
    }

    /** 几种候选的格子显示拆解台实际给的那一种：金苹果（不是附魔金苹果）；与拆解台摆的（getIngredients 再固定）一致 */
    @Test
    void jeiShowsTheLockedVariant() {
        IRecipe apple = oneSlot(new ItemStack(Items.GOLDEN_APPLE, 1, 0), new ItemStack(Items.GOLDEN_APPLE, 1, 1));
        UncraftGuard.JeiView v = jei(apple);
        assertEquals(false, v.hidden);
        assertTrue(v.differs);
        assertEquals(1, v.slots.get(0).size());
        assertEquals(0, v.slots.get(0).get(0).getMetadata());
        assertEquals(2, UncraftGuard.twilightTweaksSlots(apple).get(0).size(), "Twilight Tweaks 原来两种都列");
        // 与拆解台一致：暮色森林的 getIngredients 按箭头取一种候选，再由 lockVariants 固定
        for (int cycle = 0; cycle < 2; cycle++) {
            ItemStack[] m = apple.getIngredients().get(0).getMatchingStacks();
            ItemStack[] table = {m[cycle % m.length]};
            UncraftGuard.lockVariants(apple, table);
            assertTrue(ItemStack.areItemStacksEqual(table[0], UncraftGuard.fixedIngredients(apple)[0]));
        }
    }

    /** 通配数据值（任意木板、任意羊毛）：拆解台给数据值 0 的，页上也只显示它（JEI 原来会展开成所有种类） */
    @Test
    void jeiShowsWildcardAsMetaZero() {
        ItemStack anyPlanks = new ItemStack(net.minecraft.init.Blocks.PLANKS, 1, net.minecraftforge.oredict.OreDictionary.WILDCARD_VALUE);
        IRecipe chest = named(new ShapedRecipes("", 3, 3, ings(anyPlanks, anyPlanks, anyPlanks, anyPlanks, null, anyPlanks, anyPlanks, anyPlanks,
                anyPlanks), new ItemStack(net.minecraft.init.Blocks.CHEST)), "minecraft:chest");
        UncraftGuard.JeiView v = jei(chest);
        assertEquals(false, v.hidden);
        assertTrue(v.differs);
        assertEquals(0, v.slots.get(0).get(0).getMetadata());
        assertTrue(v.slots.get(4).isEmpty(), "空格");
        assertEquals(1, v.slots.get(0).get(0).getCount());
    }

    /** 第一种候选是带容器的（水桶），拆解台给的是它（拿不走），页上这格不显示，不再显示后面能拿走的候选（雪球） */
    @Test
    void jeiShowsNothingForContainerVariant() {
        IRecipe r = oneSlot(new ItemStack(Items.WATER_BUCKET), new ItemStack(Items.SNOWBALL));
        UncraftGuard.JeiView v = jei(r);
        assertEquals(false, v.hidden);
        assertTrue(v.differs);
        assertTrue(v.slots.get(0).isEmpty());
        assertEquals(Items.SNOWBALL, UncraftGuard.twilightTweaksSlots(r).get(0).get(0).getItem(), "Twilight Tweaks 原来显示雪球");
        IRecipe cake = named(new ShapelessRecipes("", new ItemStack(Items.CAKE), ings(stack(Items.MILK_BUCKET), stack(Items.SUGAR))), "test:cake");
        UncraftGuard.JeiView c = jei(cake);
        assertEquals(false, c.differs, "只有一种候选的带容器材料，Twilight Tweaks 原来也不显示");
        assertTrue(c.slots.get(0).isEmpty());
    }
}
