package moe.qingu.youran.server.net.nav;

import moe.qingu.youran.api.network.DomainNames;
import moe.qingu.youran.api.network.SiteInfo;

import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.Map;

/**
 * 180.yr、platform.180.yr、help.180.yr 的页面：XHTML 1.0 Strict + CSS 2.1，没有脚本（家具电脑浏览器只认这些，页面宽 600）。
 * 样子照零几年的导航站：大 Logo + 搜索框、名站格子、分栏、右边“今日”。主站与平台只放该放的，说明都在帮助站（服主 11-15）。
 * 玩家、网站给的东西写进页面前都转义。
 */
final class NavPages {
    static final DateTimeFormatter TIME = DateTimeFormatter.ofPattern("MM-dd HH:mm");

    private NavPages() {
    }

    /** 各页共用的样式 */
    static final String CSS = ""
            + "body { margin: 0; padding: 0; background: #ffffff; color: #222222; font-family: sans-serif; font-size: 12px; }\n"
            + "a { color: #1a3f8f; }\n"
            + "a.plain { text-decoration: none; }\n"
            + "div.wrap { margin: 0; padding: 6px 8px; }\n"
            + "table.layout { width: 100%; border-collapse: collapse; }\n"
            + "table.layout td { padding: 0; vertical-align: top; }\n"
            + "div.toplinks { text-align: right; font-size: 11px; color: #888888; padding: 2px 0; }\n"
            + "div.toplinks a { color: #555555; }\n"
            + "input.q { width: 250px; height: 20px; border: 2px solid #18489a; }\n"
            + "input.go { padding: 3px 10px; background: #18489a; border: 1px solid #0f3270; color: #ffffff; font-weight: bold; }\n"
            + "input.go2 { padding: 3px 10px; background: #ee801c; border: 1px solid #b85f10; color: #ffffff; font-weight: bold; }\n"
            + "div.box { border: 1px solid #c4d4e8; margin-bottom: 6px; }\n"
            + "div.box h2 { margin: 0; padding: 3px 6px; background: #eaf1fa; border-bottom: 1px solid #c4d4e8; font-size: 13px; color: #18489a; }\n"
            + "div.box div.in { padding: 4px 6px; }\n"
            + "table.famous { width: 100%; border-collapse: collapse; }\n"
            + "table.famous td { width: 25%; padding: 5px 2px; text-align: center; font-size: 13px; border-bottom: 1px dotted #d8e2ee; }\n"
            + "table.cats { width: 100%; border-collapse: collapse; }\n"
            + "table.cats th { width: 56px; padding: 4px; text-align: center; background: #f4f7fb; color: #18489a; font-size: 12px; border-bottom: 1px solid #e3eaf3; }\n"
            + "table.cats td { padding: 4px 6px; border-bottom: 1px solid #e3eaf3; line-height: 1.6; }\n"
            + "span.sep { color: #c0c8d2; }\n"
            + "span.gray, .gray { color: #888888; }\n"
            + "span.small, .small { font-size: 11px; }\n"
            + "div.adtag { font-size: 10px; color: #999999; text-align: right; }\n"
            + "div.adtag a { color: #999999; }\n"
            + "div.textads { margin-bottom: 6px; padding: 3px 6px; background: #fff8ee; border: 1px solid #f2cfa0; }\n"
            + "div.textads a.ad { color: #b5420e; }\n"
            + "a.vacant { display: block; text-align: center; color: #8aa0bc; border: 1px dashed #a9bdd6; text-decoration: none; background: #f7f9fc; }\n"
            + "div.today { padding: 4px 6px; text-align: center; }\n"
            + "div.today p { margin: 2px 0; }\n"
            + "p.clock { font-size: 20px; font-weight: bold; color: #18489a; }\n"
            + "div.buttons { text-align: center; padding: 4px 0; border-top: 1px solid #e3eaf3; }\n"
            + "div.buttons span.b { display: inline-block; margin: 0 2px; text-align: center; vertical-align: top; }\n"
            + "div.foot { text-align: center; color: #999999; font-size: 11px; padding: 6px 0; border-top: 1px solid #e3eaf3; }\n"
            + "div.bar { background: #18489a; border-bottom: 3px solid #ee801c; padding: 4px 8px; color: #ffffff; }\n"
            + "div.bar a { color: #ffffff; }\n"
            + "div.bar input { color: #18489a; font-size: 11px; padding: 0 4px; }\n"
            + "span.brand { font-size: 16px; font-weight: bold; }\n"
            + "span.sub { color: #c8d6ee; margin-left: 6px; }\n"
            + "td.who { text-align: right; color: #c8d6ee; font-size: 11px; vertical-align: middle; }\n"
            + "h1 { margin: 4px 0 8px 0; font-size: 16px; color: #18489a; }\n"
            + "h3 { margin: 10px 0 4px 0; font-size: 13px; color: #18489a; }\n"
            + "p { margin: 0 0 6px 0; line-height: 1.5; }\n"
            + "table.list { width: 100%; border-collapse: collapse; margin-bottom: 8px; }\n"
            + "table.list th { background: #eaf1fa; border: 1px solid #c4d4e8; padding: 3px 4px; text-align: left; font-weight: normal; }\n"
            + "table.list td { border: 1px solid #dde5ef; padding: 3px 4px; vertical-align: top; }\n"
            + "td.num { text-align: right; }\n"
            + "p.ok { background: #e9f6e8; border: 1px solid #a8d4a3; padding: 5px 8px; }\n"
            + "p.err { background: #fbecec; border: 1px solid #e3a9a9; padding: 5px 8px; color: #8a1f1f; }\n"
            + "table.form { border-collapse: collapse; margin-bottom: 8px; }\n"
            + "table.form th { text-align: right; font-weight: normal; color: #555555; padding: 4px 6px 4px 0; vertical-align: top; width: 70px; }\n"
            + "table.form td { padding: 4px 0; vertical-align: top; }\n"
            + "ul.res { margin: 0; padding: 0 0 0 4px; list-style: none; }\n"
            + "ul.res li { margin: 0 0 8px 0; }\n"
            + "span.res-title { font-size: 14px; }\n"
            + "span.url { color: #2a7a2a; }\n";

    static String esc(String s) {
        if (s == null) {
            return "";
        }
        StringBuilder b = new StringBuilder(s.length() + 16);
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            switch (c) {
                case '&': b.append("&amp;"); break;
                case '<': b.append("&lt;"); break;
                case '>': b.append("&gt;"); break;
                case '"': b.append("&quot;"); break;
                case '\'': b.append("&#39;"); break;
                default: b.append(c);
            }
        }
        return b.toString();
    }

    static String head(String title, int refresh, String refreshUrl) {
        StringBuilder sb = new StringBuilder(8192);
        sb.append("<?xml version=\"1.0\" encoding=\"UTF-8\"?>\n")
                .append("<!DOCTYPE html PUBLIC \"-//W3C//DTD XHTML 1.0 Strict//EN\" \"http://www.w3.org/TR/xhtml1/DTD/xhtml1-strict.dtd\">\n")
                .append("<html xmlns=\"http://www.w3.org/1999/xhtml\" xml:lang=\"zh-CN\" lang=\"zh-CN\">\n<head>\n")
                .append("<meta http-equiv=\"Content-Type\" content=\"application/xhtml+xml; charset=UTF-8\" />\n");
        if (refresh > 0) {
            sb.append("<meta http-equiv=\"refresh\" content=\"").append(refresh).append(refreshUrl == null ? "" : ";url=" + esc(refreshUrl)).append("\" />\n");
        }
        sb.append("<title>").append(esc(title)).append("</title>\n<style type=\"text/css\">\n").append(CSS).append("</style>\n</head>\n<body>\n");
        return sb.toString();
    }

    static final String END = "</body>\n</html>\n";

    // ---------------------------------------------------------------- 180.yr

    /** 主页要的东西 */
    static final class Home {
        String scheme = "http";
        NavDirectory directory;
        Today today = Today.UNKNOWN;
        AdBook.Ad banner;
        AdBook.Ad[] squares = new AdBook.Ad[0];
        AdBook.Ad[] texts = new AdBook.Ad[0];
        AdBook.Ad[] buttons = new AdBook.Ad[0];
    }

    static String url(String scheme, String host, String path) {
        return scheme + "://" + host + path;
    }

    static String siteUrl(String scheme, NavDirectory.Site s) {
        return scheme + "://" + s.domain + s.info.path;
    }

    private static String report(String scheme, AdBook.Ad a) {
        return "<a href=\"" + esc(url(scheme, NavService.PLATFORM, "/report?ad=" + a.id)) + "\">举报</a>";
    }

    private static String imageAd(String scheme, AdBook.Ad a) {
        return "<a href=\"" + esc(a.link) + "\" title=\"" + esc(a.text) + "\"><img src=\"/ad/" + a.id + ".png\" width=\"" + a.slot.width + "\" height=\""
                + a.slot.height + "\" alt=\"" + esc(a.text.isEmpty() ? "广告" : a.text) + "\" style=\"border: 0; width: " + a.slot.width + "px; height: "
                + a.slot.height + "px;\" /></a>";
    }

    private static String vacant(String scheme, AdSlot slot, String label) {
        int lh = slot.height - 2;
        return "<a class=\"vacant\" href=\"" + esc(url(scheme, NavService.PLATFORM, "/buy?slot=" + slot.code)) + "\" style=\"width: " + (slot.width - 2)
                + "px; height: " + lh + "px; line-height: " + lh + "px;" + (slot == AdSlot.BANNER ? " margin: 0 auto;" : "") + "\">" + esc(label) + "</a>";
    }

    /** 搜索框（主页、搜索页） */
    private static String searchForm(String q) {
        return "<form method=\"get\" action=\"/s\"><div><input class=\"q\" type=\"text\" name=\"q\" maxlength=\"" + NavDirectory.MAX_QUERY
                + "\" value=\"" + esc(q) + "\" /> <input class=\"go\" type=\"submit\" value=\"搜索\" /></div></form>";
    }

    private static String topLinks(String scheme) {
        return "<div class=\"toplinks\"><a href=\"" + esc(url(scheme, NavService.HELP, "/#home")) + "\">设为主页</a> <span class=\"sep\">|</span> "
                + "<a href=\"" + esc(url(scheme, NavService.PLATFORM, "/")) + "\">广告投放</a> <span class=\"sep\">|</span> "
                + "<a href=\"" + esc(url(scheme, NavService.HELP, "/")) + "\">帮助</a></div>";
    }

    private static String logoRow(String scheme, String q) {
        return "<table class=\"layout\"><tr><td style=\"width: 160px;\"><a href=\"/\"><img src=\"/logo.png\" width=\"150\" height=\"48\" alt=\"180 网址导航\" "
                + "style=\"border: 0; width: 150px; height: 48px;\" /></a></td><td style=\"vertical-align: middle;\">" + searchForm(q) + "</td></tr></table>\n";
    }

    static String home(Home h) {
        String s = h.scheme;
        StringBuilder b = new StringBuilder(head("180 网址导航", 0, null));
        b.append("<div class=\"wrap\">\n").append(topLinks(s)).append(logoRow(s, ""));
        // 顶部横幅
        b.append("<div style=\"margin: 6px 0; text-align: center;\">");
        if (h.banner != null) {
            b.append(imageAd(s, h.banner)).append("<div class=\"adtag\" style=\"width: 468px; margin: 0 auto;\">广告 ").append(report(s, h.banner)).append("</div>");
        } else {
            b.append(vacant(s, AdSlot.BANNER, "广告位招租 468×60"));
        }
        b.append("</div>\n");
        b.append("<table class=\"layout\"><tr><td style=\"padding-right: 8px;\">\n");
        // 名站导航
        List<NavDirectory.Site> famous = h.directory.featured();
        b.append("<div class=\"box\"><h2>名站导航</h2><table class=\"famous\">");
        if (famous.isEmpty()) {
            b.append("<tr><td class=\"gray\">（暂无）</td></tr>");
        }
        for (int i = 0; i < famous.size(); i += 4) {
            b.append("<tr>");
            for (int j = i; j < i + 4; j++) {
                if (j < famous.size()) {
                    NavDirectory.Site site = famous.get(j);
                    b.append("<td><a href=\"").append(esc(siteUrl(s, site))).append("\" title=\"").append(esc(site.info.summary)).append("\">")
                            .append(esc(site.info.title)).append("</a></td>");
                } else {
                    b.append("<td></td>");
                }
            }
            b.append("</tr>");
        }
        b.append("</table></div>\n");
        // 文字链
        b.append("<div class=\"textads\">");
        boolean any = false;
        boolean free = false;
        for (AdBook.Ad a : h.texts) {
            if (a == null) {
                free = true;
                continue;
            }
            if (any) {
                b.append(" <span class=\"sep\">|</span> ");
            }
            any = true;
            b.append("<a class=\"ad\" href=\"").append(esc(a.link)).append("\">").append(esc(a.text)).append("</a><span class=\"small gray\">(")
                    .append(report(s, a)).append(")</span>");
        }
        if (free) {
            b.append(any ? " <span class=\"sep\">|</span> " : "").append("<a class=\"gray\" href=\"").append(esc(url(s, NavService.PLATFORM, "/buy?slot=text")))
                    .append("\">在这里打广告</a>");
        }
        b.append("</div>\n");
        // 分栏
        b.append("<div class=\"box\"><table class=\"cats\">");
        for (Map.Entry<SiteInfo.Category, List<NavDirectory.Site>> e : h.directory.byCategory().entrySet()) {
            b.append("<tr><th>").append(esc(e.getKey().label)).append("</th><td>");
            if (e.getValue().isEmpty()) {
                b.append("<span class=\"gray\">暂无</span>");
            }
            boolean first = true;
            for (NavDirectory.Site site : e.getValue()) {
                if (!first) {
                    b.append(" <span class=\"sep\">|</span> ");
                }
                first = false;
                b.append("<a href=\"").append(esc(siteUrl(s, site))).append("\" title=\"").append(esc(site.info.summary)).append("\">")
                        .append(esc(site.info.title)).append("</a>");
            }
            b.append("</td></tr>");
        }
        b.append("</table></div>\n");
        b.append("</td><td style=\"width: 135px;\">\n");
        // 今日
        Today t = h.today;
        b.append("<div class=\"box\"><h2>今日</h2><div class=\"today\"><p>第 ").append(t.day).append(" 天</p><p class=\"clock\">").append(t.clock())
                .append("</p><p>").append(esc(t.period())).append(" · ").append(esc(t.weather.label)).append("</p><p class=\"gray\">在线 ").append(t.online)
                .append(" 人</p></div></div>\n");
        // 侧栏方块：在播的都放；空位只放一个“招租”（都空着时页面不至于拉得很长）
        boolean vacantShown = false;
        for (AdBook.Ad a : h.squares) {
            if (a == null) {
                if (vacantShown) {
                    continue;
                }
                vacantShown = true;
            }
            b.append("<div style=\"margin-bottom: 6px; text-align: center;\">");
            if (a != null) {
                b.append(imageAd(s, a)).append("<div class=\"adtag\">广告 ").append(report(s, a)).append("</div>");
            } else {
                b.append(vacant(s, AdSlot.SQUARE, "广告位招租"));
            }
            b.append("</div>\n");
        }
        b.append("</td></tr></table>\n");
        // 小按钮
        b.append("<div class=\"buttons\">");
        for (AdBook.Ad a : h.buttons) {
            b.append("<span class=\"b\">");
            if (a != null) {
                b.append(imageAd(s, a)).append("<br /><span class=\"small gray\">").append(report(s, a)).append("</span>");
            } else {
                b.append(vacant(s, AdSlot.BUTTON, "招租"));
            }
            b.append("</span>");
        }
        b.append("</div>\n");
        b.append("<div class=\"foot\"><a href=\"").append(esc(url(s, NavService.PLATFORM, "/"))).append("\">广告投放</a> · <a href=\"")
                .append(esc(url(s, NavService.HELP, "/"))).append("\">帮助</a> · 180.yr</div>\n</div>\n").append(END);
        return b.toString();
    }

    static String search(String scheme, String q, List<NavDirectory.Site> results) {
        StringBuilder b = new StringBuilder(head((q.isEmpty() ? "搜索" : q + " - 搜索") + " - 180", 0, null));
        b.append("<div class=\"wrap\">\n").append(topLinks(scheme)).append(logoRow(scheme, q));
        b.append("<div style=\"border-top: 1px solid #c4d4e8; margin-top: 6px; padding-top: 6px;\">");
        if (q.isEmpty()) {
            b.append("<p class=\"gray\">输入网站名或关键词。</p>");
        } else if (results.isEmpty()) {
            b.append("<p>没有找到和 <b>").append(esc(q)).append("</b> 有关的网站。</p>");
        } else {
            b.append("<p class=\"gray small\">找到 ").append(results.size()).append(" 个网站</p><ul class=\"res\">");
            for (NavDirectory.Site site : results) {
                String u = siteUrl(scheme, site);
                b.append("<li><span class=\"res-title\"><a href=\"").append(esc(u)).append("\">").append(esc(site.info.title)).append("</a></span>")
                        .append(" <span class=\"gray small\" style=\"margin-left: 6px;\">").append(esc(site.info.category.label)).append("</span><br />");
                if (!site.info.summary.isEmpty()) {
                    b.append(esc(site.info.summary)).append("<br />");
                }
                b.append("<span class=\"url small\">").append(esc(site.display)).append("</span></li>");
            }
            b.append("</ul>");
        }
        b.append("</div>\n<div class=\"foot\"><a href=\"/\">180 首页</a></div>\n</div>\n").append(END);
        return b.toString();
    }

    // ---------------------------------------------------------------- platform.180.yr、help.180.yr 的框架

    /** 平台、帮助站的页面骨架：上面一条深蓝的条 */
    static String frame(String title, String brandSub, String who, String csrf, boolean admin, String scheme, String body, int refresh, String refreshUrl) {
        StringBuilder b = new StringBuilder(head(title + " - 180", refresh, refreshUrl));
        b.append("<div class=\"bar\"><table class=\"layout\"><tr><td style=\"vertical-align: middle;\"><a class=\"plain\" href=\"")
                .append(esc(url(scheme, NavService.NAV, "/"))).append("\"><span class=\"brand\">180</span></a><span class=\"sub\">").append(esc(brandSub))
                .append("</span></td><td class=\"who\">");
        if (who != null) {
            b.append(esc(who));
            if (admin) {
                b.append(" | <a href=\"/admin\">管理</a>");
            }
            b.append(" | <a href=\"/\">我的广告</a>");
            if (csrf != null) {
                b.append("<form method=\"post\" action=\"/logout\" style=\"display: inline;\"><div style=\"display: inline;\"><input type=\"hidden\" name=\"csrf\" value=\"")
                        .append(esc(csrf)).append("\" /> <input type=\"submit\" value=\"退出\" /></div></form>");
            }
            b.append(" | ");
        } else if (brandSub.equals("广告平台")) {
            b.append("<a href=\"/login\">登录</a> | ");
        }
        b.append("<a href=\"").append(esc(url(scheme, NavService.HELP, "/"))).append("\">帮助</a></td></tr></table></div>\n<div class=\"wrap\">\n")
                .append(body).append("</div>\n<div class=\"foot\"><a href=\"").append(esc(url(scheme, NavService.NAV, "/"))).append("\">180 首页</a></div>\n")
                .append(END);
        return b.toString();
    }

    static String time(long ms) {
        return TIME.format(Instant.ofEpochMilli(ms).atZone(ZoneId.systemDefault()));
    }

    /** 多少毫秒换成“x 天 y 小时”（现实时间） */
    static String ticks(long t) {
        long days = t / AdRules.DAY_MS;
        long hours = (t % AdRules.DAY_MS) / 3_600_000L;
        if (days > 0) {
            return days + " 天" + (hours > 0 ? " " + hours + " 小时" : "");
        }
        if (hours > 0) {
            return hours + " 小时";
        }
        long minutes = (t + 59_999) / 60_000;
        return minutes <= 1 ? "不到 1 分钟" : minutes + " 分钟";
    }

    static String yuan(long y) {
        return y % 2 == 0 ? (y / 2) + " 绿宝石" : y + " 元";
    }

    /** 广告内容的小预览（列表里用） */
    static String preview(AdBook.Ad a) {
        if (!a.slot.isImage()) {
            return "<a href=\"" + esc(a.link) + "\">" + esc(a.text) + "</a>";
        }
        int w = a.slot.width, h = a.slot.height;
        if (w > 160) {
            h = h * 160 / w;
            w = 160;
        }
        if (h > 64) {
            w = w * 64 / h;
            h = 64;
        }
        return "<img src=\"/ad/" + a.id + ".png\" width=\"" + w + "\" height=\"" + h + "\" alt=\"#" + a.id + "\" style=\"width: " + w + "px; height: " + h
                + "px; border: 1px solid #dde5ef;\" />";
    }

    static String domainOf(String link) {
        int i = link.indexOf("://");
        String rest = i < 0 ? link : link.substring(i + 3);
        int e = rest.indexOf('/');
        return DomainNames.toDisplay(e < 0 ? rest : rest.substring(0, e));
    }
}
