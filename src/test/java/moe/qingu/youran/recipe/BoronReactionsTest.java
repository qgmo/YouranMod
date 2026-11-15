package moe.qingu.youran.recipe;

import moe.qingu.youran.logic.reaction.FluidItemReaction;
import moe.qingu.youran.logic.reaction.ReactionPlan;
import moe.qingu.youran.logic.reaction.ReactionTable;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

class BoronReactionsTest {
    /** 服主 11-15：2 硼硅酸盐渣 + 500 mB 盐酸 → 1 IC2 二氧化硅粉，不出流体 */
    @Test
    void silicaFromSlag() {
        FluidItemReaction<SiliconReactionsTest.Stack, ReactionItem> r = CannerReactions.silicaFromSlag();
        assertEquals("h_cl_fluid", r.inputFluid());
        assertEquals(500, r.inputAmount());
        assertEquals("<youranmod:borosilicate_slag:0> * 2", r.inputItem().toString());
        assertNull(r.outputFluid());
        assertEquals("<ic2:dust:13> * 1", r.outputItem().toString());

        ReactionTable<SiliconReactionsTest.Stack, ReactionItem> table = new ReactionTable<>(SiliconReactionsTest.FACTS);
        table.add(CannerReactions.trichlorosilane());
        table.add(r);
        SiliconReactionsTest.Stack two = new SiliconReactionsTest.Stack("youranmod:borosilicate_slag", 2);
        assertEquals(ReactionPlan.Status.READY, table.plan("h_cl_fluid", 500, two, x -> true).status());
        assertEquals(r, table.plan("h_cl_fluid", 3000, two, x -> true).reaction(), "同是盐酸，按物品分开");
        assertEquals(ReactionPlan.Status.NOT_ENOUGH,
                table.plan("h_cl_fluid", 500, new SiliconReactionsTest.Stack("youranmod:borosilicate_slag", 1), x -> true).status());
        assertEquals(ReactionPlan.Status.NOT_ENOUGH, table.plan("h_cl_fluid", 499, two, x -> true).status());
    }
}
