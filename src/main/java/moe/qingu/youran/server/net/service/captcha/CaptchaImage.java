package moe.qingu.youran.server.net.service.captcha;

import java.awt.BasicStroke;
import java.awt.Color;
import java.awt.GradientPaint;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.geom.AffineTransform;
import java.awt.geom.CubicCurve2D;
import java.awt.geom.Path2D;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.nio.ByteBuffer;
import java.util.HashMap;
import java.util.Map;
import java.util.Random;
import java.util.zip.CRC32;
import java.util.zip.Deflater;

/**
 * 画验证码图片（服务端，Java2D，无头模式下可用）。不用系统字体：字是自己用折线、曲线描的（服务器上可能一个字体都没装），
 * 再加上每个字随机的大小、旋转、错切、笔画抖动、粗细，几条穿过字的干扰线，整张图的正弦扭曲，噪点。
 * 目标是“一般的 OCR 不能直接认出来”（服主 11-15：游戏内计算机的性能本来就不够破解）。
 * <p>
 * 同一个答案、同一个种子画出来一样。PNG 自己编码（不经 ImageIO，不在服务器上建缓存文件）。纯计算，可以在任意线程上调用。
 */
public final class CaptchaImage {
    public static final int WIDTH = 160;
    public static final int HEIGHT = 60;

    /** 字形：每个字若干笔，每笔一串点（宽 0–4、高 0–6 的格子，y 向下）；{@code S} 开头的笔画成平滑曲线 */
    private static final Map<Character, String[]> GLYPHS = new HashMap<>();

    static {
        glyph('A', "0,6 2,0 4,6", "0.9,3.9 3.1,3.9");
        glyph('C', "S4,1 3,0 1,0 0,1.5 0,4.5 1,6 3,6 4,5");
        glyph('D', "0,0 0,6", "S0,0 2.5,0 4,1.5 4,4.5 2.5,6 0,6");
        glyph('E', "4,0 0,0 0,6 4,6", "0,3 3,3");
        glyph('F', "4,0 0,0 0,6", "0,3 3,3");
        glyph('H', "0,0 0,6", "4,0 4,6", "0,3 4,3");
        glyph('J', "1,0 4,0", "S3,0 3,4.5 2,6 0.8,6 0,5");
        glyph('K', "0,0 0,6", "4,0 0,3.6", "1.4,2.6 4,6");
        glyph('M', "0,6 0,0 2,4 4,0 4,6");
        glyph('N', "0,6 0,0 4,6 4,0");
        glyph('P', "0,6 0,0", "S0,0 3,0 4,1.5 3,3 0,3");
        glyph('R', "0,6 0,0", "S0,0 3,0 4,1.5 3,3 0,3", "2,3 4,6");
        glyph('T', "0,0 4,0", "2,0 2,6");
        glyph('W', "0,0 1,6 2,2.4 3,6 4,0");
        glyph('X', "0,0 4,6", "4,0 0,6");
        glyph('Y', "0,0 2,3 4,0", "2,3 2,6");
        glyph('3', "S0,0.8 1,0 3,0 4,1 4,2 3,3 1.5,3", "S3,3 4,4 4,5 3,6 1,6 0,5.2");
        glyph('4', "3,6 3,0 0,4 4,4");
        glyph('6', "S3.6,0 1.5,0.6 0,3 0,5 1,6 3,6 4,5 4,4 3,3 1,3 0,4");
        glyph('7', "0,0 4,0 1.4,6");
        glyph('9', "S4,2 3,3 1,3 0,2 0,1 1,0 3,0 4,1 4,3 3.4,5 2,6 0.4,6");
    }

    private static void glyph(char c, String... strokes) {
        GLYPHS.put(c, strokes);
    }

    private CaptchaImage() {
    }

    /** 字表里的每个字都有字形（测试用） */
    static boolean hasGlyph(char c) {
        return GLYPHS.containsKey(c);
    }

    /** 画成 PNG */
    public static byte[] png(String answer, long seed) {
        return encodePng(render(answer, seed));
    }

    public static BufferedImage render(String answer, long seed) {
        Random r = new Random(seed);
        BufferedImage img = new BufferedImage(WIDTH, HEIGHT, BufferedImage.TYPE_INT_RGB);
        Graphics2D g = img.createGraphics();
        try {
            g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
            g.setRenderingHint(RenderingHints.KEY_STROKE_CONTROL, RenderingHints.VALUE_STROKE_PURE);
            // 背景：浅色渐变 + 几块淡色斑
            g.setPaint(new GradientPaint(0, 0, light(r), WIDTH, HEIGHT, light(r)));
            g.fillRect(0, 0, WIDTH, HEIGHT);
            for (int i = 0; i < 6; i++) {
                g.setColor(withAlpha(light(r), 120));
                int w = 20 + r.nextInt(50);
                int h = 10 + r.nextInt(30);
                g.fillOval(r.nextInt(WIDTH) - w / 2, r.nextInt(HEIGHT) - h / 2, w, h);
            }
            // 背景上的细干扰线
            for (int i = 0; i < 5; i++) {
                g.setColor(withAlpha(mid(r), 140));
                g.setStroke(new BasicStroke(1f));
                g.drawLine(r.nextInt(WIDTH), r.nextInt(HEIGHT), r.nextInt(WIDTH), r.nextInt(HEIGHT));
            }
            // 字
            int n = answer.length();
            double slot = (WIDTH - 20) / (double) n;
            for (int i = 0; i < n; i++) {
                String[] strokes = GLYPHS.get(answer.charAt(i));
                if (strokes == null) {
                    continue;
                }
                double height = 30 + r.nextDouble() * 8;
                double scale = height / 6.0;
                double cx = 10 + slot * (i + 0.5) + (r.nextDouble() - 0.5) * 6;
                double cy = HEIGHT / 2.0 + (r.nextDouble() - 0.5) * 10;
                AffineTransform t = new AffineTransform();
                t.translate(cx, cy);
                t.rotate((r.nextDouble() - 0.5) * 0.6);
                t.shear((r.nextDouble() - 0.5) * 0.4, (r.nextDouble() - 0.5) * 0.15);
                t.scale(scale * (0.7 + r.nextDouble() * 0.25), scale);
                t.translate(-2, -3);
                g.setColor(dark(r));
                g.setStroke(new BasicStroke((float) (2.6 + r.nextDouble() * 1.6), BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND));
                for (String s : strokes) {
                    g.draw(t.createTransformedShape(stroke(s, r)));
                }
            }
            // 穿过字的干扰曲线（颜色和字差不多）
            int curves = 2 + r.nextInt(2);
            for (int i = 0; i < curves; i++) {
                g.setColor(dark(r));
                g.setStroke(new BasicStroke((float) (1.4 + r.nextDouble() * 1.0), BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND));
                g.draw(new CubicCurve2D.Double(-5, 10 + r.nextDouble() * 40, 30 + r.nextDouble() * 40, r.nextDouble() * HEIGHT,
                        90 + r.nextDouble() * 40, r.nextDouble() * HEIGHT, WIDTH + 5, 10 + r.nextDouble() * 40));
            }
        } finally {
            g.dispose();
        }
        BufferedImage warped = warp(img, r);
        noise(warped, r);
        return warped;
    }

    /** 一笔：点按格子坐标，加抖动；{@code S} 开头的用二次曲线连（过中点），其余是折线 */
    private static Path2D stroke(String s, Random r) {
        boolean smooth = s.startsWith("S");
        String[] pts = (smooth ? s.substring(1) : s).trim().split(" +");
        double[] xs = new double[pts.length];
        double[] ys = new double[pts.length];
        for (int i = 0; i < pts.length; i++) {
            String[] xy = pts[i].split(",");
            xs[i] = Double.parseDouble(xy[0]) + (r.nextDouble() - 0.5) * 0.3;
            ys[i] = Double.parseDouble(xy[1]) + (r.nextDouble() - 0.5) * 0.3;
        }
        Path2D.Double p = new Path2D.Double();
        p.moveTo(xs[0], ys[0]);
        if (!smooth || pts.length < 3) {
            for (int i = 1; i < pts.length; i++) {
                p.lineTo(xs[i], ys[i]);
            }
            return p;
        }
        for (int i = 1; i < pts.length - 1; i++) {
            p.quadTo(xs[i], ys[i], (xs[i] + xs[i + 1]) / 2, (ys[i] + ys[i + 1]) / 2);
        }
        p.lineTo(xs[pts.length - 1], ys[pts.length - 1]);
        return p;
    }

    /** 整张图按两组正弦波位移（双线性取样） */
    private static BufferedImage warp(BufferedImage src, Random r) {
        double ax = 1.5 + r.nextDouble() * 1.5;
        double ay = 1.5 + r.nextDouble() * 1.5;
        double px = 30 + r.nextDouble() * 20;
        double py = 60 + r.nextDouble() * 50;
        double fx = r.nextDouble() * Math.PI * 2;
        double fy = r.nextDouble() * Math.PI * 2;
        BufferedImage out = new BufferedImage(WIDTH, HEIGHT, BufferedImage.TYPE_INT_RGB);
        for (int y = 0; y < HEIGHT; y++) {
            for (int x = 0; x < WIDTH; x++) {
                double sx = x + ax * Math.sin(2 * Math.PI * y / px + fx);
                double sy = y + ay * Math.sin(2 * Math.PI * x / py + fy);
                out.setRGB(x, y, sample(src, sx, sy));
            }
        }
        return out;
    }

    private static int sample(BufferedImage img, double x, double y) {
        x = Math.max(0, Math.min(WIDTH - 1.001, x));
        y = Math.max(0, Math.min(HEIGHT - 1.001, y));
        int x0 = (int) x;
        int y0 = (int) y;
        double dx = x - x0;
        double dy = y - y0;
        int c00 = img.getRGB(x0, y0);
        int c10 = img.getRGB(x0 + 1, y0);
        int c01 = img.getRGB(x0, y0 + 1);
        int c11 = img.getRGB(x0 + 1, y0 + 1);
        int out = 0;
        for (int shift = 0; shift <= 16; shift += 8) {
            double v = ((c00 >> shift) & 0xFF) * (1 - dx) * (1 - dy) + ((c10 >> shift) & 0xFF) * dx * (1 - dy)
                    + ((c01 >> shift) & 0xFF) * (1 - dx) * dy + ((c11 >> shift) & 0xFF) * dx * dy;
            out |= (Math.min(255, (int) Math.round(v)) & 0xFF) << shift;
        }
        return out;
    }

    /** 噪点：深浅各一些 */
    private static void noise(BufferedImage img, Random r) {
        int dots = 350 + r.nextInt(150);
        for (int i = 0; i < dots; i++) {
            int x = r.nextInt(WIDTH);
            int y = r.nextInt(HEIGHT);
            Color c = r.nextBoolean() ? dark(r) : light(r);
            img.setRGB(x, y, c.getRGB() & 0xFFFFFF);
        }
    }

    private static Color light(Random r) {
        return new Color(200 + r.nextInt(56), 200 + r.nextInt(56), 200 + r.nextInt(56));
    }

    private static Color mid(Random r) {
        return new Color(120 + r.nextInt(80), 120 + r.nextInt(80), 120 + r.nextInt(80));
    }

    private static Color dark(Random r) {
        float h = r.nextFloat();
        return Color.getHSBColor(h, 0.5f + r.nextFloat() * 0.4f, 0.25f + r.nextFloat() * 0.3f);
    }

    private static Color withAlpha(Color c, int a) {
        return new Color(c.getRed(), c.getGreen(), c.getBlue(), a);
    }

    // ---- PNG ----

    /** RGB 8 位 PNG（每行用 Sub 滤波，zlib 压缩） */
    static byte[] encodePng(BufferedImage img) {
        int w = img.getWidth();
        int h = img.getHeight();
        byte[] raw = new byte[h * (1 + w * 3)];
        int p = 0;
        for (int y = 0; y < h; y++) {
            raw[p++] = 1;
            int pr = 0;
            int pg = 0;
            int pb = 0;
            for (int x = 0; x < w; x++) {
                int c = img.getRGB(x, y);
                int cr = (c >> 16) & 0xFF;
                int cg = (c >> 8) & 0xFF;
                int cb = c & 0xFF;
                raw[p++] = (byte) (cr - pr);
                raw[p++] = (byte) (cg - pg);
                raw[p++] = (byte) (cb - pb);
                pr = cr;
                pg = cg;
                pb = cb;
            }
        }
        Deflater d = new Deflater(Deflater.BEST_COMPRESSION);
        byte[] compressed;
        try {
            d.setInput(raw);
            d.finish();
            ByteArrayOutputStream z = new ByteArrayOutputStream();
            byte[] buf = new byte[8192];
            while (!d.finished()) {
                int n = d.deflate(buf);
                z.write(buf, 0, n);
            }
            compressed = z.toByteArray();
        } finally {
            d.end();
        }
        ByteArrayOutputStream out = new ByteArrayOutputStream(compressed.length + 64);
        out.write(new byte[]{(byte) 0x89, 'P', 'N', 'G', '\r', '\n', 0x1A, '\n'}, 0, 8);
        ByteBuffer ihdr = ByteBuffer.allocate(13);
        ihdr.putInt(w).putInt(h).put((byte) 8).put((byte) 2).put((byte) 0).put((byte) 0).put((byte) 0);
        chunk(out, "IHDR", ihdr.array());
        chunk(out, "IDAT", compressed);
        chunk(out, "IEND", new byte[0]);
        return out.toByteArray();
    }

    private static void chunk(ByteArrayOutputStream out, String type, byte[] data) {
        byte[] t = type.getBytes(java.nio.charset.StandardCharsets.US_ASCII);
        ByteBuffer len = ByteBuffer.allocate(4).putInt(data.length);
        out.write(len.array(), 0, 4);
        out.write(t, 0, 4);
        out.write(data, 0, data.length);
        CRC32 crc = new CRC32();
        crc.update(t);
        crc.update(data);
        ByteBuffer c = ByteBuffer.allocate(4).putInt((int) crc.getValue());
        out.write(c.array(), 0, 4);
    }
}
