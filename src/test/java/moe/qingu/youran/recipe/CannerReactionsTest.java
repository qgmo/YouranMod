package moe.qingu.youran.recipe;

import moe.qingu.youran.logic.reaction.FluidItemReaction;
import net.minecraft.item.ItemStack;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

class CannerReactionsTest {
    /** 服主 11-14 定的比例：3 小撮铁粉 + 5000 mB 过热蒸汽 → 1 小撮磁粉 + 20 mB 氢气 */
    @Test
    void smallMagnetite() {
        FluidItemReaction<ItemStack, ReactionItem> r = CannerReactions.smallMagnetite();
        assertEquals("ic2superheated_steam", r.inputFluid());
        assertEquals(5000, r.inputAmount());
        assertEquals("dustTinyIron", r.inputItem().oreName());
        assertEquals(3, r.inputItem().count());
        assertEquals("ic2hydrogen", r.outputFluid());
        assertEquals(20, r.outputAmount());
        assertEquals("youranmod:small_magnetite_dust", r.outputItem().itemId());
        assertEquals("<youranmod:small_magnetite_dust:0> * 1", r.outputItem().toString());
    }

    /** 炼金化学：6 铁元素 + 5000 过热蒸汽 → 2 四氧化三铁 + 20 氢气；3 铁元素 + 2500 蒸汽 → 1 四氧化三铁 + 10 氢气 */
    @Test
    void alchemistry() {
        List<FluidItemReaction<ItemStack, ReactionItem>> rs = CannerReactions.alchemistry(600);
        assertEquals(2, rs.size());
        FluidItemReaction<ItemStack, ReactionItem> a = rs.get(0);
        assertEquals("ic2superheated_steam", a.inputFluid());
        assertEquals(5000, a.inputAmount());
        assertNull(a.inputItem().oreName());
        assertEquals(6, a.inputItem().count());
        assertEquals("<alchemistry:element:26> * 6", a.inputItem().toString());
        assertEquals("ic2hydrogen", a.outputFluid());
        assertEquals(20, a.outputAmount());
        assertEquals("<alchemistry:compound:600> * 2", a.outputItem().toString());
        FluidItemReaction<ItemStack, ReactionItem> b = rs.get(1);
        assertEquals("ic2steam", b.inputFluid());
        assertEquals(2500, b.inputAmount());
        assertEquals("<alchemistry:element:26> * 3", b.inputItem().toString());
        assertEquals(10, b.outputAmount());
        assertEquals("<alchemistry:compound:600> * 1", b.outputItem().toString());
    }
}
