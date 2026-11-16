package moe.qingu.youran.api.pay;

import net.minecraft.nbt.CompressedStreamTools;
import net.minecraft.nbt.NBTTagCompound;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.File;
import java.io.FileOutputStream;
import java.io.OutputStream;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.Collections;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** 玩家存档里的标记：按 owner 分开、去重、去掉、上限、读文件核对 */
class PlayerMarksTest {
    @Test
    void addRemoveList() {
        NBTTagCompound d = new NBTTagCompound();
        String k = PlayerMarks.key("yrqing:give");
        PlayerMarks.add(d, k, "g1");
        PlayerMarks.add(d, k, "g2");
        PlayerMarks.add(d, k, "g1");
        assertEquals(Arrays.asList("g1", "g2"), PlayerMarks.list(d, k));
        assertEquals(Collections.emptyList(), PlayerMarks.list(d, PlayerMarks.key("other:x")), "别的 owner 看不到");
        PlayerMarks.remove(d, k, "g1");
        assertEquals(Collections.singletonList("g2"), PlayerMarks.list(d, k));
        PlayerMarks.remove(d, k, "g2");
        assertFalse(d.hasKey(k), "空了就整个去掉");
    }

    @Test
    void ownerAndLimit() {
        assertThrows(IllegalArgumentException.class, () -> PlayerMarks.key("没有冒号"));
        assertThrows(IllegalArgumentException.class, () -> PlayerMarks.key(null));
        NBTTagCompound d = new NBTTagCompound();
        String k = PlayerMarks.key("a:b");
        for (int i = 0; i < PlayerMarks.MAX_MARKS; i++) {
            PlayerMarks.add(d, k, "m" + i);
        }
        assertThrows(IllegalStateException.class, () -> PlayerMarks.add(d, k, "one-more"));
    }

    @Test
    void readsMarkFromPlayerFile(@TempDir Path dir) throws Exception {
        File f = dir.resolve("p.dat").toFile();
        String k = PlayerMarks.key("yrqing:give");
        assertFalse(PlayerMarks.onDisk(f, k, "g1"), "没有文件");
        NBTTagCompound root = new NBTTagCompound();
        NBTTagCompound forge = new NBTTagCompound();
        PlayerMarks.add(forge, k, "g1");
        root.setTag("ForgeData", forge);
        try (OutputStream out = new FileOutputStream(f)) {
            CompressedStreamTools.writeCompressed(root, out);
        }
        assertTrue(PlayerMarks.onDisk(f, k, "g1"));
        assertFalse(PlayerMarks.onDisk(f, k, "g2"));
        assertFalse(PlayerMarks.onDisk(f, PlayerMarks.key("other:x"), "g1"));
    }
}
