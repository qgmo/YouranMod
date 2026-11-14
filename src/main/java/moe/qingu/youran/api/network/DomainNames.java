package moe.qingu.youran.api.network;

import java.net.IDN;
import java.util.EnumSet;
import java.util.Locale;
import java.util.Set;

/**
 * 国际化域名（服主 11-14）：DNS 报文、权威区、递归解析器、分配表、证书一律只用 ASCII 形式（A-label，{@code xn--…}）；
 * 只在给人看的地方（浏览器地址栏、连接信息）转回 Unicode。
 * <p>
 * 转换用 Java 8 自带的 {@link IDN}（IDNA 2003）：{@link #toAscii} 开 {@link IDN#USE_STD3_ASCII_RULES}（只许字母、数字、连字符），
 * 不开 {@link IDN#ALLOW_UNASSIGNED}（Unicode 里还没分配的码位一律不收）。转换失败抛出 {@link IllegalArgumentException}，不静默丢掉。
 * <p>
 * 显示（{@link #toDisplay}）照主流浏览器防仿冒的做法（Chromium 的 IDN 显示规则、UTS #39 的“高度限制”）：一个标签里只用一种文字
 * （常用字符、组合附加符号不算），或者是中日韩常见的组合（拉丁 + 汉字 + 平假名 + 片假名、拉丁 + 汉字 + 注音、拉丁 + 汉字 + 谚文）时
 * 显示 Unicode；混用别的文字（如拉丁字母混西里尔字母）、或者 A-label 本身转不回来时，显示 {@code xn--} 形式。
 */
public final class DomainNames {
    public static final int MAX_NAME = 253;
    public static final int MAX_LABEL = 63;

    private DomainNames() {
    }

    /**
     * 任意写法（Unicode、大小写混写、结尾带点）→ 小写的 A-label 形式（不带结尾点）。
     *
     * @throws IllegalArgumentException 名字不合法（空、空标签、太长、有不许的字符、Unicode 里没分配的码位等）
     */
    public static String toAscii(String name) {
        if (name == null) {
            throw new IllegalArgumentException("域名为空");
        }
        String n = name.trim();
        // 全角句号等由 IDN 当作点；这里先去掉结尾的一个点
        if (n.endsWith(".") || n.endsWith("。") || n.endsWith("．") || n.endsWith("｡")) {
            n = n.substring(0, n.length() - 1);
        }
        if (n.isEmpty()) {
            throw new IllegalArgumentException("域名为空");
        }
        String ascii;
        try {
            ascii = IDN.toASCII(n, IDN.USE_STD3_ASCII_RULES);
        } catch (IllegalArgumentException e) {
            throw new IllegalArgumentException("域名不合法（" + name + "）：" + e.getMessage(), e);
        }
        ascii = ascii.toLowerCase(Locale.ROOT);
        if (ascii.length() > MAX_NAME) {
            throw new IllegalArgumentException("域名超过 " + MAX_NAME + " 个字符：" + name);
        }
        for (String label : ascii.split("\\.", -1)) {
            if (label.isEmpty()) {
                throw new IllegalArgumentException("域名里有空标签：" + name);
            }
            if (label.length() > MAX_LABEL) {
                throw new IllegalArgumentException("域名的标签超过 " + MAX_LABEL + " 个字符：" + name);
            }
            if (label.startsWith("-") || label.endsWith("-")) {
                throw new IllegalArgumentException("域名的标签不能以连字符开头或结尾：" + name);
            }
            if (label.startsWith("xn--") && toUnicodeLabel(label) == null) {
                throw new IllegalArgumentException("域名里的 A-label 不对：" + label);
            }
        }
        return ascii;
    }

    /** 同 {@link #toAscii}，不合法时返回 null */
    public static String tryToAscii(String name) {
        try {
            return toAscii(name);
        } catch (IllegalArgumentException e) {
            return null;
        }
    }

    /** 是不是只有 ASCII 字符（不检查合法性） */
    public static boolean isAscii(String s) {
        for (int i = 0; i < s.length(); i++) {
            if (s.charAt(i) >= 0x80) {
                return false;
            }
        }
        return true;
    }

    /** A-label 形式 → Unicode 形式（不做防仿冒判断；转不回来的标签原样保留） */
    public static String toUnicode(String ascii) {
        StringBuilder sb = new StringBuilder();
        for (String label : ascii.split("\\.", -1)) {
            if (sb.length() > 0) {
                sb.append('.');
            }
            String u = label.startsWith("xn--") ? toUnicodeLabel(label) : label;
            sb.append(u == null ? label : u);
        }
        return sb.toString();
    }

    /**
     * 给人看的写法：每个 A-label 标签转回 Unicode，但混用文字系统、转不回来的标签保留 {@code xn--} 形式（防仿冒）。
     * {@code ascii} 应该是 {@link #toAscii} 的结果；不是合法域名时原样返回。
     */
    public static String toDisplay(String ascii) {
        if (ascii == null || ascii.isEmpty() || !isAscii(ascii)) {
            return ascii;
        }
        StringBuilder sb = new StringBuilder();
        for (String label : ascii.split("\\.", -1)) {
            if (sb.length() > 0) {
                sb.append('.');
            }
            String shown = label;
            if (label.regionMatches(true, 0, "xn--", 0, 4)) {
                String u = toUnicodeLabel(label);
                if (u != null && isSafeToDisplay(u)) {
                    shown = u;
                }
            }
            sb.append(shown);
        }
        return sb.toString();
    }

    /** A-label 的一个标签 → Unicode；转不回来、或转回来再转过去不一致时返回 null */
    static String toUnicodeLabel(String label) {
        String u = IDN.toUnicode(label, 0);
        if (u.equalsIgnoreCase(label)) {
            return null;
        }
        try {
            if (!IDN.toASCII(u, IDN.USE_STD3_ASCII_RULES).equalsIgnoreCase(label)) {
                return null;
            }
        } catch (IllegalArgumentException e) {
            return null;
        }
        return u;
    }

    /** 一个标签（Unicode 形式）能不能直接显示：只用一种文字，或者是中日韩常见的组合 */
    public static boolean isSafeToDisplay(String label) {
        Set<Character.UnicodeScript> scripts = EnumSet.noneOf(Character.UnicodeScript.class);
        for (int i = 0; i < label.length(); ) {
            int cp = label.codePointAt(i);
            i += Character.charCount(cp);
            Character.UnicodeScript s = Character.UnicodeScript.of(cp);
            if (s == Character.UnicodeScript.UNKNOWN) {
                return false;
            }
            if (s != Character.UnicodeScript.COMMON && s != Character.UnicodeScript.INHERITED) {
                scripts.add(s);
            }
        }
        if (scripts.size() <= 1) {
            return true;
        }
        Set<Character.UnicodeScript> rest = EnumSet.copyOf(scripts);
        rest.remove(Character.UnicodeScript.LATIN);
        rest.remove(Character.UnicodeScript.HAN);
        if (rest.isEmpty()) {
            return true;
        }
        // 日文：汉字 + 平假名 + 片假名（可以带拉丁）
        if (EnumSet.of(Character.UnicodeScript.HIRAGANA, Character.UnicodeScript.KATAKANA).containsAll(rest)) {
            return true;
        }
        // 中文注音、韩文谚文（可以带汉字、拉丁）
        return rest.size() == 1 && (rest.contains(Character.UnicodeScript.BOPOMOFO) || rest.contains(Character.UnicodeScript.HANGUL));
    }
}
