package moe.qingu.youran.server.net.nav;

import java.util.Locale;

/** 180.yr 的固定广告位（服主 11-15）：顶部横幅、侧栏方块、导航下一排文字链、最底下一排小按钮 */
enum AdSlot {
    BANNER("banner", "顶部横幅", 468, 60),
    SQUARE("square", "侧栏方块", 125, 125),
    TEXT("text", "文字链", 0, 0),
    BUTTON("button", "小按钮", 88, 31);

    final String code;
    final String label;
    /** 图片广告的尺寸；文字链为 0 */
    final int width;
    final int height;

    AdSlot(String code, String label, int width, int height) {
        this.code = code;
        this.label = label;
        this.width = width;
        this.height = height;
    }

    boolean isImage() {
        return width > 0;
    }

    static AdSlot of(String code) {
        if (code == null) {
            return null;
        }
        String c = code.trim().toLowerCase(Locale.ROOT);
        for (AdSlot s : values()) {
            if (s.code.equals(c)) {
                return s;
            }
        }
        return null;
    }
}
