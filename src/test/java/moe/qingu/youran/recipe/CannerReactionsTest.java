package moe.qingu.youran.recipe;

import moe.qingu.youran.logic.reaction.FluidItemReaction;
import net.minecraft.item.ItemStack;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

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
}
