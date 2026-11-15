package moe.qingu.youran.api.network;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.regex.Pattern;

/**
 * 系统确认框里的一段文字：原样的文字，或者一个翻译键（由操作者的客户端按他的语言翻译，如 YouranMod 自己的固定文字、物品名）。
 * 带翻译键时也要给一份 {@link #fallback}（服务端写好的整句，客户端没有这个键时显示它；日志里也用它）。
 * 参数也是 {@code ConfirmText}（例如“{物品名} ×3”里的物品名本身是翻译键），最多 {@value #MAX_ARGS} 个、套 {@value #MAX_DEPTH} 层。
 * <p>
 * 网站传来的文字（标题、条目）一律用 {@link #literal}，原样显示；翻译键只由服务端的代码写。不可变。
 */
public final class ConfirmText {
    public static final int MAX_ARGS = 4;
    public static final int MAX_DEPTH = 2;
    public static final int MAX_KEY = 128;
    /** 翻译键的写法（原版与模组的键：字母、数字、{@code . _ - :}） */
    public static final Pattern KEY = Pattern.compile("[A-Za-z0-9._:-]{1," + MAX_KEY + "}");

    /** 翻译键；原样的文字为 null */
    public final String key;
    /** 原样的文字；带翻译键时是客户端没有这个键时显示的整句 */
    public final String fallback;
    public final List<ConfirmText> args;

    private ConfirmText(String key, String fallback, List<ConfirmText> args) {
        this.key = key;
        this.fallback = fallback;
        this.args = args;
    }

    /** 原样显示的文字 */
    public static ConfirmText literal(String text) {
        if (text == null) {
            throw new IllegalArgumentException("文字不能为 null");
        }
        return new ConfirmText(null, text, Collections.<ConfirmText>emptyList());
    }

    /**
     * 翻译键 {@code key}，客户端没有这个键时显示 {@code fallback}。{@code args} 是 {@code ConfirmText}（翻译）或别的（按 {@code toString} 原样）。
     */
    public static ConfirmText translate(String key, String fallback, Object... args) {
        if (key == null || !KEY.matcher(key).matches()) {
            throw new IllegalArgumentException("翻译键不对：" + key);
        }
        if (fallback == null) {
            throw new IllegalArgumentException("要给客户端没有这个键时显示的文字");
        }
        if (args != null && args.length > MAX_ARGS) {
            throw new IllegalArgumentException("参数超过 " + MAX_ARGS + " 个");
        }
        List<ConfirmText> list = new ArrayList<>();
        if (args != null) {
            for (Object a : args) {
                ConfirmText t = a instanceof ConfirmText ? (ConfirmText) a : literal(String.valueOf(a));
                if (t.depth() >= MAX_DEPTH) {
                    throw new IllegalArgumentException("参数套得太深");
                }
                list.add(t);
            }
        }
        return new ConfirmText(key, fallback, Collections.unmodifiableList(list));
    }

    /** 套了几层（原样的文字为 0） */
    public int depth() {
        int d = 0;
        for (ConfirmText a : args) {
            d = Math.max(d, a.depth());
        }
        return key == null ? 0 : d + 1;
    }

    public boolean isLiteral() {
        return key == null;
    }

    @Override
    public boolean equals(Object o) {
        if (!(o instanceof ConfirmText)) {
            return false;
        }
        ConfirmText t = (ConfirmText) o;
        return (key == null ? t.key == null : key.equals(t.key)) && fallback.equals(t.fallback) && args.equals(t.args);
    }

    @Override
    public int hashCode() {
        return (key == null ? 0 : key.hashCode() * 31) + fallback.hashCode() * 7 + args.hashCode();
    }

    @Override
    public String toString() {
        return fallback;
    }
}
