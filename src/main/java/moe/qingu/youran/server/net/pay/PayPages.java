package moe.qingu.youran.server.net.pay;

import moe.qingu.youran.api.network.DomainNames;
import moe.qingu.youran.api.pay.Amount;
import moe.qingu.youran.api.pay.Currency;
import moe.qingu.youran.api.pay.OrderInfo;
import moe.qingu.youran.api.pay.PayItem;
import moe.qingu.youran.api.pay.PayRecord;

import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.Map;

/**
 * pay.yr 的页面：XHTML 1.0 Strict + CSS 2.1，没有脚本（家具电脑浏览器只认这些）。样子照老式支付页：上面一条深蓝的标题栏，
 * 中间一个白底细边框的框，表格排信息，按钮朴素。网页上的文字都是网站内容（中文）；玩家、网站给的东西写进页面前都转义。
 */
final class PayPages {
    static final DateTimeFormatter TIME = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");

    private PayPages() {
    }

    /** 页面骨架；{@code refresh} 秒后自动刷新到 {@code refreshUrl}（同站，0 为不刷新） */
    static String page(String title, String who, String csrf, String body, int refresh, String refreshUrl) {
        StringBuilder sb = new StringBuilder(4096);
        sb.append("<?xml version=\"1.0\" encoding=\"UTF-8\"?>\n")
                .append("<!DOCTYPE html PUBLIC \"-//W3C//DTD XHTML 1.0 Strict//EN\" \"http://www.w3.org/TR/xhtml1/DTD/xhtml1-strict.dtd\">\n")
                .append("<html xmlns=\"http://www.w3.org/1999/xhtml\" xml:lang=\"zh-CN\" lang=\"zh-CN\">\n<head>\n")
                .append("<meta http-equiv=\"Content-Type\" content=\"application/xhtml+xml; charset=UTF-8\" />\n");
        if (refresh > 0) {
            sb.append("<meta http-equiv=\"refresh\" content=\"").append(refresh).append(refreshUrl == null ? "" : ";url=" + esc(refreshUrl)).append("\" />\n");
        }
        sb.append("<title>").append(esc(title)).append(" - pay.yr</title>\n")
                .append("<style type=\"text/css\">\n")
                .append("body { margin: 0; padding: 0; background: #e9edf2; color: #1d2733; font-family: sans-serif; font-size: 13px; }\n")
                .append("div.top { background: #1f3f6e; border-bottom: 3px solid #f0a020; padding: 4px 10px; color: #ffffff; }\n")
                .append("div.top table { width: 100%; border-collapse: collapse; }\n")
                .append("span.logo { font-size: 18px; font-weight: bold; color: #ffffff; }\n")
                .append("span.logo2 { font-size: 13px; color: #c9d6ea; margin-left: 8px; }\n")
                .append("td.who { text-align: right; color: #c9d6ea; font-size: 12px; }\n")
                .append("td.who a { color: #ffffff; }\n")
                .append("div.main { margin: 8px; padding: 8px 12px; background: #ffffff; border: 1px solid #b7c3d1; }\n")
                .append("h1 { margin: 0 0 8px 0; padding-bottom: 4px; font-size: 16px; color: #1f3f6e; border-bottom: 1px solid #d5dde6; }\n")
                .append("h2 { margin: 10px 0 4px 0; font-size: 14px; color: #1f3f6e; }\n")
                .append("p { margin: 0 0 6px 0; line-height: 1.4; }\n")
                .append("table.info { border-collapse: collapse; width: 100%; margin-bottom: 8px; }\n")
                .append("table.info th { width: 90px; text-align: left; vertical-align: top; background: #f2f5f8; border: 1px solid #d5dde6; padding: 2px 6px; font-weight: normal; color: #4a5866; }\n")
                .append("table.info td { border: 1px solid #d5dde6; padding: 2px 6px; vertical-align: top; }\n")
                .append("table.list { border-collapse: collapse; width: 100%; margin-bottom: 10px; }\n")
                .append("table.list th { background: #dfe6ee; border: 1px solid #c4cfdb; padding: 3px 6px; text-align: left; font-weight: normal; }\n")
                .append("table.list td { border: 1px solid #d5dde6; padding: 3px 6px; vertical-align: top; }\n")
                .append("td.num { text-align: right; }\n")
                .append("span.amount { font-size: 16px; font-weight: bold; color: #b5321e; }\n")
                .append("p.ok { background: #e7f5e6; border: 1px solid #9fcf9a; padding: 6px 8px; }\n")
                .append("p.err { background: #fbeaea; border: 1px solid #e3a5a5; padding: 6px 8px; color: #8a1f1f; }\n")
                .append(".hint { color: #66727f; font-size: 12px; }\n")
                .append("p.wait { background: #fff7e0; border: 1px solid #e8cf86; padding: 8px; }\n")
                .append("pre { background: #f2f5f8; border: 1px solid #d5dde6; padding: 6px; font-size: 12px; }\n")
                .append("code { background: #f2f5f8; }\n")
                .append("input.go { padding: 3px 14px; background: #f0a020; border: 1px solid #b97811; color: #ffffff; font-weight: bold; }\n")
                .append("div.foot { margin: 0 8px 8px 8px; color: #7d8996; font-size: 11px; text-align: center; }\n")
                .append("</style>\n</head>\n<body>\n")
                .append("<div class=\"top\"><table><tr><td><span class=\"logo\">pay.yr</span><span class=\"logo2\">悠然聚合支付</span></td>")
                .append("<td class=\"who\">");
        if (who != null) {
            sb.append("已登录：<b>").append(esc(who)).append("</b> | <a href=\"/account\">我的账户</a>");
            if (csrf != null) {
                sb.append("<form method=\"post\" action=\"/logout\" style=\"display: inline\"><div style=\"display: inline\">")
                        .append("<input type=\"hidden\" name=\"csrf\" value=\"").append(esc(csrf)).append("\" />")
                        .append(" <input type=\"submit\" value=\"退出\" /></div></form>");
            }
        } else {
            sb.append("<a href=\"/\">首页</a>");
        }
        sb.append("</td></tr></table></div>\n<div class=\"main\">\n").append(body).append("</div>\n")
                .append("<div class=\"foot\">pay.yr 由悠然服务器提供 · 每一笔都要在游戏弹出的系统确认框里确认（网页点不到它） · 暂不收手续费</div>\n")
                .append("</body>\n</html>\n");
        return sb.toString();
    }

    static String message(String title, String who, String csrf, String cls, String text, String links) {
        return page(title, who, csrf, "<h1>" + esc(title) + "</h1>\n<p class=\"" + cls + "\">" + esc(text) + "</p>\n"
                + (links == null ? "" : "<p>" + links + "</p>\n"), 0, null);
    }

    static String link(String href, String text) {
        return "<a href=\"" + esc(href) + "\">" + esc(text) + "</a>";
    }

    static String time(long millis) {
        return TIME.format(Instant.ofEpochMilli(millis).atZone(ZoneId.systemDefault()));
    }

    static String site(String asciiSite) {
        return DomainNames.toDisplay(asciiSite);
    }

    /** 金额的明细表（钱一行，物品一行一种） */
    static String amountRows(Amount a, Currency.Unit unit) {
        StringBuilder sb = new StringBuilder();
        for (Map.Entry<PayItem, Long> e : a.items.entrySet()) {
            sb.append("<tr><td>").append(esc(ItemText.name(e.getKey()))).append(" ×").append(e.getValue()).append("</td><td class=\"hint\">")
                    .append(esc(e.getKey().toString().length() > 80 ? e.getKey().id + "（带 NBT，要完全一样）" : e.getKey().toString()))
                    .append("</td></tr>\n");
        }
        return sb.toString();
    }

    static String items(Map<PayItem, Long> m) {
        StringBuilder sb = new StringBuilder();
        for (Map.Entry<PayItem, Long> e : m.entrySet()) {
            if (sb.length() > 0) {
                sb.append("、");
            }
            sb.append(ItemText.name(e.getKey())).append(" ×").append(e.getValue());
        }
        return sb.length() == 0 ? "（无）" : sb.toString();
    }

    static String recordKind(PayRecord.Kind k) {
        switch (k) {
            case RECEIVED:
                return "收款";
            case PAID:
                return "付款（从背包）";
            case CHANGE:
                return "找零记入";
            case REFUND_IN:
                return "收到退款";
            case REFUND_OUT:
                return "退款";
            case WITHDRAW:
                return "提现";
            case WITHDRAW_RETURNED:
                return "提现退回";
            case TAKEN:
                return "取出";
            default:
                return "作废";
        }
    }

    static String records(List<PayRecord> rs) {
        if (rs.isEmpty()) {
            return "<p class=\"hint\">还没有收支记录。</p>\n";
        }
        StringBuilder sb = new StringBuilder("<table class=\"list\"><tr><th>时间</th><th>类型</th><th>金额</th><th>对方</th><th>说明</th></tr>\n");
        for (PayRecord r : rs) {
            String sign = r.sign() > 0 ? "+ " : r.sign() < 0 ? "− " : "";
            sb.append("<tr><td>").append(time(r.time)).append("</td><td>").append(recordKind(r.kind)).append("</td><td>")
                    .append(esc(sign + ItemText.describe(r.amount, Currency.Unit.YUAN))).append("</td><td>")
                    .append(esc(r.counterparty == null ? "" : r.counterparty)).append("</td><td>")
                    .append(esc(r.note == null ? "" : r.note)).append(r.ref == null ? "" : " <span class=\"hint\">" + esc(r.ref) + "</span>")
                    .append("</td></tr>\n");
        }
        return sb.append("</table>\n").toString();
    }

    static String statusName(OrderInfo.Status s) {
        switch (s) {
            case OPEN:
                return "待付款";
            case PAYING:
                return "正在付款";
            case PAID:
                return "已付款";
            case CANCELED:
                return "已取消";
            default:
                return "已过期";
        }
    }

    static String esc(String s) {
        if (s == null) {
            return "";
        }
        StringBuilder sb = new StringBuilder(s.length());
        for (int i = 0; i < s.length(); i++) {
            char ch = s.charAt(i);
            switch (ch) {
                case '&': sb.append("&amp;"); break;
                case '<': sb.append("&lt;"); break;
                case '>': sb.append("&gt;"); break;
                case '"': sb.append("&quot;"); break;
                case '\'': sb.append("&#39;"); break;
                default:
                    if (ch == '\t' || ch == '\n' || ch == '\r' || (ch >= 0x20 && ch != 0xFFFE && ch != 0xFFFF && !Character.isSurrogate(ch))) {
                        sb.append(ch);
                    } else if (Character.isHighSurrogate(ch) && i + 1 < s.length() && Character.isLowSurrogate(s.charAt(i + 1))) {
                        sb.append(ch).append(s.charAt(++i));
                    }
            }
        }
        return sb.toString();
    }

    /** 首页：介绍与给网站作者的接口说明 */
    static String index(String who, String csrf, boolean idOk, String idDomain, boolean https) {
        String base = (https ? "https" : "http") + "://pay.yr";
        StringBuilder b = new StringBuilder();
        b.append("<h1>pay.yr 悠然聚合支付</h1>\n")
                .append("<p>任何游戏内网站接入 pay.yr 就能收款：网站开一张收款单，把你带到这里，你用游戏角色登录、在游戏弹出的确认框里确认，"
                        + "pay.yr 从你的背包里扣东西，记进收款方的账户，再带你回网站。</p>\n")
                .append("<p>可以用任何物品付（物品、数据值、NBT 要完全一样）；<b>绿宝石和悠然元可以互换</b>（1 绿宝石 = 2 元），"
                        + "网站只标一种价，你用哪种付都行、可以混着付。装着东西的容器（有内容的潜影盒、背包等）不收，空的照收。</p>\n");
        if (!idOk) {
            b.append("<p class=\"err\">这个服务器现在没有开游戏角色一键登录（").append(esc(idDomain))
                    .append("，由 YouranAuth 提供），pay.yr 暂时不能登录、不能付款。</p>\n");
        } else if (who == null) {
            b.append("<form method=\"get\" action=\"/login\"><div><input type=\"hidden\" name=\"next\" value=\"/account\" />")
                    .append("<input type=\"submit\" class=\"go\" value=\"用游戏角色登录，查看我的账户\" /></div></form>\n");
        } else {
            b.append("<p>").append(link("/account", "查看我的账户（余额、收支记录、提现、退款、密钥）")).append("</p>\n");
        }
        b.append("<h2>给网站作者：怎么收款</h2>\n")
                .append("<p>1. 用收款人的角色登录 pay.yr，在“我的账户”里生成密钥（只显示一次，可以重置；重置后旧的马上作废）。</p>\n")
                .append("<p>2. 网站后台（在游戏内网络里）创建收款单：</p>\n<pre>POST ").append(base).append("/api/v1/orders\n")
                .append("Authorization: Bearer 你的密钥\nContent-Type: application/x-www-form-urlencoded\n\n")
                .append("emerald=20&amp;description=钻石剑&amp;reference=单号123&amp;return_url=https://shop.yr/done</pre>\n")
                .append("<table class=\"list\"><tr><th>字段</th><th>说明</th></tr>\n")
                .append("<tr><td>emerald 或 yuan</td><td>价格（二选一，整数）；付的时候绿宝石、悠然元都能用</td></tr>\n")
                .append("<tr><td>item1 … item16</td><td>要的别的物品 ID（如 minecraft:diamond，数据值写成 minecraft:wool@14）；"
                        + "itemN_count 个数（默认 1），itemN_nbt 要求的 NBT（SNBT 写法，要完全一样）</td></tr>\n")
                .append("<tr><td>return_url</td><td>付完带玩家回哪里（必填，http 或 https，主机就是你的网站；付款页上写的网站域名就是它的主机）。"
                        + "回来时带 <code>?order=单号&amp;status=paid</code></td></tr>\n")
                .append("<tr><td>cancel_url</td><td>玩家点“不付了”回哪里（可选，默认 return_url），带 <code>status=canceled</code></td></tr>\n")
                .append("<tr><td>description</td><td>付款页、确认框上写的“买的是什么”（最多 60 字）</td></tr>\n")
                .append("<tr><td>reference</td><td>你自己的单号（最多 64 个 ASCII 字符），查询时原样带回</td></tr>\n")
                .append("<tr><td>expires_in</td><td>多少秒后过期（60–").append(PayConfig.orderMaxTtlSeconds).append("，默认 ")
                .append(PayConfig.orderTtlSeconds).append("）；每张只能付一次</td></tr>\n")
                .append("<tr><td>format=text</td><td>回纯文本（一行一项 名字=值，给 Z80 上的程序用）；默认回 JSON</td></tr>\n</table>\n")
                .append("<p>回 <code>201</code>：<code>id</code>（单号 ord_…）、<code>url</code>（付款页网址，把玩家的浏览器跳过去）、<code>status</code>、<code>expires_at</code> 等。</p>\n")
                .append("<p>3. 玩家回到 return_url 以后，<b>网站后台一定要再查一次</b>确实付了再发货（网址里的 status 谁都能伪造）：</p>\n")
                .append("<pre>GET ").append(base).append("/api/v1/orders/单号\nAuthorization: Bearer 你的密钥</pre>\n")
                .append("<p><code>status</code> 是 <code>open</code>（还没付）、<code>paid</code>（付了：还有 <code>payment</code>、<code>payer_uuid</code>、"
                        + "<code>payer_name</code>、<code>paid_at</code>、已退的 <code>refunded_yuan</code> 等）、<code>canceled</code>、<code>expired</code>。</p>\n")
                .append("<p>其他：<code>POST /api/v1/orders/单号/cancel</code> 取消还没付的单；<code>GET /api/v1/balance</code> 查自己账户的余额。"
                        + "出错时回 4xx，JSON 是 <code>{\"error\":代号,\"message\":说明}</code>。密钥也可以放在表单字段 <code>key</code> 里。</p>\n")
                .append("<p>退款：在“我的账户”的收款记录里点“退款”，全额或部分（按价值），退进付款人在 pay.yr 的账户，由他自己提现；"
                        + "从你的账户扣回，余额不够（已经提现了）就退不了。pay.yr 不裁决纠纷。</p>\n");
        return page("首页", who, csrf, b.toString(), 0, null);
    }
}
