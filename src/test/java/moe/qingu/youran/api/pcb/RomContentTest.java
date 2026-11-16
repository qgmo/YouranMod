package moe.qingu.youran.api.pcb;

import net.minecraft.nbt.NBTTagCompound;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** ROM 只比内容（服主 11-16）：掩模 ROM 看是哪一种，EPROM 看字节数与 SHA-256 */
class RomContentTest {
    @Test
    void image() {
        byte[] data = {1, 2, (byte) 0xFF};
        NBTTagCompound t = RomContent.image(data);
        assertEquals(3, t.getInteger(RomContent.TAG_LEN));
        assertEquals(0x102, t.getInteger(RomContent.TAG_SUM));
        assertEquals("1d6c0e5c4f1c8e4e7a5a0b1a5ab6d3f7a7c8fb4b0d4c7e55df3d0f9c4c1c1b4f".length(), t.getString(RomContent.TAG_SHA).length());
        assertEquals(RomContent.sha256(new byte[0]), "e3b0c44298fc1c149afbf4c8996fb92427ae41e4649b934ca495991b7852b855");
    }

    @Test
    void genuine() {
        byte[] bios = new byte[8192];
        bios[0] = 0x0F;
        String sha = RomContent.sha256(bios);
        assertTrue(RomContent.genuine(RomContent.image(bios), RomContent.BIOS, 8192, sha));
        assertTrue(RomContent.genuine(RomContent.mask(RomContent.BIOS), RomContent.BIOS, 8192, sha));
        // 掩模 ROM 不管服务报没报正版的镜像
        assertTrue(RomContent.genuine(RomContent.mask(RomContent.MONITOR), RomContent.MONITOR, -1, null));
        assertFalse(RomContent.genuine(RomContent.mask(RomContent.MONITOR), RomContent.BIOS, 8192, sha));
        // 多一个字节、改一个字节都不算
        byte[] longer = java.util.Arrays.copyOf(bios, 8193);
        assertFalse(RomContent.genuine(RomContent.image(longer), RomContent.BIOS, 8192, sha));
        bios[100] = 1;
        assertFalse(RomContent.genuine(RomContent.image(bios), RomContent.BIOS, 8192, sha));
        // 空白、不知道正版
        assertFalse(RomContent.genuine(null, RomContent.BIOS, 8192, sha));
        assertFalse(RomContent.genuine(new NBTTagCompound(), RomContent.BIOS, 8192, sha));
        assertFalse(RomContent.genuine(RomContent.image(bios), RomContent.BIOS, -1, null));
    }
}
