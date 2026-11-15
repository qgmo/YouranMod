package moe.qingu.youran.server.net.service.captcha;

import org.junit.jupiter.api.Test;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.util.Arrays;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** 验证码图片：PNG 能解开、尺寸、同一种子画出来一样、字确实画上去了 */
class CaptchaImageTest {
    @Test
    void pngDecodesAndIsDeterministic() throws Exception {
        byte[] a = CaptchaImage.png("AC4X9", 42);
        byte[] b = CaptchaImage.png("AC4X9", 42);
        byte[] c = CaptchaImage.png("AC4X9", 43);
        assertTrue(Arrays.equals(a, b), "同一种子不一样");
        assertFalse(Arrays.equals(a, c));
        assertTrue(a.length < 32 * 1024, "图太大：" + a.length);
        BufferedImage img = ImageIO.read(new ByteArrayInputStream(a));
        assertEquals(CaptchaImage.WIDTH, img.getWidth());
        assertEquals(CaptchaImage.HEIGHT, img.getHeight());
        // 和直接画出来的像素一样（自己写的 PNG 编码没错）
        BufferedImage raw = CaptchaImage.render("AC4X9", 42);
        for (int y = 0; y < img.getHeight(); y++) {
            for (int x = 0; x < img.getWidth(); x++) {
                assertEquals(raw.getRGB(x, y) & 0xFFFFFF, img.getRGB(x, y) & 0xFFFFFF);
            }
        }
    }

    @Test
    void glyphsLeaveDarkStrokes() {
        // 字在中间一带：深色像素明显多于只有背景时
        int withText = dark(CaptchaImage.render("MWHNK", 7));
        int noText = dark(CaptchaImage.render("", 7));
        assertTrue(withText > noText + 600, withText + " vs " + noText);
    }

    private static int dark(BufferedImage img) {
        int n = 0;
        for (int y = 10; y < 50; y++) {
            for (int x = 10; x < 150; x++) {
                int c = img.getRGB(x, y);
                int l = ((c >> 16) & 0xFF) + ((c >> 8) & 0xFF) + (c & 0xFF);
                if (l < 360) {
                    n++;
                }
            }
        }
        return n;
    }
}
