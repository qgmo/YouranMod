package moe.qingu.youran.api.image;

import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.zip.CRC32;
import java.util.zip.DataFormatException;
import java.util.zip.Deflater;
import java.util.zip.Inflater;

/**
 * 16 色像素画（Minecraft 16 种染料色 + 透明）与 PNG 的互转，纯 Java（不用 AWT / ImageIO），两端都用：
 * 家具电脑浏览器的画板表单控件提交时用 {@link #encode} 写 PNG，网站（如 {@code platform.180.yr} 的广告图）收到后用 {@link #decode}
 * 检查、读出像素，再用 {@link #encode} 重新写一份存（去掉多余的块，大小也稳定）。
 * <p>
 * 像素值：{@code 0} 是透明，{@code 1}–{@code 16} 是染料色（{@link #DYE_RGB} 的下标 + 1，顺序同染料的数据值：白 0、橙 1 …… 黑 15）。
 * <p>
 * 写出的 PNG：8 位调色板（颜色类型 3），调色板 17 项（0 号透明、1–16 号染料色），{@code tRNS} 只标 0 号透明，不隔行，每行过滤方式 0。
 * 读的时候收调色板（1/2/4/8 位）、RGB 8 位、RGBA 8 位三种（不收隔行、16 位、灰度）：每个像素要么完全透明（alpha 0），
 * 要么不透明且正好是 16 种染料色之一，别的颜色整张不收。
 */
public final class DyePng {
    /** 16 种染料色（Minecraft 1.12 的 {@code EnumDyeColor#getColorValue}），按数据值排 */
    public static final int[] DYE_RGB = {
            0xF9FFFE, // 白
            0xF9801D, // 橙
            0xC74EBD, // 品红
            0x3AB3DA, // 淡蓝
            0xFED83D, // 黄
            0x80C71F, // 黄绿
            0xF38BAA, // 粉红
            0x474F52, // 灰
            0x9D9D97, // 淡灰
            0x169C9C, // 青
            0x8932B8, // 紫
            0x3C44AA, // 蓝
            0x835432, // 棕
            0x5E7C16, // 绿
            0xB02E26, // 红
            0x1D1D21, // 黑
    };
    /** 透明的像素值 */
    public static final byte TRANSPARENT = 0;
    /** 宽、高的上限 */
    public static final int MAX_SIDE = 512;
    /** 宽 × 高的上限（256 × 256） */
    public static final int MAX_PIXELS = 65536;
    /** 读的 PNG 最多多少字节 */
    public static final int MAX_PNG_BYTES = 64 * 1024;
    /** 表单里的写法：{@code data:image/png;base64,} 后面跟 PNG 的 base64 */
    public static final String DATA_URL_PREFIX = "data:image/png;base64,";

    private static final byte[] SIGNATURE = {(byte) 0x89, 'P', 'N', 'G', '\r', '\n', 0x1A, '\n'};

    private DyePng() {
    }

    /** PNG 不合格 */
    public static final class BadImage extends Exception {
        public BadImage(String message) {
            super(message);
        }
    }

    /** 读出来的图：宽、高、每个像素的值（0 透明，1–16 染料色），按行从左上角排 */
    public static final class Image {
        public final int width;
        public final int height;
        public final byte[] pixels;

        public Image(int width, int height, byte[] pixels) {
            this.width = width;
            this.height = height;
            this.pixels = pixels;
        }

        /** 有没有不透明的像素 */
        public boolean isBlank() {
            for (byte b : pixels) {
                if (b != TRANSPARENT) {
                    return false;
                }
            }
            return true;
        }
    }

    /** 宽、高合不合规定（1–{@value #MAX_SIDE}，乘积不超过 {@value #MAX_PIXELS}） */
    public static boolean validSize(int width, int height) {
        return width >= 1 && height >= 1 && width <= MAX_SIDE && height <= MAX_SIDE && (long) width * height <= MAX_PIXELS;
    }

    /** 0xRRGGBB → 像素值（1–16）；不是染料色返回 0 */
    public static int dyeOf(int rgb) {
        int c = rgb & 0xFFFFFF;
        for (int i = 0; i < DYE_RGB.length; i++) {
            if (DYE_RGB[i] == c) {
                return i + 1;
            }
        }
        return 0;
    }

    /** 像素值 → 0xAARRGGBB（透明为 0） */
    public static int argb(int pixel) {
        return pixel <= 0 || pixel > DYE_RGB.length ? 0 : 0xFF000000 | DYE_RGB[pixel - 1];
    }

    // ---------------------------------------------------------------- 写

    /** 写成 PNG；{@code pixels} 长度要等于宽 × 高，值 0–16（别的值当透明） */
    public static byte[] encode(int width, int height, byte[] pixels) {
        if (!validSize(width, height) || pixels.length != width * height) {
            throw new IllegalArgumentException("尺寸不对：" + width + "×" + height + "，像素 " + pixels.length);
        }
        ByteArrayOutputStream out = new ByteArrayOutputStream(256 + pixels.length / 4);
        out.write(SIGNATURE, 0, SIGNATURE.length);
        byte[] ihdr = new byte[13];
        putInt(ihdr, 0, width);
        putInt(ihdr, 4, height);
        ihdr[8] = 8; // 位深
        ihdr[9] = 3; // 调色板
        chunk(out, "IHDR", ihdr);
        byte[] plte = new byte[3 * (DYE_RGB.length + 1)];
        for (int i = 0; i < DYE_RGB.length; i++) {
            plte[3 * (i + 1)] = (byte) (DYE_RGB[i] >> 16);
            plte[3 * (i + 1) + 1] = (byte) (DYE_RGB[i] >> 8);
            plte[3 * (i + 1) + 2] = (byte) DYE_RGB[i];
        }
        chunk(out, "PLTE", plte);
        chunk(out, "tRNS", new byte[]{0});
        byte[] raw = new byte[height * (width + 1)];
        for (int y = 0; y < height; y++) {
            int o = y * (width + 1);
            raw[o] = 0;
            for (int x = 0; x < width; x++) {
                int v = pixels[y * width + x];
                raw[o + 1 + x] = (byte) (v < 0 || v > DYE_RGB.length ? 0 : v);
            }
        }
        Deflater d = new Deflater(Deflater.BEST_COMPRESSION);
        try {
            d.setInput(raw);
            d.finish();
            ByteArrayOutputStream z = new ByteArrayOutputStream();
            byte[] buf = new byte[8192];
            while (!d.finished()) {
                int n = d.deflate(buf);
                z.write(buf, 0, n);
            }
            chunk(out, "IDAT", z.toByteArray());
        } finally {
            d.end();
        }
        chunk(out, "IEND", new byte[0]);
        return out.toByteArray();
    }

    /** PNG → {@code data:image/png;base64,…} */
    public static String toDataUrl(byte[] png) {
        return DATA_URL_PREFIX + Base64.getEncoder().encodeToString(png);
    }

    /** {@code data:image/png;base64,…} → PNG 字节；写法不对、太大时返回 null */
    public static byte[] fromDataUrl(String value) {
        if (value == null) {
            return null;
        }
        String v = value.trim();
        if (!v.regionMatches(true, 0, DATA_URL_PREFIX, 0, DATA_URL_PREFIX.length())) {
            return null;
        }
        String b64 = v.substring(DATA_URL_PREFIX.length()).replace("\r", "").replace("\n", "");
        if (b64.length() > (MAX_PNG_BYTES / 3 + 1) * 4) {
            return null;
        }
        try {
            return Base64.getDecoder().decode(b64.getBytes(StandardCharsets.US_ASCII));
        } catch (IllegalArgumentException e) {
            return null;
        }
    }

    private static void chunk(ByteArrayOutputStream out, String type, byte[] data) {
        byte[] len = new byte[4];
        putInt(len, 0, data.length);
        out.write(len, 0, 4);
        byte[] t = type.getBytes(StandardCharsets.US_ASCII);
        out.write(t, 0, 4);
        out.write(data, 0, data.length);
        CRC32 crc = new CRC32();
        crc.update(t);
        crc.update(data);
        byte[] c = new byte[4];
        putInt(c, 0, (int) crc.getValue());
        out.write(c, 0, 4);
    }

    private static void putInt(byte[] b, int o, int v) {
        b[o] = (byte) (v >>> 24);
        b[o + 1] = (byte) (v >>> 16);
        b[o + 2] = (byte) (v >>> 8);
        b[o + 3] = (byte) v;
    }

    private static int getInt(byte[] b, int o) {
        return (b[o] & 0xFF) << 24 | (b[o + 1] & 0xFF) << 16 | (b[o + 2] & 0xFF) << 8 | (b[o + 3] & 0xFF);
    }

    // ---------------------------------------------------------------- 读

    /**
     * 读一张 PNG 并检查：尺寸要正好是 {@code width} × {@code height}（任一为 0 时只要合 {@link #validSize}），每个像素是透明或 16 种染料色之一。
     * 先看文件头的尺寸再解压，解压出来的字节数不会超过尺寸算出来的那么多（防压缩炸弹）。
     */
    public static Image decode(byte[] png, int width, int height) throws BadImage {
        if (png == null || png.length < SIGNATURE.length + 12 || png.length > MAX_PNG_BYTES) {
            throw new BadImage(png != null && png.length > MAX_PNG_BYTES ? "图片太大（最多 " + MAX_PNG_BYTES / 1024 + " KB）" : "不是 PNG 图片");
        }
        for (int i = 0; i < SIGNATURE.length; i++) {
            if (png[i] != SIGNATURE[i]) {
                throw new BadImage("不是 PNG 图片");
            }
        }
        int pos = SIGNATURE.length;
        int w = -1, h = -1, depth = 0, type = -1;
        int[] palette = null;
        int[] alpha = null;
        ByteArrayOutputStream idat = new ByteArrayOutputStream();
        boolean ended = false;
        boolean first = true;
        while (pos + 12 <= png.length) {
            int len = getInt(png, pos);
            if (len < 0 || pos + 12L + len > png.length) {
                throw new BadImage("PNG 文件不完整");
            }
            String t = new String(png, pos + 4, 4, StandardCharsets.US_ASCII);
            CRC32 crc = new CRC32();
            crc.update(png, pos + 4, 4 + len);
            if ((int) crc.getValue() != getInt(png, pos + 8 + len)) {
                throw new BadImage("PNG 文件损坏（校验和不对）");
            }
            int d = pos + 8;
            if (first && !t.equals("IHDR")) {
                throw new BadImage("PNG 文件头不对");
            }
            first = false;
            switch (t) {
                case "IHDR":
                    if (len != 13 || w >= 0) {
                        throw new BadImage("PNG 文件头不对");
                    }
                    w = getInt(png, d);
                    h = getInt(png, d + 4);
                    depth = png[d + 8] & 0xFF;
                    type = png[d + 9] & 0xFF;
                    if (png[d + 10] != 0 || png[d + 11] != 0) {
                        throw new BadImage("PNG 的压缩或过滤方式不认识");
                    }
                    if (png[d + 12] != 0) {
                        throw new BadImage("不支持隔行扫描的 PNG");
                    }
                    if (!validSize(w, h)) {
                        throw new BadImage("图片尺寸 " + w + "×" + h + " 超出范围");
                    }
                    if ((width > 0 && w != width) || (height > 0 && h != height)) {
                        throw new BadImage("图片尺寸要是 " + width + "×" + height + "（这张是 " + w + "×" + h + "）");
                    }
                    boolean ok = (type == 3 && (depth == 1 || depth == 2 || depth == 4 || depth == 8)) || ((type == 2 || type == 6) && depth == 8);
                    if (!ok) {
                        throw new BadImage("只收调色板、RGB、RGBA（8 位）的 PNG");
                    }
                    break;
                case "PLTE":
                    if (len % 3 != 0 || len == 0 || len / 3 > 256 || palette != null) {
                        throw new BadImage("PNG 调色板不对");
                    }
                    palette = new int[len / 3];
                    for (int i = 0; i < palette.length; i++) {
                        palette[i] = (png[d + 3 * i] & 0xFF) << 16 | (png[d + 3 * i + 1] & 0xFF) << 8 | (png[d + 3 * i + 2] & 0xFF);
                    }
                    break;
                case "tRNS":
                    if (type == 3) {
                        alpha = new int[len];
                        for (int i = 0; i < len; i++) {
                            alpha[i] = png[d + i] & 0xFF;
                        }
                    } else {
                        // RGB 的 tRNS（一种颜色算透明）：不收，写起来容易出歧义
                        throw new BadImage("不收带 tRNS 的 RGB 图片");
                    }
                    break;
                case "IDAT":
                    idat.write(png, d, len);
                    break;
                case "IEND":
                    ended = true;
                    break;
                default:
                    // 关键块（首字母大写）不认识就不收，辅助块跳过
                    if (Character.isUpperCase(t.charAt(0))) {
                        throw new BadImage("PNG 里有不认识的块 " + t);
                    }
                    break;
            }
            pos += 12 + len;
            if (ended) {
                break;
            }
        }
        if (!ended || w < 0) {
            throw new BadImage("PNG 文件不完整");
        }
        if (type == 3 && palette == null) {
            throw new BadImage("PNG 缺调色板");
        }
        int channels = type == 6 ? 4 : type == 2 ? 3 : 1;
        int bitsPerPixel = channels * depth;
        int rowBytes = (w * bitsPerPixel + 7) / 8;
        int bpp = Math.max(1, bitsPerPixel / 8);
        byte[] raw = inflate(idat.toByteArray(), h * (rowBytes + 1));
        byte[] prev = new byte[rowBytes];
        byte[] cur = new byte[rowBytes];
        byte[] pixels = new byte[w * h];
        int[] map = null;
        if (type == 3) {
            map = new int[palette.length];
            for (int i = 0; i < palette.length; i++) {
                int a = alpha != null && i < alpha.length ? alpha[i] : 255;
                map[i] = a == 0 ? 0 : a == 255 ? dyeOf(palette[i]) : -1;
                if (map[i] == 0 && a != 0) {
                    map[i] = -1;
                }
            }
        }
        for (int y = 0; y < h; y++) {
            int o = y * (rowBytes + 1);
            int filter = raw[o] & 0xFF;
            System.arraycopy(raw, o + 1, cur, 0, rowBytes);
            unfilter(filter, cur, prev, bpp);
            for (int x = 0; x < w; x++) {
                int v;
                if (type == 3) {
                    int idx;
                    if (depth == 8) {
                        idx = cur[x] & 0xFF;
                    } else {
                        int bit = x * depth;
                        idx = ((cur[bit >> 3] & 0xFF) >> (8 - depth - (bit & 7))) & ((1 << depth) - 1);
                    }
                    if (idx >= map.length) {
                        throw new BadImage("PNG 的像素超出调色板");
                    }
                    v = map[idx];
                } else {
                    int i = x * channels;
                    int rgb = (cur[i] & 0xFF) << 16 | (cur[i + 1] & 0xFF) << 8 | (cur[i + 2] & 0xFF);
                    int a = type == 6 ? cur[i + 3] & 0xFF : 255;
                    if (a == 0) {
                        v = 0;
                    } else if (a == 255) {
                        v = dyeOf(rgb);
                        if (v == 0) {
                            v = -1;
                        }
                    } else {
                        v = -1;
                    }
                }
                if (v < 0) {
                    throw new BadImage("图片里有 16 种染料色以外的颜色（或半透明的像素），第 " + (y + 1) + " 行第 " + (x + 1) + " 个");
                }
                pixels[y * w + x] = (byte) v;
            }
            byte[] t = prev;
            prev = cur;
            cur = t;
        }
        return new Image(w, h, pixels);
    }

    private static byte[] inflate(byte[] data, int expected) throws BadImage {
        Inflater inf = new Inflater();
        try {
            inf.setInput(data);
            byte[] out = new byte[expected];
            int n = 0;
            while (n < expected) {
                int k = inf.inflate(out, n, expected - n);
                if (k == 0) {
                    if (inf.finished() || inf.needsInput()) {
                        break;
                    }
                    if (inf.needsDictionary()) {
                        throw new BadImage("PNG 数据损坏");
                    }
                }
                n += k;
            }
            if (n != expected) {
                throw new BadImage("PNG 数据不完整");
            }
            // 解出来比尺寸算的多：不收
            byte[] extra = new byte[1];
            if (!inf.finished() && inf.inflate(extra) > 0) {
                throw new BadImage("PNG 数据比尺寸多");
            }
            return out;
        } catch (DataFormatException e) {
            throw new BadImage("PNG 数据损坏");
        } finally {
            inf.end();
        }
    }

    private static void unfilter(int filter, byte[] cur, byte[] prev, int bpp) throws BadImage {
        switch (filter) {
            case 0:
                return;
            case 1:
                for (int i = bpp; i < cur.length; i++) {
                    cur[i] = (byte) (cur[i] + cur[i - bpp]);
                }
                return;
            case 2:
                for (int i = 0; i < cur.length; i++) {
                    cur[i] = (byte) (cur[i] + prev[i]);
                }
                return;
            case 3:
                for (int i = 0; i < cur.length; i++) {
                    int left = i >= bpp ? cur[i - bpp] & 0xFF : 0;
                    cur[i] = (byte) (cur[i] + ((left + (prev[i] & 0xFF)) >> 1));
                }
                return;
            case 4:
                for (int i = 0; i < cur.length; i++) {
                    int a = i >= bpp ? cur[i - bpp] & 0xFF : 0;
                    int b = prev[i] & 0xFF;
                    int c = i >= bpp ? prev[i - bpp] & 0xFF : 0;
                    int p = a + b - c;
                    int pa = Math.abs(p - a), pb = Math.abs(p - b), pc = Math.abs(p - c);
                    int pr = pa <= pb && pa <= pc ? a : pb <= pc ? b : c;
                    cur[i] = (byte) (cur[i] + pr);
                }
                return;
            default:
                throw new BadImage("PNG 的行过滤方式不认识");
        }
    }
}
