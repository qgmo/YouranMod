package moe.qingu.youran.logic.reaction;

import org.junit.jupiter.api.Test;

import java.util.Arrays;
import java.util.Collections;
import java.util.HashSet;
import java.util.Set;
import java.util.function.Predicate;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ReactionTableTest {
    /** 假物品：注册名、元数据、数量、矿物词典名 */
    static final class Stack {
        final String id;
        final int meta;
        final int count;
        final Set<String> ores;

        Stack(String id, int meta, int count, String... ores) {
            this.id = id;
            this.meta = meta;
            this.count = count;
            this.ores = new HashSet<>(Arrays.asList(ores));
        }
    }

    static final StackFacts<Stack> FACTS = new StackFacts<Stack>() {
        @Override
        public boolean isEmpty(Stack stack) {
            return stack.count <= 0;
        }

        @Override
        public int count(Stack stack) {
            return stack.count;
        }

        @Override
        public String itemId(Stack stack) {
            return stack.id;
        }

        @Override
        public int meta(Stack stack) {
            return stack.meta;
        }

        @Override
        public boolean hasOre(Stack stack, String oreName) {
            return stack.ores.contains(oreName);
        }
    };

    static final String STEAM = "ic2superheated_steam";
    static final String HYDROGEN = "ic2hydrogen";
    static final Predicate<FluidItemReaction<Stack, String>> FITS = r -> true;
    static final Predicate<FluidItemReaction<Stack, String>> FULL = r -> false;

    static Stack ironDust(int count) {
        // 两个模组的铁粉都在 dustIron 下
        return new Stack("ic2:dust", 8, count, "dustIron");
    }

    /** 测试用的反应（比例与内置的无关，内置的见 recipe.CannerReactionsTest） */
    static FluidItemReaction<Stack, String> magnetite() {
        return new FluidItemReaction<>(STEAM, 1000, ItemIngredient.ore("dustIron", 1), HYDROGEN, 1000, "youranmod:magnetite_dust");
    }

    static ReactionTable<Stack, String> table(FluidItemReaction<Stack, String>... reactions) {
        ReactionTable<Stack, String> t = new ReactionTable<>(FACTS);
        for (FluidItemReaction<Stack, String> r : reactions) {
            t.add(r);
        }
        return t;
    }

    @Test
    void matchesOreDictionaryInput() {
        ReactionTable<Stack, String> t = table(magnetite());
        ReactionPlan<Stack, String> p = t.plan(STEAM, 1000, ironDust(1), FITS);
        assertEquals(ReactionPlan.Status.READY, p.status());
        assertEquals("youranmod:magnetite_dust", p.reaction().outputItem());
        assertEquals(HYDROGEN, p.reaction().outputFluid());
        assertEquals(1000, p.reaction().outputAmount());
        // 别的模组的铁粉（不同注册名）只要在 dustIron 下也算
        assertEquals(ReactionPlan.Status.READY, t.plan(STEAM, 8000, new Stack("thermalfoundation:material", 0, 64, "dustIron"), FITS).status());
    }

    @Test
    void wrongFluidOrItemIsNoMatch() {
        ReactionTable<Stack, String> t = table(magnetite());
        // 普通蒸汽、水不行
        assertEquals(ReactionPlan.Status.NO_MATCH, t.plan("ic2steam", 8000, ironDust(1), FITS).status());
        assertEquals(ReactionPlan.Status.NO_MATCH, t.plan("water", 8000, ironDust(1), FITS).status());
        // 不在 dustIron 下的东西不行（IC2 原来的富集配方照常走）
        assertEquals(ReactionPlan.Status.NO_MATCH, t.plan(STEAM, 8000, new Stack("minecraft:dye", 4, 1, "gemLapis"), FITS).status());
        // 罐空、槽空
        assertEquals(ReactionPlan.Status.NO_MATCH, t.plan(null, 0, ironDust(1), FITS).status());
        assertEquals(ReactionPlan.Status.NO_MATCH, t.plan(STEAM, 1000, null, FITS).status());
        assertEquals(ReactionPlan.Status.NO_MATCH, t.plan(STEAM, 1000, ironDust(0), FITS).status());
        assertNull(t.plan("water", 1000, ironDust(1), FITS).reaction());
        assertFalse(t.plan("water", 1000, ironDust(1), FITS).handled());
    }

    @Test
    void notEnoughFluidOrItemsWaits() {
        ReactionTable<Stack, String> t = table(new FluidItemReaction<>(STEAM, 1000, ItemIngredient.ore("dustIron", 3), HYDROGEN, 1000, "m"));
        assertEquals(ReactionPlan.Status.NOT_ENOUGH, t.plan(STEAM, 999, ironDust(3), FITS).status());
        assertEquals(ReactionPlan.Status.NOT_ENOUGH, t.plan(STEAM, 1000, ironDust(2), FITS).status());
        assertTrue(t.plan(STEAM, 1000, ironDust(2), FITS).handled());
        assertEquals(ReactionPlan.Status.READY, t.plan(STEAM, 1000, ironDust(3), FITS).status());
        assertEquals(ReactionPlan.Status.READY, t.plan(STEAM, 5000, ironDust(64), FITS).status());
    }

    @Test
    void fullOutputBlocks() {
        ReactionTable<Stack, String> t = table(magnetite());
        ReactionPlan<Stack, String> p = t.plan(STEAM, 1000, ironDust(1), FULL);
        assertEquals(ReactionPlan.Status.OUTPUT_BLOCKED, p.status());
        assertTrue(p.handled());
        // 输出判断拿到的是要做的那条
        FluidItemReaction<Stack, String>[] seen = new FluidItemReaction[1];
        t.plan(STEAM, 1000, ironDust(1), r -> {
            seen[0] = r;
            return false;
        });
        assertSame(t.reactions().get(0), seen[0]);
        // 输入不够时不问输出
        t.plan(STEAM, 10, ironDust(1), r -> {
            throw new AssertionError("输入不够时不该问输出");
        });
    }

    @Test
    void exactItemAndMeta() {
        ReactionTable<Stack, String> t = table(
                new FluidItemReaction<>("water", 1000, ItemIngredient.item("minecraft:dye", 4, 1), null, 0, "lapis_out"),
                new FluidItemReaction<>("water", 1000, ItemIngredient.item("minecraft:wool", ItemIngredient.ANY_META, 2), "ic2coolant", 500, null));
        assertEquals("lapis_out", t.plan("water", 1000, new Stack("minecraft:dye", 4, 1), FITS).reaction().outputItem());
        assertEquals(ReactionPlan.Status.NO_MATCH, t.plan("water", 1000, new Stack("minecraft:dye", 3, 1), FITS).status());
        // 任意元数据
        ReactionPlan<Stack, String> wool = t.plan("water", 1000, new Stack("minecraft:wool", 14, 2), FITS);
        assertEquals(ReactionPlan.Status.READY, wool.status());
        assertNull(wool.reaction().outputItem());
        assertEquals("ic2coolant", wool.reaction().outputFluid());
    }

    @Test
    void firstAddedWins() {
        FluidItemReaction<Stack, String> a = new FluidItemReaction<>(STEAM, 1000, ItemIngredient.ore("dustIron", 1), HYDROGEN, 1000, "a");
        FluidItemReaction<Stack, String> b = new FluidItemReaction<>(STEAM, 500, ItemIngredient.ore("dustIron", 1), HYDROGEN, 400, "b");
        ReactionTable<Stack, String> t = table(a, b);
        assertSame(a, t.plan(STEAM, 1000, ironDust(1), FITS).reaction());
        // 前一条量不够时用后一条
        assertSame(b, t.plan(STEAM, 600, ironDust(1), FITS).reaction());
        // 都不够：不够
        assertEquals(ReactionPlan.Status.NOT_ENOUGH, t.plan(STEAM, 100, ironDust(1), FITS).status());
    }

    @Test
    void customIngredient() {
        ReactionTable<Stack, String> t = table(new FluidItemReaction<>("water", 250,
                ItemIngredient.custom(s -> s.id.startsWith("minecraft:"), 2, "<minecraft:*>"), "lava", 250, null));
        assertEquals(ReactionPlan.Status.READY, t.plan("water", 250, new Stack("minecraft:stone", 0, 2), FITS).status());
        assertEquals(ReactionPlan.Status.NOT_ENOUGH, t.plan("water", 250, new Stack("minecraft:stone", 0, 1), FITS).status());
        assertEquals(ReactionPlan.Status.NO_MATCH, t.plan("water", 250, new Stack("ic2:dust", 0, 2), FITS).status());
    }

    @Test
    void removeAndReAdd() {
        ReactionTable<Stack, String> t = table(magnetite());
        assertEquals(1, t.removeIf(r -> "youranmod:magnetite_dust".equals(r.outputItem())));
        assertEquals(ReactionPlan.Status.NO_MATCH, t.plan(STEAM, 1000, ironDust(1), FITS).status());
        assertEquals(0, t.removeIf(r -> "youranmod:magnetite_dust".equals(r.outputItem())));
        // 删掉后用 CraftTweaker 重新加一条比例不同的
        t.add(new FluidItemReaction<>(STEAM, 4000, ItemIngredient.ore("dustIron", 3), HYDROGEN, 4000, "youranmod:magnetite_dust"));
        assertEquals(ReactionPlan.Status.NOT_ENOUGH, t.plan(STEAM, 1000, ironDust(1), FITS).status());
        assertEquals(ReactionPlan.Status.READY, t.plan(STEAM, 4000, ironDust(3), FITS).status());
        assertEquals(1, t.clear());
        assertTrue(t.reactions().isEmpty());
    }

    @Test
    void acceptsLikeIc2Enrich() {
        ReactionTable<Stack, String> t = table(magnetite());
        // 罐空时只看物品
        assertTrue(t.accepts(null, ironDust(1)));
        // 罐里是这条反应的流体
        assertTrue(t.accepts(STEAM, ironDust(64)));
        // 罐里是别的流体：不收
        assertFalse(t.accepts("water", ironDust(1)));
        assertFalse(t.accepts(null, new Stack("minecraft:dye", 4, 1, "gemLapis")));
        assertFalse(t.accepts(null, ironDust(0)));
        assertFalse(t.accepts(null, null));
        assertFalse(table().accepts(null, ironDust(1)));
    }

    @Test
    void rejectsBadReactions() {
        ItemIngredient<Stack> iron = ItemIngredient.ore("dustIron", 1);
        assertThrows(IllegalArgumentException.class, () -> new FluidItemReaction<Stack, String>(null, 1000, iron, HYDROGEN, 1000, "m"));
        assertThrows(IllegalArgumentException.class, () -> new FluidItemReaction<Stack, String>(STEAM, 0, iron, HYDROGEN, 1000, "m"));
        assertThrows(NullPointerException.class, () -> new FluidItemReaction<Stack, String>(STEAM, 1000, null, HYDROGEN, 1000, "m"));
        assertThrows(IllegalArgumentException.class, () -> new FluidItemReaction<Stack, String>(STEAM, 1000, iron, HYDROGEN, 0, "m"));
        assertThrows(IllegalArgumentException.class, () -> new FluidItemReaction<Stack, String>(STEAM, 1000, iron, null, 0, null));
        assertThrows(IllegalArgumentException.class, () -> ItemIngredient.<Stack>ore("dustIron", 0));
        assertThrows(IllegalArgumentException.class, () -> ItemIngredient.<Stack>ore("", 1));
        assertThrows(IllegalArgumentException.class, () -> ItemIngredient.<Stack>item(null, 0, 1));
        // 只出物品、只出流体都行
        new FluidItemReaction<Stack, String>(STEAM, 1000, iron, null, 0, "m");
        new FluidItemReaction<Stack, String>(STEAM, 1000, iron, HYDROGEN, 1, null);
        assertEquals(Collections.emptyList(), table().reactions());
    }

    @Test
    void readOnlyView() {
        ReactionTable<Stack, String> t = table(magnetite());
        assertThrows(UnsupportedOperationException.class, () -> t.reactions().clear());
    }
}
