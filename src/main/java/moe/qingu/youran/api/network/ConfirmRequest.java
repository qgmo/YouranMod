package moe.qingu.youran.api.network;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * 系统确认框（{@link SystemConfirm}）的内容：标题、哪个网站（域名）、条目，以及多久没回应算拒绝、确认的是哪一类事（{@link Purpose}）。不可变。
 * <p>
 * 标题与条目是 {@link ConfirmText}：网站传来的用原样的文字（用 {@code String} 的构造方法即可），YouranMod 自己的固定文字用翻译键，
 * 由操作者的客户端按他的语言显示。{@link #title}、{@link #items} 是服务端写好的整句（客户端没有翻译键时显示的、日志里用的）。
 * <p>
 * 上限（超出时构造抛 {@link IllegalArgumentException}）：标题 1–{@value #MAX_TITLE} 个字；域名按 {@link DomainNames} 转成 A-label 后
 * 不超过 253 个字符（客户端按 A-label 显示 Unicode 形式，混用文字系统的显示成 {@code xn--} 形式，防仿冒）；
 * 条目最多 {@value #MAX_ITEMS} 条、每条 1–{@value #MAX_ITEM} 个字；都不能有控制字符（翻译参数同样检查）。时限 5–{@value #MAX_TIMEOUT_SECONDS} 秒，
 * 默认 {@value #DEFAULT_TIMEOUT_SECONDS} 秒。
 */
public final class ConfirmRequest {
    public static final int MAX_TITLE = 40;
    public static final int MAX_ITEMS = 12;
    public static final int MAX_ITEM = 80;
    public static final int DEFAULT_TIMEOUT_SECONDS = 60;
    public static final int MAX_TIMEOUT_SECONDS = 120;

    /** 确认的是哪一类事：决定框里条目上面那一行（“将会提供”“将会付出”……）和同意按钮上的字（客户端按语言显示） */
    public enum Purpose {
        /** 把信息给网站（一键登录、位置）：“将会提供”，按钮“同意” */
        SHARE,
        /** 付出物品（pay.yr 付款、退款）：“将会付出”，按钮“确认付款” */
        PAY,
        /** 收到物品（pay.yr 提现）：“将会收到”，按钮“确认” */
        RECEIVE,
        /** 其他操作（如重置密钥）：“内容”，按钮“确认” */
        ACTION
    }

    /** 标题（服务端写好的整句） */
    public final String title;
    public final ConfirmText titleText;
    /** 网站的域名（A-label，小写） */
    public final String site;
    /** 条目（服务端写好的整句） */
    public final List<String> items;
    public final List<ConfirmText> itemTexts;
    public final int timeoutSeconds;
    public final Purpose purpose;

    public ConfirmRequest(String title, String site, List<String> items) {
        this(title, site, items, DEFAULT_TIMEOUT_SECONDS);
    }

    public ConfirmRequest(String title, String site, List<String> items, int timeoutSeconds) {
        this(title == null ? null : ConfirmText.literal(title), site, literals(items), timeoutSeconds, Purpose.SHARE);
    }

    /** 带翻译键的标题、条目，并说明确认的是哪一类事 */
    public ConfirmRequest(ConfirmText title, String site, List<ConfirmText> items, int timeoutSeconds, Purpose purpose) {
        this.titleText = checkText(title, MAX_TITLE, "标题");
        this.title = titleText.fallback;
        String s = site == null ? null : DomainNames.tryToAscii(site.trim());
        if (s == null || s.isEmpty() || s.length() > DomainNames.MAX_NAME || !s.matches("[a-z0-9]([a-z0-9.-]*[a-z0-9])?")) {
            throw new IllegalArgumentException("网站域名不对：" + site);
        }
        this.site = s;
        List<ConfirmText> list = new ArrayList<>();
        List<String> plain = new ArrayList<>();
        if (items != null) {
            if (items.size() > MAX_ITEMS) {
                throw new IllegalArgumentException("条目超过 " + MAX_ITEMS + " 条");
            }
            for (ConfirmText i : items) {
                ConfirmText t = checkText(i, MAX_ITEM, "条目");
                list.add(t);
                plain.add(t.fallback);
            }
        }
        this.itemTexts = Collections.unmodifiableList(list);
        this.items = Collections.unmodifiableList(plain);
        if (timeoutSeconds < 5 || timeoutSeconds > MAX_TIMEOUT_SECONDS) {
            throw new IllegalArgumentException("时限要在 5–" + MAX_TIMEOUT_SECONDS + " 秒之间：" + timeoutSeconds);
        }
        this.timeoutSeconds = timeoutSeconds;
        this.purpose = purpose == null ? Purpose.SHARE : purpose;
    }

    private static List<ConfirmText> literals(List<String> items) {
        if (items == null) {
            return null;
        }
        List<ConfirmText> out = new ArrayList<>();
        for (String i : items) {
            out.add(i == null ? null : ConfirmText.literal(i));
        }
        return out;
    }

    private static ConfirmText checkText(ConfirmText t, int max, String what) {
        if (t == null) {
            throw new IllegalArgumentException(what + "不能为空");
        }
        checkText(t.fallback, max, what);
        for (ConfirmText a : t.args) {
            checkText(a, max, what + "的参数");
        }
        return t;
    }

    /** 文字是否合格（客户端渲染翻译后也按同样的字符规则清理）：不许控制字符、颜色代码、文字方向控制 */
    public static boolean isAllowedChar(char c) {
        return !(c < 0x20 || c == 0x7f || (c >= 0x80 && c < 0xa0) || c == '\u00a7' || c == '\u2028' || c == '\u2029'
                || (c >= '\u202a' && c <= '\u202e') || (c >= '\u2066' && c <= '\u2069'));
    }

    private static String checkText(String s, int max, String what) {
        if (s == null || s.trim().isEmpty()) {
            throw new IllegalArgumentException(what + "不能为空");
        }
        if (s.codePointCount(0, s.length()) > max) {
            throw new IllegalArgumentException(what + "超过 " + max + " 个字：" + s);
        }
        for (int i = 0; i < s.length(); i++) {
            if (!isAllowedChar(s.charAt(i))) {
                throw new IllegalArgumentException(what + "里有不允许的字符（控制字符、颜色代码、文字方向控制）");
            }
        }
        return s;
    }
}
