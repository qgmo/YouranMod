package moe.qingu.youran.api.pcb;

import moe.qingu.youran.litho.LaserAligner;
import moe.qingu.youran.logic.litho.ChipModel;
import moe.qingu.youran.logic.pcb.BoardType;
import net.minecraft.nbt.NBTTagCompound;
import org.junit.jupiter.api.Test;

import java.util.Arrays;
import java.util.Collections;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** 激光对准控制程序（服主 11-16）：自带的正版镜像、掩模 ROM 与 EPROM 的比对、个人电脑主板的 ROM 槽 */
class LaserAlignProgramTest {
    /** 镜像：8 KB 的 8088 程序，F000:FFF0 是上电跳转（EA = jmp far），全部字节相加为 0 */
    @Test
    void image() {
        byte[] img = LaserAlignProgram.image();
        assertEquals(8192, img.length);
        assertEquals((byte) 0xEA, img[0x1FF0]);
        assertEquals(0xF000, (img[0x1FF3] & 0xFF) | (img[0x1FF4] & 0xFF) << 8);
        int sum = 0;
        for (byte b : img) {
            sum += b & 0xFF;
        }
        assertEquals(0, sum & 0xFF);
        assertTrue(new String(img, 0, 40, java.nio.charset.StandardCharsets.US_ASCII).startsWith("YR-LAC"));
        assertEquals(RomContent.sha256(img), LaserAlignProgram.sha256());
        // 拿到的是副本，改了不影响正版
        img[0] = 0;
        assertTrue(LaserAlignProgram.genuine(RomContent.image(LaserAlignProgram.image())));
    }

    /** 比对照电脑开机检查 ROM 的规则：掩模 ROM 看是哪一种；EPROM 看实际烧进去的字节数与 SHA-256 */
    @Test
    void genuine() {
        byte[] img = LaserAlignProgram.image();
        assertTrue(LaserAlignProgram.genuine(RomContent.mask(LaserAlignProgram.MASK)));
        assertTrue(LaserAlignProgram.genuine(RomContent.image(img)));
        assertFalse(LaserAlignProgram.genuine(RomContent.mask(RomContent.BIOS)), "插普通 BIOS：控制程序不对");
        assertFalse(LaserAlignProgram.genuine(null), "空白 EPROM");
        byte[] changed = img.clone();
        changed[0x100] ^= 1;
        assertFalse(LaserAlignProgram.genuine(RomContent.image(changed)));
        assertFalse(LaserAlignProgram.genuine(RomContent.image(Arrays.copyOf(img, 4096))), "只烧了一半");
        assertFalse(LaserAlignProgram.genuine(RomContent.image(Arrays.copyOf(img, 8193))));
        assertEquals(LaserAlignProgram.MASK, ChipModel.ROM_LASER_ALIGN.id);
    }

    static BoardNbt.Part chip(ChipModel m, int maxFreq, boolean scrapped) {
        NBTTagCompound t = new NBTTagCompound();
        t.setString(ChipModel.TAG, m.id);
        t.setInteger("MaxFreq", maxFreq);
        t.setBoolean("Scrapped", scrapped);
        return new BoardNbt.Part(m, t);
    }

    /** 个人电脑主板的 ROM 槽认激光对准控制程序的掩模 ROM（普通电脑主板不认）；装上后内容是掩模 */
    @Test
    void pcBoardTakesAlignRom() {
        NBTTagCompound pc = BoardNbt.install(BoardNbt.empty(BoardType.PC), Collections.singletonList(chip(ChipModel.ROM_LASER_ALIGN, 4000, false)));
        assertNotNull(pc);
        assertTrue(LaserAlignProgram.genuine(BoardNbt.romContent(pc)));
        assertFalse(RomContent.genuine(BoardNbt.romContent(pc), RomContent.BIOS, -1, null), "这块主板装进电脑开不了机");
        assertNull(BoardNbt.install(BoardNbt.empty(BoardType.COMPUTER), Collections.singletonList(chip(ChipModel.ROM_LASER_ALIGN, 4000, false))));
        // BIOS 照样认
        assertNotNull(BoardNbt.install(BoardNbt.empty(BoardType.PC), Collections.singletonList(chip(ChipModel.ROM_BIOS, 4000, false))));
    }

    /** CPU 跑多快：芯片的最大频率，不超过 8088 的可调上限 8 MHz；报废的为 0 */
    @Test
    void cpuSpeed() {
        NBTTagCompound b = BoardNbt.install(BoardNbt.empty(BoardType.PC), Collections.singletonList(chip(ChipModel.I8088, 5500, false)));
        assertEquals(5500, LaserAligner.cpuKHz(b));
        b = BoardNbt.install(BoardNbt.empty(BoardType.PC), Collections.singletonList(chip(ChipModel.I8088, 9000, false)));
        assertEquals(8000, LaserAligner.cpuKHz(b));
        b = BoardNbt.install(BoardNbt.empty(BoardType.PC), Collections.singletonList(chip(ChipModel.I8088, 5500, true)));
        assertEquals(0, LaserAligner.cpuKHz(b));
        assertEquals(0, LaserAligner.cpuKHz(BoardNbt.empty(BoardType.PC)));
    }
}
