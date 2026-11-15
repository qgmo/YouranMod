package moe.qingu.youran.item;

import moe.qingu.youran.fluid.FluidPurity;
import moe.qingu.youran.logic.purity.Purity;
import moe.qingu.youran.logic.purity.WaferMold;
import moe.qingu.youran.logic.purity.WaferRules;
import net.minecraft.nbt.NBTTagCompound;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;

class WaferNbtTest {
    /** 纯度一路传下去：三氯氢硅（蒸 8–12 遍）→ 混合气 → 硅晶圆 → 高精硅晶圆，个数不变；铸模材质跟着晶圆走 */
    @Test
    void purityChain() {
        for (int passes = 8; passes <= 12; passes++) {
            int tcs = Purity.distilled(Purity.DEFAULT_NINES, passes);
            NBTTagCompound tcsTag = FluidPurity.tagFor(tcs);
            int mixture = WaferRules.mixtureNines(FluidPurity.nines(tcsTag));
            NBTTagCompound mixtureTag = FluidPurity.tagFor(mixture);
            for (WaferMold mold : WaferMold.values()) {
                NBTTagCompound wafer = WaferNbt.tag(WaferRules.waferNines(FluidPurity.nines(mixtureTag)), mold);
                NBTTagCompound precision = WaferNbt.copy(wafer);
                assertEquals(passes + 1, WaferNbt.nines(wafer));
                assertEquals(passes + 1, WaferNbt.nines(precision));
                assertSame(mold, WaferNbt.mold(precision));
                assertEquals(wafer, precision);
            }
        }
    }

    /** 晶圆的标记：纯度总写（1 个 9 也写），铸模按名字；读回来一样 */
    @Test
    void tags() {
        NBTTagCompound tag = WaferNbt.tag(9, WaferMold.GOLD);
        assertEquals(9, tag.getInteger("Nines"));
        assertEquals("gold", tag.getString("Mold"));
        NBTTagCompound one = WaferNbt.tag(1, null);
        assertEquals(1, one.getInteger("Nines"), "1 个 9 也写上");
        assertFalse(one.hasKey("Mold"));
        assertNull(WaferNbt.mold(one));
        assertEquals(1, WaferNbt.nines(null), "没有标记按 1 个 9");
        assertNull(WaferNbt.mold(null));
        NBTTagCompound odd = new NBTTagCompound();
        odd.setString("Mold", "diamond");
        assertNull(WaferNbt.mold(odd), "认不出的铸模当没有");
        assertSame(WaferMold.CLAY, WaferNbt.mold(WaferNbt.tag(10, WaferMold.CLAY)));
        // 照抄只留纯度与铸模
        NBTTagCompound extra = WaferNbt.tag(11, WaferMold.CLAY);
        extra.setString("display", "x");
        NBTTagCompound copied = WaferNbt.copy(extra);
        assertFalse(copied.hasKey("display"));
        assertEquals(11, WaferNbt.nines(copied));
    }
}
