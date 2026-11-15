package moe.qingu.youran.api.network;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * 系统确认框（{@link SystemConfirm}）的内容：标题、哪个网站（域名）、要授权的条目，以及多久没回应算拒绝。不可变。
 * <p>
 * 上限（超出时构造抛 {@link IllegalArgumentException}）：标题 1–{@value #MAX_TITLE} 个字；域名按 {@link DomainNames} 转成 A-label 后
 * 不超过 253 个字符（客户端按 A-label 显示 Unicode 形式，混用文字系统的显示成 {@code xn--} 形式，防仿冒）；
 * 条目最多 {@value #MAX_ITEMS} 条、每条 1–{@value #MAX_ITEM} 个字；都不能有控制字符。时限 5–{@value #MAX_TIMEOUT_SECONDS} 秒，
 * 默认 {@value #DEFAULT_TIMEOUT_SECONDS} 秒。
 */
public final class ConfirmRequest {
    public static final int MAX_TITLE = 40;
    public static final int MAX_ITEMS = 12;
    public static final int MAX_ITEM = 80;
    public static final int DEFAULT_TIMEOUT_SECONDS = 60;
    public static final int MAX_TIMEOUT_SECONDS = 120;

    public final String title;
    /** 网站的域名（A-label，小写） */
    public final String site;
    public final List<String> items;
    public final int timeoutSeconds;

    public ConfirmRequest(String title, String site, List<String> items) {
        this(title, site, items, DEFAULT_TIMEOUT_SECONDS);
    }

    public ConfirmRequest(String title, String site, List<String> items, int timeoutSeconds) {
        this.title = checkText(title, MAX_TITLE, "标题");
        String s = site == null ? null : DomainNames.tryToAscii(site.trim());
        if (s == null || s.isEmpty() || s.length() > DomainNames.MAX_NAME || !s.matches("[a-z0-9]([a-z0-9.-]*[a-z0-9])?")) {
            throw new IllegalArgumentException("网站域名不对：" + site);
        }
        this.site = s;
        List<String> list = new ArrayList<>();
        if (items != null) {
            if (items.size() > MAX_ITEMS) {
                throw new IllegalArgumentException("条目超过 " + MAX_ITEMS + " 条");
            }
            for (String i : items) {
                list.add(checkText(i, MAX_ITEM, "条目"));
            }
        }
        this.items = Collections.unmodifiableList(list);
        if (timeoutSeconds < 5 || timeoutSeconds > MAX_TIMEOUT_SECONDS) {
            throw new IllegalArgumentException("时限要在 5–" + MAX_TIMEOUT_SECONDS + " 秒之间：" + timeoutSeconds);
        }
        this.timeoutSeconds = timeoutSeconds;
    }

    private static String checkText(String s, int max, String what) {
        if (s == null || s.trim().isEmpty()) {
            throw new IllegalArgumentException(what + "不能为空");
        }
        if (s.codePointCount(0, s.length()) > max) {
            throw new IllegalArgumentException(what + "超过 " + max + " 个字：" + s);
        }
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            if (c < 0x20 || c == 0x7f || (c >= 0x80 && c < 0xa0) || c == '§' || c == ' ' || c == ' '
                    || (c >= '‪' && c <= '‮') || (c >= '⁦' && c <= '⁩')) {
                throw new IllegalArgumentException(what + "里有不允许的字符（控制字符、颜色代码、文字方向控制）");
            }
        }
        return s;
    }
}
