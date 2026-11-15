package moe.qingu.youran.api.network;

import java.util.Locale;

/**
 * 导航信息（服主 11-15）：网站在浏览器主页 {@code 180.yr} 上怎么显示——名字、分类、一句话，要不要进“名站导航”格子。
 * 登记域名时顺带登记（{@link YouranNetworkApi#registerDomainName(String, String, SiteInfo)}），或者给别处登记的域名单独登记
 * （{@link YouranNetworkApi#registerNavigation}）。没登记导航信息的网站不上导航（例如留给玩家自己发现的店）。不可变。
 */
public final class SiteInfo {
    /** 名字最多几个字 */
    public static final int MAX_TITLE = 16;
    /** 一句话最多几个字 */
    public static final int MAX_SUMMARY = 40;

    /** 导航上的分栏 */
    public enum Category {
        SHOPPING("shopping", "购物"),
        SERVICE("service", "服务"),
        TOOL("tool", "工具"),
        PLAYER("player", "玩家网站");

        public final String code;
        public final String label;

        Category(String code, String label) {
            this.code = code;
            this.label = label;
        }

        /** 按代号或中文名找；找不到为 null */
        public static Category of(String s) {
            if (s == null) {
                return null;
            }
            String t = s.trim().toLowerCase(Locale.ROOT);
            for (Category c : values()) {
                if (c.code.equals(t) || c.label.equals(s.trim())) {
                    return c;
                }
            }
            return null;
        }
    }

    public final String title;
    public final Category category;
    public final String summary;
    /** 放进“名站导航”格子 */
    public final boolean featured;
    /** 链接到网站的哪个路径（以 {@code /} 开头），默认 {@code /} */
    public final String path;

    private SiteInfo(String title, Category category, String summary, boolean featured, String path) {
        this.title = title;
        this.category = category;
        this.summary = summary;
        this.featured = featured;
        this.path = path;
    }

    /**
     * @param title    名字（如 {@code MineBay}，最多 {@value #MAX_TITLE} 个字）
     * @param category 分栏
     * @param summary  一句话（最多 {@value #MAX_SUMMARY} 个字，可以为空）
     */
    public static SiteInfo of(String title, Category category, String summary) {
        return new SiteInfo(checkText(title, MAX_TITLE, false, "名字"), check(category), checkText(summary == null ? "" : summary, MAX_SUMMARY, true, "一句话"),
                false, "/");
    }

    /** 放进“名站导航”格子 */
    public SiteInfo featured() {
        return new SiteInfo(title, category, summary, true, path);
    }

    /** 链接到网站的这个路径（如 {@code /shop}） */
    public SiteInfo path(String p) {
        if (p == null || !p.startsWith("/") || p.startsWith("//") || p.length() > 100 || !p.matches("[A-Za-z0-9/_.~?=&%-]*")) {
            throw new IllegalArgumentException("路径要以 / 开头、只用网址里的普通字符：" + p);
        }
        return new SiteInfo(title, category, summary, featured, p);
    }

    private static Category check(Category c) {
        if (c == null) {
            throw new IllegalArgumentException("分类不能为 null");
        }
        return c;
    }

    static String checkText(String s, int max, boolean emptyOk, String what) {
        if (s == null) {
            throw new IllegalArgumentException(what + "不能为 null");
        }
        String t = s.trim();
        if (t.isEmpty() && !emptyOk) {
            throw new IllegalArgumentException(what + "不能是空的");
        }
        if (t.codePointCount(0, t.length()) > max) {
            throw new IllegalArgumentException(what + "太长（最多 " + max + " 个字）：" + s);
        }
        for (int i = 0; i < t.length(); i++) {
            char c = t.charAt(i);
            if (Character.isISOControl(c) || c == '§' || (c >= 0x202A && c <= 0x202E) || (c >= 0x2066 && c <= 0x2069)) {
                throw new IllegalArgumentException(what + "里有不允许的字符：" + s);
            }
        }
        return t;
    }

    @Override
    public String toString() {
        return title + " [" + category.code + (featured ? ", featured" : "") + "] " + summary;
    }
}
