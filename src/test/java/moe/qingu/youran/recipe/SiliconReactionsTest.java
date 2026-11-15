package moe.qingu.youran.recipe;

import moe.qingu.youran.logic.reaction.FluidItemReaction;
import moe.qingu.youran.logic.reaction.ReactionPlan;
import moe.qingu.youran.logic.reaction.ReactionTable;
import moe.qingu.youran.logic.reaction.StackFacts;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

class SiliconReactionsTest {
    /** 假物品：注册名、数量 */
    static final class Stack {
        final String id;
        final int count;

        Stack(String id, int count) {
            this.id = id;
            this.count = count;
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
            return 0;
        }

        @Override
        public boolean hasOre(Stack stack, String oreName) {
            return false;
        }
    };

    /** 服主 11-15：1 硅粉 + 3000 mB 盐酸 → 1000 mB 三氯氢硅（纯度 90%，不带标记），不出物品 */
    @Test
    void trichlorosilane() {
        FluidItemReaction<Stack, ReactionItem> r = CannerReactions.trichlorosilane();
        assertEquals("h_cl_fluid", r.inputFluid());
        assertEquals(3000, r.inputAmount());
        assertEquals("<youranmod:silicon_dust:0> * 1", r.inputItem().toString());
        assertEquals("trichlorosilane", r.outputFluid());
        assertEquals(1000, r.outputAmount());
        assertNull(r.outputItem());
    }

    /** 配方匹配：流体按种类（注册名）、量要够，物品要是硅粉 */
    @Test
    void matching() {
        ReactionTable<Stack, ReactionItem> table = new ReactionTable<>(FACTS);
        FluidItemReaction<Stack, ReactionItem> r = CannerReactions.trichlorosilane();
        table.add(r);
        Stack dust = new Stack("youranmod:silicon_dust", 1);
        ReactionPlan<Stack, ReactionItem> ready = table.plan("h_cl_fluid", 3000, dust, x -> true);
        assertEquals(ReactionPlan.Status.READY, ready.status());
        assertSame(r, ready.reaction());
        assertEquals(ReactionPlan.Status.NOT_ENOUGH, table.plan("h_cl_fluid", 2999, dust, x -> true).status());
        assertEquals(ReactionPlan.Status.OUTPUT_BLOCKED, table.plan("h_cl_fluid", 8000, new Stack("youranmod:silicon_dust", 5), x -> false).status());
        assertEquals(ReactionPlan.Status.NO_MATCH, table.plan("water", 8000, dust, x -> true).status());
        assertEquals(ReactionPlan.Status.NO_MATCH, table.plan("h_cl_fluid", 8000, new Stack("ic2:dust", 1), x -> true).status());
        assertTrue(table.accepts(null, dust));
        assertTrue(table.accepts("h_cl_fluid", dust));
        assertFalse(table.accepts("trichlorosilane", dust), "装着三氯氢硅时不收硅粉（没有用三氯氢硅当输入的反应）");
    }
}
