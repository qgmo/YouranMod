package moe.qingu.youran.api.image;

import org.junit.jupiter.api.Test;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.util.Random;
import java.util.zip.CRC32;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** 16 色像素画与 PNG：写、读、检查 */
class DyePngTest {
    static byte[] random(int w, int h, long seed) {
        Random r = new Random(seed);
        byte[] p = new byte[w * h];
        for (int i = 0; i < p.length; i++) {
            p[i] = (byte) r.nextInt(17);
        }
        return p;
    }

    @Test
    void roundTrip() throws Exception {
        for (int[] wh : new int[][]{{1, 1}, {125, 125}, {468, 60}, {88, 31}, {256, 256}, {512, 128}}) {
            byte[] p = random(wh[0], wh[1], wh[0] * 31L + wh[1]);
            byte[] png = DyePng.encode(wh[0], wh[1], p);
            DyePng.Image img = DyePng.decode(png, wh[0], wh[1]);
            assertArrayEquals(p, img.pixels, wh[0] + "x" + wh[1]);
            // 写出来的 PNG 别的解码器也认：颜色、透明对得上
            BufferedImage bi = ImageIO.read(new ByteArrayInputStream(png));
            assertEquals(wh[0], bi.getWidth());
            for (int i = 0; i < Math.min(p.length, 500); i++) {
                int argb = bi.getRGB(i % wh[0], i / wh[0]);
                if (p[i] == 0) {
                    assertEquals(0, argb >>> 24);
                } else {
                    assertEquals(DyePng.argb(p[i]), argb);
                }
            }
        }
    }

    @Test
    void readsOtherEncoders() throws Exception {
        // ImageIO 写的 RGBA（颜色类型 6）：同样认
        BufferedImage bi = new BufferedImage(20, 10, BufferedImage.TYPE_INT_ARGB);
        byte[] p = random(20, 10, 7);
        for (int i = 0; i < p.length; i++) {
            bi.setRGB(i % 20, i / 20, DyePng.argb(p[i]));
        }
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        ImageIO.write(bi, "png", out);
        assertArrayEquals(p, DyePng.decode(out.toByteArray(), 20, 10).pixels);
        // RGB（不透明）：没有透明像素
        BufferedImage rgb = new BufferedImage(5, 5, BufferedImage.TYPE_INT_RGB);
        for (int i = 0; i < 25; i++) {
            rgb.setRGB(i % 5, i / 5, DyePng.DYE_RGB[i % 16]);
        }
        out.reset();
        ImageIO.write(rgb, "png", out);
        DyePng.Image img = DyePng.decode(out.toByteArray(), 0, 0);
        assertEquals(5, img.width);
        assertEquals(1 + 3, img.pixels[3]);
    }

    @Test
    void rejectsWrongColorsAndSizes() throws Exception {
        BufferedImage bi = new BufferedImage(4, 4, BufferedImage.TYPE_INT_ARGB);
        bi.setRGB(1, 1, 0xFF123456);
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        ImageIO.write(bi, "png", out);
        DyePng.BadImage e = assertThrows(DyePng.BadImage.class, () -> DyePng.decode(out.toByteArray(), 4, 4));
        assertTrue(e.getMessage().contains("16 种"), e.getMessage());
        // 半透明
        bi.setRGB(1, 1, 0x80000000 | DyePng.DYE_RGB[3]);
        out.reset();
        ImageIO.write(bi, "png", out);
        assertThrows(DyePng.BadImage.class, () -> DyePng.decode(out.toByteArray(), 4, 4));
        // 尺寸不对
        byte[] png = DyePng.encode(10, 10, new byte[100]);
        assertThrows(DyePng.BadImage.class, () -> DyePng.decode(png, 125, 125));
        assertThrows(IllegalArgumentException.class, () -> DyePng.encode(513, 1, new byte[513]));
        assertThrows(IllegalArgumentException.class, () -> DyePng.encode(300, 300, new byte[90000]));
        assertFalse(DyePng.validSize(0, 5));
    }

    @Test
    void rejectsBrokenFiles() throws Exception {
        byte[] png = DyePng.encode(8, 8, random(8, 8, 3));
        assertThrows(DyePng.BadImage.class, () -> DyePng.decode("not png at all, sorry".getBytes(StandardCharsets.US_ASCII), 8, 8));
        // 改一个字节：校验和不对
        byte[] bad = png.clone();
        bad[bad.length - 20] ^= 1;
        assertThrows(DyePng.BadImage.class, () -> DyePng.decode(bad, 8, 8));
        // 截断
        byte[] cut = java.util.Arrays.copyOf(png, png.length - 12);
        assertThrows(DyePng.BadImage.class, () -> DyePng.decode(cut, 8, 8));
        // 文件头说很大（防压缩炸弹：先看尺寸）
        byte[] huge = png.clone();
        huge[16] = 0x10; // 宽 = 0x10000008
        fixCrc(huge, 8);
        assertThrows(DyePng.BadImage.class, () -> DyePng.decode(huge, 0, 0));
        // 太大的文件
        assertThrows(DyePng.BadImage.class, () -> DyePng.decode(new byte[DyePng.MAX_PNG_BYTES + 1], 0, 0));
    }

    /** 改了块的内容后重算 CRC（{@code at} 是块开头） */
    static void fixCrc(byte[] png, int at) {
        int len = (png[at] & 0xFF) << 24 | (png[at + 1] & 0xFF) << 16 | (png[at + 2] & 0xFF) << 8 | (png[at + 3] & 0xFF);
        CRC32 c = new CRC32();
        c.update(png, at + 4, 4 + len);
        int v = (int) c.getValue();
        int o = at + 8 + len;
        png[o] = (byte) (v >>> 24);
        png[o + 1] = (byte) (v >>> 16);
        png[o + 2] = (byte) (v >>> 8);
        png[o + 3] = (byte) v;
    }

    @Test
    void dataUrls() throws Exception {
        byte[] png = DyePng.encode(3, 2, new byte[]{1, 2, 3, 0, 16, 5});
        String url = DyePng.toDataUrl(png);
        assertTrue(url.startsWith("data:image/png;base64,"));
        assertArrayEquals(png, DyePng.fromDataUrl(url));
        assertArrayEquals(png, DyePng.fromDataUrl("DATA:image/PNG;base64," + url.substring(22)));
        assertNull(DyePng.fromDataUrl("data:image/gif;base64,AAAA"));
        assertNull(DyePng.fromDataUrl("data:image/png;base64,***"));
        assertNull(DyePng.fromDataUrl(null));
        assertTrue(DyePng.decode(DyePng.encode(2, 2, new byte[4]), 2, 2).isBlank());
    }

    @Test
    void dyeTable() {
        assertEquals(16, DyePng.DYE_RGB.length);
        assertEquals(1, DyePng.dyeOf(0xF9FFFE));
        assertEquals(16, DyePng.dyeOf(0x1D1D21));
        assertEquals(0, DyePng.dyeOf(0x000000));
        assertEquals(0, DyePng.argb(0));
    }
}
