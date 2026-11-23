package moe.qingu.youran.server.net.nav;

import moe.qingu.youran.api.image.DyePng;
import moe.qingu.youran.api.network.DomainNames;

import java.util.Locale;
import java.util.regex.Pattern;

/** 投放表单的检查（纯逻辑）：天数、链接、文字、图片 */
final class AdInput {
    /** 文字链最多几个字 */
    static final int MAX_TEXT = 16;
    /** 图片说明（悬停文字）最多几个字 */
    static final int MAX_ALT = 24;
    static final int MAX_LINK = 200;
    /** 举报理由最多几个字 */
    static final int MAX_REASON = 60;
    private static final Pattern PATH = Pattern.compile("[A-Za-z0-9/_.~!$&'()*+,;=:@%?-]*");

    private AdInput() {
    }

    static final class Bad extends Exception {
        Bad(String message) {
            super(message);
        }
    }

    static int days(String s, int max) throws Bad {
        if (s == null || !s.trim().matches("\\d{1,4}")) {
            throw new Bad("天数要填 1–" + max + " 的整数");
        }
        int d = Integer.parseInt(s.trim());
        if (d < 1 || d > max) {
            throw new Bad("天数要填 1–" + max + " 的整数");
        }
        return d;
    }

    /**
     * 链接：http / https，主机是 .yr 或 .com 下的域名（游戏内网站；国际化域名写 Unicode 也行，存 A-label），不许用户名密码、端口外的怪东西、{@code #}。
     * 返回规范写法（小写主机、没有路径时补 {@code /}）。
     */
    static String link(String s) throws Bad {
        if (s == null || s.trim().isEmpty()) {
            throw new Bad("要填链接");
        }
        String v = s.trim();
        if (v.length() > MAX_LINK) {
            throw new Bad("链接太长（最多 " + MAX_LINK + " 个字符）");
        }
        String lower = v.toLowerCase(Locale.ROOT);
        String scheme;
        if (lower.startsWith("http://")) {
            scheme = "http";
        } else if (lower.startsWith("https://")) {
            scheme = "https";
        } else if (!lower.contains("://")) {
            scheme = "http";
            v = "http://" + v;
        } else {
            throw new Bad("链接要以 http:// 或 https:// 开头");
        }
        String rest = v.substring(scheme.length() + 3);
        int slash = rest.indexOf('/');
        int q = rest.indexOf('?');
        int end = slash < 0 ? q : q < 0 ? slash : Math.min(slash, q);
        String host = end < 0 ? rest : rest.substring(0, end);
        String path = end < 0 ? "/" : rest.substring(end);
        if (path.startsWith("?")) {
            path = "/" + path;
        }
        String port = "";
        int colon = host.lastIndexOf(':');
        if (colon >= 0) {
            port = host.substring(colon);
            host = host.substring(0, colon);
            if (!port.matches(":[0-9]{1,5}") || Integer.parseInt(port.substring(1)) > 65535 || Integer.parseInt(port.substring(1)) == 0) {
                throw new Bad("链接里的端口不对");
            }
        }
        if (host.contains("@")) {
            throw new Bad("链接里不能有用户名、密码");
        }
        String a = DomainNames.tryToAscii(host.toLowerCase(Locale.ROOT));
        if (a == null || !(a.endsWith(".yr") || a.endsWith(".com")) || !a.matches("[a-z0-9]([a-z0-9.-]*[a-z0-9])?") || a.contains("..")) {
            throw new Bad("链接要指向游戏内的网站（.yr 或 .com 的域名）");
        }
        if (path.contains("#") || !PATH.matcher(path).matches()) {
            throw new Bad("链接的路径里有不允许的字符（空格、引号、# 等）");
        }
        return scheme + "://" + a + port + path;
    }

    /** 文字链的文字、图片说明：去掉首尾空白，不许控制字符、颜色代码、文字方向控制 */
    static String text(String s, int max, boolean required, String what) throws Bad {
        String t = s == null ? "" : s.trim().replaceAll("\\s+", " ");
        if (t.isEmpty()) {
            if (required) {
                throw new Bad("要填" + what);
            }
            return "";
        }
        if (t.codePointCount(0, t.length()) > max) {
            throw new Bad(what + "太长（最多 " + max + " 个字）");
        }
        for (int i = 0; i < t.length(); i++) {
            char c = t.charAt(i);
            if (Character.isISOControl(c) || c == '§' || (c >= 0x202A && c <= 0x202E) || (c >= 0x2066 && c <= 0x2069) || c == '﻿') {
                throw new Bad(what + "里有不允许的字符");
            }
        }
        return t;
    }

    /**
     * 画板交上来的图（{@code data:image/png;base64,…}）：尺寸要正好是广告位的，只能用 16 种染料色与透明，不能是空白的。
     * 返回重新写过的 PNG。
     */
    static byte[] image(String dataUrl, AdSlot slot) throws Bad {
        if (dataUrl == null || dataUrl.isEmpty()) {
            throw new Bad("要画一张图");
        }
        byte[] png = DyePng.fromDataUrl(dataUrl);
        if (png == null) {
            throw new Bad("图片读不出来，请重新画一次");
        }
        DyePng.Image img;
        try {
            img = DyePng.decode(png, slot.width, slot.height);
        } catch (DyePng.BadImage e) {
            throw new Bad(e.getMessage());
        }
        if (img.isBlank()) {
            throw new Bad("图还是空白的");
        }
        return DyePng.encode(img.width, img.height, img.pixels);
    }
}
