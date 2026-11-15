package moe.qingu.youran.client.confirm;

import moe.qingu.youran.api.network.ConfirmRequest;
import moe.qingu.youran.api.network.ConfirmText;

import java.util.function.Predicate;

/** 客户端把确认框里的 {@link ConfirmText} 变成要显示的文字（翻译键按玩家的语言，没有这个键时用服务端给的整句） */
public final class ConfirmTexts {
    /** 按翻译键格式化（客户端是 {@code I18n.format}） */
    @FunctionalInterface
    public interface Formatter {
        String format(String key, Object[] args);
    }

    private ConfirmTexts() {
    }

    /**
     * 翻译、清理：去掉控制字符、颜色代码、文字方向控制（翻译文件里的也不放过），最长 {@code max} 的两倍（翻成别的语言可能长一些）。
     */
    public static String render(ConfirmText t, int max, Predicate<String> hasKey, Formatter format) {
        return clean(raw(t, hasKey, format), max * 2);
    }

    private static String raw(ConfirmText t, Predicate<String> hasKey, Formatter format) {
        if (t.isLiteral() || !hasKey.test(t.key)) {
            return t.fallback;
        }
        Object[] args = new Object[t.args.size()];
        for (int i = 0; i < args.length; i++) {
            args[i] = clean(raw(t.args.get(i), hasKey, format), ConfirmRequest.MAX_ITEM * 2);
        }
        try {
            String s = format.format(t.key, args);
            return s == null || s.trim().isEmpty() ? t.fallback : s;
        } catch (RuntimeException e) {
            return t.fallback;
        }
    }

    static String clean(String s, int maxCodePoints) {
        StringBuilder sb = new StringBuilder(s.length());
        int n = 0;
        for (int i = 0; i < s.length() && n < maxCodePoints; ) {
            int cp = s.codePointAt(i);
            int len = Character.charCount(cp);
            if (len == 2 || ConfirmRequest.isAllowedChar((char) cp)) {
                sb.appendCodePoint(cp);
                n++;
            }
            i += len;
        }
        return sb.toString();
    }
}
