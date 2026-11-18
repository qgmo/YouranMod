package moe.qingu.youran.logic.uncraft;

import moe.qingu.youran.logic.uncraft.UncraftRules.Part;
import moe.qingu.youran.logic.uncraft.UncraftRules.Rules;
import moe.qingu.youran.logic.uncraft.UncraftRules.Simulation;
import moe.qingu.youran.logic.uncraft.UncraftRules.Verdict;
import org.junit.jupiter.api.Test;

import java.util.Arrays;
import java.util.Collections;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;

class UncraftRulesTest {
    static final Simulation OK = new Simulation(true, false);

    static Rules rules(boolean enabled) {
        return new Rules(enabled, ItemPatterns.parse("minecraft:rail", "minecraft:stone@5"), ItemPatterns.parse("youranmod:chip"),
                Arrays.asList("minecraft:mapextending", " # 注释", ""), Arrays.asList("Board", "Floppy"));
    }

    static List<Part> parts(Part... p) {
        return Arrays.asList(p);
    }

    @Test
    void inputListAndDataKeys() {
        Rules r = rules(true);
        assertEquals(Verdict.INPUT_BLOCKED, UncraftRules.input(r, "minecraft:rail", 0, null, null));
        assertEquals(Verdict.INPUT_BLOCKED, UncraftRules.input(r, "minecraft:stone", 5, null, null));
        assertEquals(Verdict.ALLOW, UncraftRules.input(r, "minecraft:stone", 0, null, null));
        assertEquals(Verdict.CARRIES_DATA, UncraftRules.input(r, "scp:computer", 0, Collections.singleton("Board"), null));
        assertEquals(Verdict.CARRIES_DATA, UncraftRules.input(r, "x:block", 0, Collections.emptySet(), Collections.singleton("Floppy")),
                "方块物品的数据在 BlockEntityTag 里也算");
        assertEquals(Verdict.ALLOW, UncraftRules.input(r, "minecraft:iron_sword", 0, Collections.singleton("ench"), null));
        assertEquals(Verdict.ALLOW, UncraftRules.input(rules(false), "minecraft:rail", 0, null, null), "关掉开关时都照原样");
    }

    static Part p(String id, int meta) {
        return new Part(id, meta, true, false);
    }

    @Test
    void ordinaryRecipeIsAllowed() {
        List<Part> sword = parts(p("minecraft:iron_ingot", 0), null, null, p("minecraft:iron_ingot", 0), null, null,
                p("minecraft:stick", 0), null, null);
        assertEquals(Verdict.ALLOW, UncraftRules.recipe(rules(true), "minecraft:iron_sword", sword, true, OK));
        assertEquals(Verdict.ALLOW, UncraftRules.recipe(rules(true), "minecraft:iron_sword", sword, true, Simulation.NOT_RUN),
                "试不了合成时只按别的几条判");
    }

    @Test
    void eachRuleBlocks() {
        Rules r = rules(true);
        List<Part> paper = parts(p("minecraft:paper", 0));
        assertEquals(Verdict.RECIPE_BLOCKED, UncraftRules.recipe(r, "Minecraft:MapExtending", paper, true, OK));
        assertEquals(Verdict.YIELDS_BLOCKED, UncraftRules.recipe(r, "youranmod:eprom",
                parts(p("youranmod:chip", 5), p("youranmod:glass_tube", 0)), true, OK));
        assertEquals(Verdict.RETURNS_INPUT, UncraftRules.recipe(r, "quark:elytra_dyeing",
                parts(new Part("minecraft:elytra", 0, true, true), p("minecraft:dye", 4)), true, OK));
        assertEquals(Verdict.OUTPUT_NBT_MISMATCH, UncraftRules.recipe(r, "ic2:355", paper, false, OK));
        assertEquals(Verdict.CRAFTED_DIFFERS, UncraftRules.recipe(r, "x:coil", paper, true, new Simulation(false, false)));
        assertEquals(Verdict.KEEPS_INGREDIENT, UncraftRules.recipe(r, "cfm:cut_log_diamond",
                parts(p("minecraft:log", 0), p("minecraft:diamond_axe", 0)), true, new Simulation(true, true)));
        assertEquals(Verdict.ALLOW, UncraftRules.recipe(rules(false), "quark:elytra_dyeing",
                parts(new Part("minecraft:elytra", 0, true, true)), false, new Simulation(false, true)), "关掉开关时都照原样");
    }

    /** 拆解台不给的格子（带容器的材料，如水桶）不算拆出来的东西 */
    @Test
    void partsNotGivenAreIgnored() {
        Rules r = rules(true);
        assertEquals(Verdict.ALLOW, UncraftRules.recipe(r, "minecraft:cake",
                parts(new Part("minecraft:cake", 0, false, true), new Part("youranmod:chip", 0, false, false), p("minecraft:sugar", 0)), true, OK));
        assertEquals(Verdict.ALLOW, UncraftRules.recipe(r, "x:dyed",
                parts(p("minecraft:wool", 0), p("minecraft:dye", 1)), true, OK), "材料不是它自己就照常");
    }

    @Test
    void variantChoice() {
        ItemPatterns cheaper = ItemPatterns.parse("ic2:crafting@19", "quark:root_dye@0");
        assertEquals(-1, UncraftRules.chooseVariant(cheaper, new String[0], new int[0]));
        assertEquals(0, UncraftRules.chooseVariant(cheaper, new String[]{"minecraft:chest", "minecraft:ender_chest"}, new int[]{0, 0}),
                "没有替代品给第一种（普通箱子，不是末影箱）");
        assertEquals(1, UncraftRules.chooseVariant(cheaper, new String[]{"minecraft:diamond", "ic2:crafting"}, new int[]{0, 19}),
                "钻石 / 工业钻石给工业钻石");
        assertEquals(0, UncraftRules.chooseVariant(cheaper, new String[]{"minecraft:diamond", "ic2:crafting"}, new int[]{0, 18}),
                "数据值不对不算");
        assertEquals(1, UncraftRules.chooseVariant(cheaper, new String[]{"minecraft:dye", "quark:root_dye"}, new int[]{4, 0}));
    }

    /** JEI 拆解页：整件不让拆、配方不拿来拆的藏；检查出错的不知道（客户端没有世界）照旧显示 */
    @Test
    void hiddenInJei() {
        assertEquals(false, UncraftRules.hiddenInJei(Verdict.ALLOW, Verdict.ALLOW));
        assertEquals(true, UncraftRules.hiddenInJei(Verdict.INPUT_BLOCKED, null), "整件不让拆：不用看配方");
        assertEquals(true, UncraftRules.hiddenInJei(Verdict.CARRIES_DATA, Verdict.ALLOW));
        for (Verdict v : Verdict.values()) {
            boolean expected = v != Verdict.ALLOW && v != Verdict.CHECK_FAILED;
            assertEquals(expected, UncraftRules.hiddenInJei(Verdict.ALLOW, v), v.name());
        }
        assertEquals(false, UncraftRules.hiddenInJei(Verdict.ALLOW, null));
    }
}
