package moe.qingu.youran.fluid;

import moe.qingu.youran.logic.purity.Purity;
import net.minecraft.nbt.NBTTagCompound;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

class FluidPurityTest {
    /** 服主 11-15：没有纯度标记的三氯氢硅一律按 90% 算 */
    @Test
    void untaggedIsNinetyPercent() {
        assertEquals(0.1, FluidPurity.impurity((NBTTagCompound) null));
        assertEquals(0.1, FluidPurity.impurity(new NBTTagCompound()));
        NBTTagCompound other = new NBTTagCompound();
        other.setString("Impurity", "很纯");
        assertEquals(0.1, FluidPurity.impurity(other), "标记不是数字时也按 90%");
        NBTTagCompound unrelated = new NBTTagCompound();
        unrelated.setInteger("pressurized", 1);
        assertEquals(0.1, FluidPurity.impurity(unrelated));
        assertEquals("90", Purity.percent(FluidPurity.impurity((NBTTagCompound) null)));
    }

    /** 90% 不写标记（和丢了标记的能合并），别的纯度写杂质比例，读回来一样 */
    @Test
    void tagRoundTrip() {
        assertNull(FluidPurity.tagFor(0.1));
        NBTTagCompound tag = FluidPurity.tagFor(0.001);
        assertEquals(0.001, tag.getDouble(FluidPurity.TAG_IMPURITY));
        assertEquals(0.001, FluidPurity.impurity(tag));
        double imp = Purity.DEFAULT_IMPURITY;
        for (int i = 0; i < 8; i++) {
            imp = FluidPurity.impurity(FluidPurity.tagFor(Purity.distilled(imp)));
        }
        assertEquals(1e-9, imp);
        // 整数类型的标记（命令手写的 Impurity:0）也认
        NBTTagCompound intTag = new NBTTagCompound();
        intTag.setInteger(FluidPurity.TAG_IMPURITY, 0);
        assertEquals(0, FluidPurity.impurity(intTag));
        NBTTagCompound bad = new NBTTagCompound();
        bad.setDouble(FluidPurity.TAG_IMPURITY, 3);
        assertEquals(1, FluidPurity.impurity(bad));
    }
}
