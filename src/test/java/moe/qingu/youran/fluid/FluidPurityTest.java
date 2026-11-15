package moe.qingu.youran.fluid;

import moe.qingu.youran.logic.purity.Purity;
import net.minecraft.nbt.NBTTagCompound;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

class FluidPurityTest {
    /** 服主 11-15：没有纯度标记的三氯氢硅一律按 1 个 9（90%）算 */
    @Test
    void untaggedIsOneNine() {
        assertEquals(1, FluidPurity.nines((NBTTagCompound) null));
        assertEquals(1, FluidPurity.nines(new NBTTagCompound()));
        NBTTagCompound other = new NBTTagCompound();
        other.setString(FluidPurity.TAG_NINES, "很纯");
        assertEquals(1, FluidPurity.nines(other), "标记不是数字时也按 1 个 9");
        NBTTagCompound unrelated = new NBTTagCompound();
        unrelated.setInteger("pressurized", 1);
        assertEquals(1, FluidPurity.nines(unrelated));
        // 原来的双精度杂质比例不再认（未上线，不做兼容）
        NBTTagCompound old = new NBTTagCompound();
        old.setDouble("Impurity", 1e-9);
        assertEquals(1, FluidPurity.nines(old));
    }

    /** 1 个 9 不写标记（和丢了标记的能合并），别的写整数个数，读回来一样 */
    @Test
    void tagRoundTrip() {
        assertNull(FluidPurity.tagFor(1));
        assertNull(FluidPurity.tagFor(0), "少于 1 个按 1 个算，也不写");
        NBTTagCompound tag = FluidPurity.tagFor(3);
        assertEquals(3, tag.getInteger(FluidPurity.TAG_NINES));
        assertEquals(3, FluidPurity.nines(tag));
        int nines = Purity.DEFAULT_NINES;
        for (int i = 0; i < 8; i++) {
            nines = FluidPurity.nines(FluidPurity.tagFor(Purity.distilled(nines)));
        }
        assertEquals(9, nines);
        // 别的数字类型（命令手写的 Nines:9b、Nines:9.0d）也认
        NBTTagCompound byteTag = new NBTTagCompound();
        byteTag.setByte(FluidPurity.TAG_NINES, (byte) 9);
        assertEquals(9, FluidPurity.nines(byteTag));
        NBTTagCompound big = new NBTTagCompound();
        big.setInteger(FluidPurity.TAG_NINES, 999);
        assertEquals(Purity.MAX_NINES, FluidPurity.nines(big));
    }
}
