package moe.qingu.youran.api.pay;

import net.minecraft.nbt.NBTTagByteArray;
import net.minecraft.nbt.NBTTagCompound;
import net.minecraft.nbt.NBTTagFloat;
import net.minecraft.nbt.NBTTagIntArray;
import net.minecraft.nbt.NBTTagList;
import net.minecraft.nbt.NBTTagString;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class NbtCanonTest {
    static NBTTagCompound sample() {
        NBTTagCompound c = new NBTTagCompound();
        c.setByte("b", (byte) -3);
        c.setShort("s", (short) 300);
        c.setInteger("i", 7);
        c.setLong("l", 1L << 40);
        c.setFloat("f", 0.1f);
        c.setDouble("d", 1.0e-7);
        c.setString("str", "引号\"和\\反斜杠");
        c.setTag("ba", new NBTTagByteArray(new byte[]{1, -2}));
        c.setTag("ia", new NBTTagIntArray(new int[]{3, 4}));
        c.setTag("la", NbtCanon.longArray(5L, -6L));
        NBTTagList l = new NBTTagList();
        l.appendTag(new NBTTagString("x"));
        l.appendTag(new NBTTagString("y"));
        c.setTag("list", l);
        NBTTagCompound inner = new NBTTagCompound();
        inner.setString("z", "1");
        inner.setString("a", "2");
        c.setTag("inner", inner);
        return c;
    }

    /** 同样的 NBT 不管键加进去的顺序，写出来都一样；读回去再写还是一样 */
    @Test
    void canonicalAndRoundTrip() {
        NBTTagCompound a = sample();
        NBTTagCompound b = new NBTTagCompound();
        java.util.List<String> keys = new java.util.ArrayList<>(a.getKeySet());
        java.util.Collections.reverse(keys);
        for (String k : keys) {
            b.setTag(k, a.getTag(k).copy());
        }
        String ca = NbtCanon.write(a);
        assertEquals(ca, NbtCanon.write(b));
        assertTrue(ca.indexOf("\"ba\"") < ca.indexOf("\"str\""), "键按字典序");
        assertTrue(NbtCanon.roundTrips(ca));
        assertEquals(a, NbtCanon.parse(ca));
    }

    /** NaN 写出来读不回原样：不收 */
    @Test
    void nanDoesNotRoundTrip() {
        NBTTagCompound c = new NBTTagCompound();
        c.setTag("f", new NBTTagFloat(Float.NaN));
        assertFalse(NbtCanon.roundTrips(NbtCanon.write(c)));
        assertThrows(IllegalArgumentException.class, () -> NbtCanon.parse("{a:"));
    }

    /** 物品：ID + 数据值 + NBT 完全一样才是同一种；空 NBT 算没有 */
    @Test
    void payItemIdentity() {
        PayItem a = new PayItem("minecraft:wool", 14, null, null);
        assertEquals(a, new PayItem("minecraft:wool", 14, "{}", null));
        assertNotEquals(a, new PayItem("minecraft:wool", 13, null, null));
        assertNotEquals(a, new PayItem("minecraft:wool", 14, "{\"a\":1b}", null));
        assertNotEquals(new PayItem("minecraft:wool", 14, null, "{\"x\":1}"), a);
        assertNull(new PayItem("minecraft:wool", 14, "{}", "{}").nbt);
        assertThrows(IllegalArgumentException.class, () -> new PayItem("Bad Id", 0, null, null));
        assertThrows(IllegalArgumentException.class, () -> new PayItem("minecraft:wool", -1, null, null));
        assertTrue(a.compareTo(new PayItem("minecraft:wool", 15, null, null)) < 0);
    }
}
