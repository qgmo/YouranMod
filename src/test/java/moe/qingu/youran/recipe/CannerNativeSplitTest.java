package moe.qingu.youran.recipe;

import moe.qingu.youran.logic.reaction.FluidItemReaction;
import moe.qingu.youran.logic.reaction.ItemIngredient;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** 哪些内置反应改登记进 IC2 原生的富集配方表（只出流体、输入流体不靠 NBT 区分），哪些留在我们的反应表 */
class CannerNativeSplitTest {
    @Test
    void fluidOnlyOutputsGoNative() {
        assertTrue(CannerReactions.isNativeEnrichment(CannerReactions.<SiliconReactionsTest.Stack>trichlorosilane()));
        // 正胶：酚醛树脂 + 重氮萘醌磺酰氯粉 → 光刻胶（产物带 Resist:"positive"，IC2 原样灌进输出罐）
        List<FluidItemReaction<net.minecraft.item.ItemStack, ReactionItem>> positive = PositiveResistRecipes.cannerReactions();
        assertFalse(CannerReactions.isNativeEnrichment(positive.get(0)), "出物品的（磺酰氯粉）留在我们的表");
        assertTrue(CannerReactions.isNativeEnrichment(positive.get(1)));
        assertEquals("photoresist#positive", positive.get(1).outputFluid());
        // 负胶线：四氯化锡、带叠氮基的芳烃、光刻胶（负胶）原生；两条出环化橡胶的留下
        List<FluidItemReaction<net.minecraft.item.ItemStack, ReactionItem>> resist = ResistRecipes.cannerReactions();
        boolean[] expected = {true, false, false, true, true};
        for (int i = 0; i < expected.length; i++) {
            assertEquals(expected[i], CannerReactions.isNativeEnrichment(resist.get(i)), "负胶线第 " + i + " 条");
        }
    }

    @Test
    void itemOutputsAndComputedOutputsStay() {
        assertFalse(CannerReactions.isNativeEnrichment(CannerReactions.<SiliconReactionsTest.Stack>silicaFromSlag()));
        assertFalse(CannerReactions.isNativeEnrichment(CannerReactions.smallMagnetite()), "出流体也出物品");
        assertFalse(CannerReactions.isNativeEnrichment(CannerReactions.pvcFromPolyethylene()));
        assertFalse(CannerReactions.isNativeEnrichment(CannerReactions.wafer(moe.qingu.youran.logic.purity.WaferMold.GOLD)), "晶圆按输入混合气的纯度现算");
    }

    @Test
    void variantInputsStay() {
        // 输入流体是 NBT 变种（键里带 #）：原生表没有这个键，留下
        FluidItemReaction<net.minecraft.item.ItemStack, ReactionItem> r = new FluidItemReaction<>("photoresist#negative", 100,
                ItemIngredient.ore("dustX", 1), "water", 100, null);
        assertFalse(CannerReactions.isNativeEnrichment(r));
    }

    @Test
    void pcbFerricSolutionGoesNative() {
        long fluidOnly = PcbRecipes.cannerReactions().stream().filter(CannerReactions::isNativeEnrichment).count();
        assertEquals(PcbRecipes.WATERS.length, fluidOnly, "三氯化铁溶液：每种水一条");
    }

    /** 只出物品（不出流体）的反应走“装罐”模式；出流体的（含又出物品的）留在富集模式；同一条只在一边 */
    @Test
    void bottlingSplit() {
        assertTrue(CannerReactions.isBottling(CannerReactions.<SiliconReactionsTest.Stack>silicaFromSlag()), "硼硅酸盐渣去硼：只出物品");
        assertTrue(CannerReactions.isBottling(CannerReactions.pvcFromPolyethylene()), "聚氯乙烯：只出物品");
        assertTrue(CannerReactions.isBottling(CannerReactions.wafer(moe.qingu.youran.logic.purity.WaferMold.GOLD)), "硅晶圆：只出物品（产物仍按输入现算）");
        assertFalse(CannerReactions.isBottling(CannerReactions.smallMagnetite()), "小撮磁粉又出氢气：留在富集模式");
        assertFalse(CannerReactions.isBottling(CannerReactions.<SiliconReactionsTest.Stack>trichlorosilane()), "只出流体：原生富集");
        for (FluidItemReaction<net.minecraft.item.ItemStack, ReactionItem> r : PcbRecipes.cannerReactions()) {
            assertFalse(CannerReactions.isBottling(r) && CannerReactions.isNativeEnrichment(r), "同一条不能两边都做：" + r);
        }
        long bottled = PcbRecipes.cannerReactions().stream().filter(CannerReactions::isBottling).count();
        assertTrue(bottled > 0, "主板涂胶、蚀刻、酚醛基板、三氯化铁粉都只出物品");
    }
}
