package moe.qingu.youran.item;

import net.minecraft.init.Bootstrap;
import net.minecraft.item.ItemStack;
import net.minecraft.nbt.NBTTagCompound;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.util.HashSet;
import java.util.Random;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class FloppyNbtTest {
    @BeforeAll
    static void boot() {
        Bootstrap.register();
    }

    /** 软盘堆叠数 1：没分配盘号的也叠不起来 */
    @Test
    void stackSizeOne() {
        ItemFloppyDisk item = new ItemFloppyDisk();
        ItemStack a = new ItemStack(item);
        assertEquals(1, item.getItemStackLimit(a));
        assertEquals(1, a.getMaxStackSize());
    }

    /** NBT 只存盘号、标签、镜像；没有的读出 0 / 空 */
    @Test
    void tags() {
        assertEquals(0, FloppyNbt.diskId(null));
        assertEquals("", FloppyNbt.label(null));
        NBTTagCompound t = FloppyNbt.withId(null, 0x1234_5678_9ABCL);
        assertEquals(0x1234_5678_9ABCL, FloppyNbt.diskId(t));
        assertEquals("", FloppyNbt.image(t));
        assertEquals(1, t.getKeySet().size());
        assertEquals(1, t.getCompoundTag("Floppy").getKeySet().size(), "只有盘号");
        NBTTagCompound tool = FloppyNbt.toolDisk(77, ToolDisk.C.image, ToolDisk.C.label);
        assertEquals(77, FloppyNbt.diskId(tool));
        assertEquals("c", FloppyNbt.image(tool));
        assertEquals("SMALL-C", FloppyNbt.label(tool));
        // 已有别的 NBT（铁砧改名）时只加不改
        NBTTagCompound named = new NBTTagCompound();
        named.setTag("display", new NBTTagCompound());
        NBTTagCompound withId = FloppyNbt.withId(named, 5);
        assertTrue(withId.hasKey("display"));
        assertEquals(5, FloppyNbt.diskId(withId));
        NBTTagCompound bad = FloppyNbt.withId(null, -3);
        assertEquals(0, FloppyNbt.diskId(bad), "负数当没有");
    }

    /** 新盘号：正数、不重复（跳过发过的），随机分布（高位也用上） */
    @Test
    void newIdsUnique() {
        Set<Long> issued = new HashSet<>();
        Random r = new Random(42);
        boolean high = false;
        for (int i = 0; i < 100000; i++) {
            long id = FloppyNbt.newId(r, issued::contains);
            assertTrue(id > 0);
            assertTrue(issued.add(id), "两张盘拿到了同一个号");
            high |= id > (1L << 62);
        }
        assertTrue(high);
        // 发过的一定跳过：随机数故意重复
        Random same = new Random() {
            int n;

            @Override
            public long nextLong() {
                return n++ < 3 ? 99 : 100;
            }
        };
        Set<Long> taken = new HashSet<>();
        taken.add(99L);
        assertEquals(100, FloppyNbt.newId(same, taken::contains));
        assertFalse(taken.contains(100L));
    }
}
