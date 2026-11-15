package moe.qingu.youran.server.net.nav;

import moe.qingu.youran.api.image.DyePng;
import moe.qingu.youran.api.network.Ipv6Address;
import moe.qingu.youran.api.network.http.HttpRequest;
import moe.qingu.youran.api.network.http.HttpResponse;
import moe.qingu.youran.server.net.service.captcha.SourceLimiter;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import java.io.IOException;
import java.security.SecureRandom;
import java.util.ArrayList;
import java.util.Base64;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.ConcurrentHashMap;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 180.yr（导航站）、platform.180.yr（广告平台）、help.180.yr（帮助）三个名字在同一台主机上，按 {@code Host} 分（网络线程上调用，不阻塞；
 * 要动游戏的交给 {@link Env}）。
 * <pre>
 * 180.yr            GET /  主页；GET /s?q=  搜索；GET /logo.png；GET /ad/编号.png（只给在播的）
 * platform.180.yr   GET /  价格与我的广告；GET|POST /buy  投放；GET /ad/编号  一条广告；POST /ad/编号/again  用同样的内容重新下单；
 *                   GET|POST /report  举报；GET /admin、POST /admin/remove、/admin/ban、/admin/unban  管理；
 *                   GET /login、/login/callback、POST /logout  用 id.yr 游戏角色一键登录
 * help.180.yr       GET /、/home、/ads、/rules、/canvas
 * </pre>
 * 登录是 Cookie {@code yr180}（只在 platform.180.yr 上；跟着电脑走，同 pay.yr）。坐在电脑前的人就能用这台电脑上登录着的帐号（服主 11-15）。
 */
final class NavService {
    static final Logger LOG = LogManager.getLogger("YouranMod");
    static final String NAV = "180.yr";
    static final String PLATFORM = "platform.180.yr";
    static final String HELP = "help.180.yr";
    static final String COOKIE = "yr180";
    static final long LOGIN_TTL = 10 * 60_000L;
    static final long SESSION_MAX = 2 * 3600_000L;
    static final int MAX_SESSIONS = 2048;
    static final int MAX_LOGINS = 512;
    static final int MAX_BODY = 96 * 1024;
    static final int SEARCH_MAX = 30;
    private static final Pattern AD_IMG = Pattern.compile("/ad/([0-9]{1,9})\\.png");
    private static final Pattern AD_PAGE = Pattern.compile("/ad/([0-9]{1,9})(/again)?");

    /** 要动游戏、查 pay.yr 的事（实现在 {@link NavServer}） */
    interface Env {
        Today today();

        /** 现在的游戏 tick（主世界总 tick） */
        long tick();

        boolean isAdmin(UUID player, String name);

        /** 游戏角色一键登录（id.yr）在不在 */
        boolean idAvailable();

        /** pay.yr 在不在 */
        boolean payAvailable();

        /** 主线程上给这条广告开收款单，完成时给付款页网址（失败以异常结束，消息给人看） */
        CompletableFuture<String> openOrder(AdBook.Ad ad, String scheme);

        /** 主线程上在 pay.yr 给投放者退 {@code yuan} 元（0 不退），完成表示退好了 */
        CompletableFuture<Void> refund(AdBook.Ad ad, long yuan, String note);

        /** 收款单的状态代号（open / paying / paid / canceled / expired）；查不到为 null */
        String orderStatus(String orderId);

        /** 收款单的付款页网址；查不到为 null */
        String checkoutUrl(String orderId);

        /** 来源地址认出来的操作者名字；认不出为 null */
        String identify(Ipv6Address source);
    }

    static final class Session {
        final String id;
        final UUID player;
        final String name;
        final String csrf;
        final long created;
        volatile long lastSeen;

        Session(String id, UUID player, String name, String csrf, long now) {
            this.id = id;
            this.player = player;
            this.name = name;
            this.csrf = csrf;
            this.created = now;
            this.lastSeen = now;
        }
    }

    static final class Login {
        final String verifier;
        final String scheme;
        final String next;
        final Ipv6Address source;
        final long created;

        Login(String verifier, String scheme, String next, Ipv6Address source, long created) {
            this.verifier = verifier;
            this.scheme = scheme;
            this.next = next;
            this.source = source;
            this.created = created;
        }
    }

    private final AdBook book;
    private final AdStore store;
    private final NavDirectory directory;
    private final Env env;
    private final NavIdClient id;
    private final byte[] logo;
    private final long idleMs;
    private final SecureRandom random = new SecureRandom();
    private final Map<String, Session> sessions = new ConcurrentHashMap<>();
    private final Map<String, Login> logins = new ConcurrentHashMap<>();
    private final SourceLimiter pageLimit = new SourceLimiter(10, 40, 8192);
    private final SourceLimiter loginLimit = new SourceLimiter(0.5, 10, 8192);
    private final SourceLimiter buyLimit = new SourceLimiter(0.2, 5, 8192);
    private final SourceLimiter reportLimit = new SourceLimiter(1 / 60.0, 5, 8192);
    /** 举报过的（来源 + 广告），同一来源同一条只记一次 */
    private final Set<String> reported = Collections.newSetFromMap(new ConcurrentHashMap<String, Boolean>());

    NavService(AdBook book, AdStore store, NavDirectory directory, Env env, NavIdClient id, byte[] logo, long idleMs) {
        this.book = book;
        this.store = store;
        this.directory = directory;
        this.env = env;
        this.id = id;
        this.logo = logo;
        this.idleMs = idleMs;
    }

    // ---------------------------------------------------------------- 分发

    CompletableFuture<HttpResponse> handle(HttpRequest r) {
        long now = System.currentTimeMillis();
        try {
            if (!pageLimit.tryAcquire(String.valueOf(r.remoteAddress), now)) {
                return done(HttpResponse.text(429, "请求太快了，请稍后再试"));
            }
            String host = hostOf(r);
            String scheme = r.secure ? "https" : "http";
            if (host.equals(PLATFORM)) {
                return platform(r, scheme, now);
            }
            if (host.equals(HELP)) {
                return done(help(r, scheme));
            }
            return done(nav(r, scheme));
        } catch (RuntimeException e) {
            LOG.error("180.yr 处理请求 {} {} 出错", r.method, r.path, e);
            return done(page(500, NavPages.frame("出错了", "", null, null, false, r.secure ? "https" : "http",
                    "<p class=\"err\">出错了，请稍后再试。</p>", 0, null)));
        }
    }

    /** 请求的主机名（小写、去掉端口）；没有 Host 时当作 180.yr */
    static String hostOf(HttpRequest r) {
        String h = r.header("Host");
        if (h == null) {
            return NAV;
        }
        h = h.trim().toLowerCase(Locale.ROOT);
        if (h.startsWith("[")) {
            return NAV;
        }
        int c = h.indexOf(':');
        if (c >= 0) {
            h = h.substring(0, c);
        }
        if (h.endsWith(".")) {
            h = h.substring(0, h.length() - 1);
        }
        return h;
    }

    // ---------------------------------------------------------------- 180.yr

    private HttpResponse nav(HttpRequest r, String scheme) {
        boolean get = r.method.equals("GET") || r.method.equals("HEAD");
        if (!get) {
            return notAllowed("GET");
        }
        switch (r.path) {
            case "/":
            case "/index.html":
                return ok(NavPages.home(homeData(scheme)));
            case "/s": {
                String q = NavDirectory.normalizeQuery(r.queryParameters().get("q"));
                String raw = r.queryParameters().get("q");
                return ok(NavPages.search(scheme, raw == null ? "" : raw.trim(), directory.search(q, SEARCH_MAX)));
            }
            case "/logo.png":
                return HttpResponse.of(200, "image/png", logo).withHeader("Cache-Control", "max-age=86400");
            case "/favicon.ico":
                return HttpResponse.text(404, "没有");
            default:
                break;
        }
        Matcher m = AD_IMG.matcher(r.path);
        if (m.matches()) {
            AdBook.Ad a = book.get(Integer.parseInt(m.group(1)));
            return a != null && a.state == AdBook.State.LIVE ? image(a) : HttpResponse.text(404, "没有这张图");
        }
        return page(404, NavPages.search(scheme, "", Collections.<NavDirectory.Site>emptyList()).replace("输入网站名或关键词。", "没有这个页面。"));
    }

    NavPages.Home homeData(String scheme) {
        NavPages.Home h = new NavPages.Home();
        h.scheme = scheme;
        h.directory = directory;
        h.today = env.today();
        h.banner = book.live(AdSlot.BANNER)[0];
        h.squares = book.live(AdSlot.SQUARE);
        h.texts = book.live(AdSlot.TEXT);
        h.buttons = book.live(AdSlot.BUTTON);
        return h;
    }

    private HttpResponse image(AdBook.Ad a) {
        byte[] png = store.image(a.id);
        if (png == null) {
            return HttpResponse.text(404, "没有这张图");
        }
        return HttpResponse.of(200, "image/png", png).withHeader("Cache-Control", "no-cache");
    }

    // ---------------------------------------------------------------- help.180.yr

    private HttpResponse help(HttpRequest r, String scheme) {
        if (!(r.method.equals("GET") || r.method.equals("HEAD"))) {
            return notAllowed("GET");
        }
        String body = HelpPages.page(r.path, scheme, book.rules());
        if (body == null) {
            return page(404, NavPages.frame("没有这个页面", "帮助", null, null, false, scheme, "<p>没有这个页面。<a href=\"/\">回帮助首页</a></p>", 0, null));
        }
        return ok(body);
    }

    // ---------------------------------------------------------------- platform.180.yr

    private CompletableFuture<HttpResponse> platform(HttpRequest r, String scheme, long now) {
        sweep(now);
        Session s = session(r, now);
        boolean get = r.method.equals("GET") || r.method.equals("HEAD");
        boolean post = r.method.equals("POST");
        switch (r.path) {
            case "/":
                return done(get ? ok(index(s, scheme, r.queryParameters().get("msg"))) : notAllowed("GET"));
            case "/login":
                return done(get ? login(r, now) : notAllowed("GET"));
            case "/login/callback":
                return get ? callback(r, now) : done(notAllowed("GET"));
            case "/logout":
                return done(post ? logout(r, s) : notAllowed("POST"));
            case "/buy":
                if (get) {
                    return done(buyForm(r, s, scheme));
                }
                return post ? buy(r, s, scheme, now) : done(notAllowed("GET, POST"));
            case "/report":
                if (get) {
                    return done(reportForm(r, s, scheme));
                }
                return done(post ? report(r, s, scheme, now) : notAllowed("GET, POST"));
            case "/admin":
                return done(get ? admin(r, s, scheme) : notAllowed("GET"));
            case "/admin/remove":
                return post ? adminRemove(r, s, scheme, now) : done(notAllowed("POST"));
            case "/admin/ban":
                return post ? adminBan(r, s, scheme, now) : done(notAllowed("POST"));
            case "/admin/unban":
                return done(post ? adminUnban(r, s, scheme) : notAllowed("POST"));
            default:
                break;
        }
        Matcher m = AD_IMG.matcher(r.path);
        if (m.matches()) {
            AdBook.Ad a = book.get(Integer.parseInt(m.group(1)));
            return done(a != null && (a.state == AdBook.State.LIVE || canSee(s, a)) ? image(a) : HttpResponse.text(404, "没有这张图"));
        }
        m = AD_PAGE.matcher(r.path);
        if (m.matches()) {
            int adId = Integer.parseInt(m.group(1));
            if (m.group(2) == null) {
                return done(get ? adPage(s, scheme, adId, r.queryParameters().get("msg")) : notAllowed("GET"));
            }
            return post ? again(r, s, scheme, adId, now) : done(notAllowed("POST"));
        }
        return done(page(404, frame("没有这个页面", s, scheme, "<p>没有这个页面。<a href=\"/\">回首页</a></p>")));
    }

    private boolean isAdmin(Session s) {
        return s != null && env.isAdmin(s.player, s.name);
    }

    private boolean canSee(Session s, AdBook.Ad a) {
        return s != null && (a.owner.equals(s.player) || isAdmin(s));
    }

    private String frame(String title, Session s, String scheme, String body) {
        return NavPages.frame(title, "广告平台", s == null ? null : s.name, s == null ? null : s.csrf, isAdmin(s), scheme, body, 0, null);
    }

    private String index(Session s, String scheme, String msg) {
        StringBuilder b = new StringBuilder();
        if ("canceled".equals(msg)) {
            b.append("<p class=\"err\">没有付款。</p>");
        }
        b.append("<h1>在 180 上做广告</h1>\n");
        if (!env.payAvailable()) {
            b.append("<p class=\"err\">付款服务 pay.yr 没开，暂停投放。</p>");
        }
        long tick = env.tick();
        b.append("<table class=\"list\"><tr><th>广告位</th><th>尺寸</th><th>价格 / 游戏天</th><th>最早上线</th><th></th></tr>");
        for (AdSlot slot : AdSlot.values()) {
            long start = book.nextStart(slot, tick);
            b.append("<tr><td>").append(slot.label).append(" ×").append(book.rules().positions(slot)).append("</td><td>")
                    .append(slot.isImage() ? slot.width + "×" + slot.height : "≤ " + AdInput.MAX_TEXT + " 字").append("</td><td class=\"num\">")
                    .append(book.rules().emeraldsPerDay.get(slot)).append(" 绿宝石</td><td>")
                    .append(start < 0 ? "<span class=\"gray\">排满了</span>" : start == 0 ? "马上" : NavPages.ticks(start) + "后").append("</td><td>")
                    .append(start < 0 ? "" : "<a href=\"/buy?slot=" + slot.code + "\">投放</a>").append("</td></tr>");
        }
        b.append("</table>\n<p class=\"small gray\">按游戏天计时（一天约 20 分钟）。可以用绿宝石或悠然元付（1 绿宝石 = 2 元）。<a href=\"")
                .append(NavPages.esc(NavPages.url(scheme, HELP, "/ads"))).append("\">投放说明</a> · <a href=\"")
                .append(NavPages.esc(NavPages.url(scheme, HELP, "/rules"))).append("\">广告规则</a></p>\n");
        if (s == null) {
            b.append("<form method=\"get\" action=\"/login\"><div><input class=\"go\" type=\"submit\" value=\"用游戏角色登录\" /></div></form>\n");
        } else {
            if (book.isBanned(s.player)) {
                b.append("<p class=\"err\">你已被禁止投放广告。</p>");
            }
            b.append("<h3>我的广告</h3>");
            List<AdBook.Ad> mine = book.byOwner(s.player);
            if (mine.isEmpty()) {
                b.append("<p class=\"gray\">还没有。</p>");
            } else {
                b.append("<table class=\"list\"><tr><th>编号</th><th>广告位</th><th>内容</th><th>状态</th></tr>");
                for (AdBook.Ad a : mine) {
                    b.append("<tr><td><a href=\"/ad/").append(a.id).append("\">#").append(a.id).append("</a></td><td>").append(a.slot.label).append("</td><td>")
                            .append(NavPages.preview(a)).append("</td><td>").append(stateText(a, tick)).append("</td></tr>");
                }
                b.append("</table>");
            }
        }
        return frame("广告平台", s, scheme, b.toString());
    }

    private String stateText(AdBook.Ad a, long tick) {
        switch (a.state) {
            case LIVE:
                return "在播，还剩 " + NavPages.ticks(a.remainingTicks(tick));
            case QUEUED:
                long w = book.waitTicks(a, tick);
                return "排队中，约 " + NavPages.ticks(w) + "后上线";
            case REMOVED:
                return "已下架" + (a.refundedYuan > 0 ? "（退了 " + NavPages.yuan(a.refundedYuan) + "）" : "");
            default:
                return a.state.label;
        }
    }

    // ---------------------------------------------------------------- 投放

    private HttpResponse buyForm(HttpRequest r, Session s, String scheme) {
        AdSlot slot = AdSlot.of(r.queryParameters().get("slot"));
        if (slot == null) {
            return redirect("/");
        }
        if (s == null) {
            return redirect("/login?next=" + NavIdClient.enc("/buy?slot=" + slot.code));
        }
        return ok(buyPage(s, scheme, slot, null, "1", "", "", ""));
    }

    private String buyPage(Session s, String scheme, AdSlot slot, String error, String days, String link, String text, String image) {
        StringBuilder b = new StringBuilder();
        b.append("<h1>投放").append(slot.label).append("</h1>\n");
        if (error != null) {
            b.append("<p class=\"err\">").append(NavPages.esc(error)).append("</p>");
        }
        if (book.isBanned(s.player)) {
            b.append("<p class=\"err\">你已被禁止投放广告。</p>");
            return frame("投放", s, scheme, b.toString());
        }
        long start = book.nextStart(slot, env.tick());
        b.append("<p>").append(book.rules().emeraldsPerDay.get(slot)).append(" 绿宝石 / 游戏天 · ")
                .append(start < 0 ? "排满了" : start == 0 ? "付款后马上上线" : "约 " + NavPages.ticks(start) + "后上线").append("</p>\n");
        b.append("<form method=\"post\" action=\"/buy\"><div><input type=\"hidden\" name=\"csrf\" value=\"").append(NavPages.esc(s.csrf))
                .append("\" /><input type=\"hidden\" name=\"slot\" value=\"").append(slot.code).append("\" /></div>\n<table class=\"form\">");
        b.append("<tr><th>天数</th><td><input type=\"text\" name=\"days\" maxlength=\"3\" style=\"width: 40px;\" value=\"").append(NavPages.esc(days))
                .append("\" /> <span class=\"gray\">1–").append(book.rules().maxDays).append("</span></td></tr>");
        b.append("<tr><th>链接</th><td><input type=\"text\" name=\"link\" maxlength=\"").append(AdInput.MAX_LINK)
                .append("\" style=\"width: 300px;\" value=\"").append(NavPages.esc(link)).append("\" /></td></tr>");
        if (slot.isImage()) {
            b.append("<tr><th>说明</th><td><input type=\"text\" name=\"text\" maxlength=\"").append(AdInput.MAX_ALT)
                    .append("\" style=\"width: 200px;\" value=\"").append(NavPages.esc(text)).append("\" /> <span class=\"gray\">选填</span></td></tr>");
            b.append("<tr><th>图片</th><td><input type=\"x-yr-canvas\" name=\"image\" width=\"").append(slot.width).append("\" height=\"").append(slot.height)
                    .append("\"").append(image.isEmpty() ? "" : " value=\"" + NavPages.esc(image) + "\"").append(" /></td></tr>");
        } else {
            b.append("<tr><th>文字</th><td><input type=\"text\" name=\"text\" maxlength=\"").append(AdInput.MAX_TEXT)
                    .append("\" style=\"width: 200px;\" value=\"").append(NavPages.esc(text)).append("\" /> <span class=\"gray\">最多 ")
                    .append(AdInput.MAX_TEXT).append(" 字</span></td></tr>");
        }
        b.append("<tr><th></th><td><input class=\"go2\" type=\"submit\" value=\"去付款\" /></td></tr></table></form>\n");
        b.append("<p class=\"small gray\">付款后马上上线，先上后审。<a href=\"").append(NavPages.esc(NavPages.url(scheme, HELP, "/rules"))).append("\">广告规则</a></p>");
        return frame("投放", s, scheme, b.toString());
    }

    private CompletableFuture<HttpResponse> buy(HttpRequest r, Session s, String scheme, long now) {
        if (s == null) {
            return done(page(401, frame("没有登录", null, scheme, "<p class=\"err\">登录过期了。请先<a href=\"/login\">登录</a>，再点浏览器的“后退”回来提交（填的内容还在）。</p>")));
        }
        Map<String, String> f = r.form();
        if (!s.csrf.equals(f.get("csrf"))) {
            return done(page(403, frame("页面过期了", s, scheme, "<p class=\"err\">页面过期了。点浏览器的“后退”回去，刷新后再提交。</p>")));
        }
        AdSlot slot = AdSlot.of(f.get("slot"));
        if (slot == null) {
            return done(redirect("/"));
        }
        String days = nz(f.get("days")), link = nz(f.get("link")), text = nz(f.get("text")), image = nz(f.get("image"));
        // 画的图原样带回表单（看得懂的才带，带不回的不会是画板交上来的）
        String echo = DyePng.fromDataUrl(image) != null && image.length() < 200_000 ? image : "";
        if (!env.payAvailable()) {
            return done(page(503, buyPage(s, scheme, slot, "付款服务 pay.yr 没开，暂停投放。", days, link, text, echo)));
        }
        if (!buyLimit.tryAcquire(s.player.toString(), now)) {
            return done(page(429, buyPage(s, scheme, slot, "提交太快了，过一会再试。", days, link, text, echo)));
        }
        int d;
        String okLink;
        String okText;
        byte[] png = null;
        try {
            d = AdInput.days(days, book.rules().maxDays);
            okLink = AdInput.link(link);
            if (slot.isImage()) {
                okText = AdInput.text(text, AdInput.MAX_ALT, false, "说明");
                png = AdInput.image(image, slot);
            } else {
                okText = AdInput.text(text, AdInput.MAX_TEXT, true, "文字");
            }
        } catch (AdInput.Bad e) {
            return done(page(422, buyPage(s, scheme, slot, e.getMessage(), days, link, text, echo)));
        }
        return order(s, scheme, slot, d, okText, okLink, png, now, p -> page(p.status, buyPage(s, scheme, slot, p.message, days, link, text, echo)));
    }

    /** 下单失败时给什么页面 */
    interface FailPage {
        HttpResponse page(Failure f);
    }

    static final class Failure {
        final int status;
        final String message;

        Failure(int status, String message) {
            this.status = status;
            this.message = message;
        }
    }

    /** 记待付款、存图、在主线程开收款单，成功时 303 到付款页 */
    private CompletableFuture<HttpResponse> order(Session s, String scheme, AdSlot slot, int days, String text, String link, byte[] png, long now, FailPage fail) {
        AdBook.Ad ad;
        try {
            ad = book.draft(slot, s.player, s.name, days, text, link, env.tick(), now);
        } catch (AdBook.AdException e) {
            return done(fail.page(new Failure(409, e.getMessage())));
        }
        if (png != null) {
            try {
                store.putImage(ad.id, png);
            } catch (IOException e) {
                LOG.error("180.yr：存广告 #{} 的图失败", ad.id, e);
                book.cancelDraft(ad, now);
                return done(fail.page(new Failure(500, "存图失败，请稍后再试。")));
            }
        }
        return env.openOrder(ad, scheme).handle((url, e) -> {
            if (url == null) {
                Throwable c = e instanceof CompletionException && e.getCause() != null ? e.getCause() : e;
                book.cancelDraft(ad, System.currentTimeMillis());
                LOG.warn("180.yr：给广告 #{} 开收款单失败：{}", ad.id, c == null ? "?" : c.toString());
                return fail.page(new Failure(502, "开不了收款单：" + (c == null ? "?" : c.getMessage())));
            }
            LOG.info("180.yr：{} 下单广告 #{}（{} {} 天）", s.name, ad.id, slot.code, days);
            return redirect(url);
        });
    }

    private HttpResponse adPage(Session s, String scheme, int adId, String msg) {
        AdBook.Ad a = book.get(adId);
        if (s == null) {
            return redirect("/login?next=" + NavIdClient.enc("/ad/" + adId));
        }
        if (a == null || !canSee(s, a)) {
            return page(404, frame("没有这条广告", s, scheme, "<p>没有这条广告。<a href=\"/\">回首页</a></p>"));
        }
        long tick = env.tick();
        StringBuilder b = new StringBuilder();
        String status = a.state == AdBook.State.DRAFT && a.orderId != null ? env.orderStatus(a.orderId) : null;
        boolean settling = "paid".equals(status) || "paying".equals(status);
        if ("paid".equals(msg) || settling) {
            b.append(a.state == AdBook.State.DRAFT ? "<p class=\"ok\">已付款，正在上线……</p>" : "<p class=\"ok\">付款成功。</p>");
        } else if ("again".equals(msg)) {
            b.append("<p class=\"ok\">已重新下单。</p>");
        }
        b.append("<h1>广告 #").append(a.id).append("</h1>\n<table class=\"list\">");
        b.append("<tr><th style=\"width: 70px;\">广告位</th><td>").append(a.slot.label).append(" ").append(a.position + 1).append(" 号</td></tr>");
        b.append("<tr><th>内容</th><td>").append(NavPages.preview(a)).append(a.slot.isImage() && !a.text.isEmpty() ? "<br />" + NavPages.esc(a.text) : "")
                .append("</td></tr>");
        b.append("<tr><th>链接</th><td>").append(NavPages.esc(a.link)).append("</td></tr>");
        b.append("<tr><th>时长</th><td>").append(a.days).append(" 游戏天 · ").append(NavPages.yuan(a.priceYuan)).append("</td></tr>");
        b.append("<tr><th>状态</th><td>").append(stateText(a, tick)).append("</td></tr>");
        if (a.state == AdBook.State.REMOVED && a.removeReason != null && !a.removeReason.isEmpty()) {
            b.append("<tr><th>下架理由</th><td>").append(NavPages.esc(a.removeReason)).append("</td></tr>");
        }
        if (!a.reports.isEmpty() && isAdmin(s)) {
            b.append("<tr><th>举报</th><td>").append(a.reports.size()).append(" 次</td></tr>");
        }
        b.append("</table>\n");
        String pay = a.state == AdBook.State.DRAFT && "open".equals(status) ? env.checkoutUrl(a.orderId) : null;
        if (pay != null) {
            b.append("<p><a href=\"").append(NavPages.esc(pay)).append("\"><b>去付款</b></a></p>");
        }
        if (a.state == AdBook.State.CANCELED && a.owner.equals(s.player)) {
            b.append("<form method=\"post\" action=\"/ad/").append(a.id).append("/again\"><div><input type=\"hidden\" name=\"csrf\" value=\"").append(NavPages.esc(s.csrf))
                    .append("\" /><input class=\"go2\" type=\"submit\" value=\"重新下单\" /> <span class=\"gray\">内容不变</span></div></form>");
        }
        String body = b.toString();
        if (settling) {
            return ok(NavPages.frame("广告 #" + a.id, "广告平台", s.name, s.csrf, isAdmin(s), scheme, body, 2, "/ad/" + a.id));
        }
        return ok(frame("广告 #" + a.id, s, scheme, body));
    }

    private CompletableFuture<HttpResponse> again(HttpRequest r, Session s, String scheme, int adId, long now) {
        if (s == null || !s.csrf.equals(r.form().get("csrf"))) {
            return done(page(403, frame("页面过期了", s, scheme, "<p class=\"err\">页面过期了，请回去刷新后再试。</p>")));
        }
        AdBook.Ad a = book.get(adId);
        if (a == null || !a.owner.equals(s.player) || a.state != AdBook.State.CANCELED) {
            return done(page(404, frame("不能重新下单", s, scheme, "<p class=\"err\">这条广告不能重新下单。</p>")));
        }
        byte[] png = null;
        if (a.slot.isImage()) {
            png = store.image(a.id);
            if (png == null) {
                return done(page(410, frame("不能重新下单", s, scheme, "<p class=\"err\">图找不到了，请重新投放。</p>")));
            }
        }
        if (!buyLimit.tryAcquire(s.player.toString(), now)) {
            return done(page(429, frame("太快了", s, scheme, "<p class=\"err\">提交太快了，过一会再试。</p>")));
        }
        return order(s, scheme, a.slot, a.days, a.text, a.link, png, now,
                p -> page(p.status, frame("不能重新下单", s, scheme, "<p class=\"err\">" + NavPages.esc(p.message) + "</p>")));
    }

    // ---------------------------------------------------------------- 举报

    private HttpResponse reportForm(HttpRequest r, Session s, String scheme) {
        AdBook.Ad a = parseAd(r.queryParameters().get("ad"));
        if (a == null || (a.state != AdBook.State.LIVE && !canSee(s, a))) {
            return page(404, frame("举报", s, scheme, "<p>这条广告已经不在了。</p>"));
        }
        StringBuilder b = new StringBuilder("<h1>举报广告 #").append(a.id).append("</h1>\n<p>").append(NavPages.preview(a)).append("</p>")
                .append("<p class=\"gray\">链接到 ").append(NavPages.esc(NavPages.domainOf(a.link))).append("</p>\n")
                .append("<form method=\"post\" action=\"/report\"><div><input type=\"hidden\" name=\"ad\" value=\"").append(a.id).append("\" /></div>")
                .append("<table class=\"form\"><tr><th>原因</th><td><select name=\"reason\">");
        for (String reason : REASONS) {
            b.append("<option>").append(NavPages.esc(reason)).append("</option>");
        }
        b.append("</select></td></tr><tr><th>补充</th><td><input type=\"text\" name=\"detail\" maxlength=\"").append(AdInput.MAX_REASON)
                .append("\" style=\"width: 260px;\" /></td></tr><tr><th></th><td><input class=\"go\" type=\"submit\" value=\"举报\" /></td></tr></table></form>");
        return ok(frame("举报", s, scheme, b.toString()));
    }

    static final String[] REASONS = {"违法违规", "色情低俗", "骗人、虚假", "恶意链接", "其他"};

    private HttpResponse report(HttpRequest r, Session s, String scheme, long now) {
        Map<String, String> f = r.form();
        AdBook.Ad a = parseAd(f.get("ad"));
        if (a == null || a.state != AdBook.State.LIVE && a.state != AdBook.State.QUEUED) {
            return page(404, frame("举报", s, scheme, "<p>这条广告已经不在了。</p>"));
        }
        String reason = nz(f.get("reason"));
        boolean known = false;
        for (String x : REASONS) {
            known |= x.equals(reason);
        }
        String detail;
        try {
            detail = AdInput.text(f.get("detail"), AdInput.MAX_REASON, false, "补充");
        } catch (AdInput.Bad e) {
            return page(422, frame("举报", s, scheme, "<p class=\"err\">" + NavPages.esc(e.getMessage()) + "（点“后退”修改）</p>"));
        }
        String source = String.valueOf(r.remoteAddress);
        String key = source + " " + a.id;
        if (reported.contains(key)) {
            return ok(frame("举报", s, scheme, "<p class=\"ok\">已经举报过了，谢谢。</p><p><a href=\"" + NavPages.esc(NavPages.url(scheme, NAV, "/")) + "\">回 180</a></p>"));
        }
        if (!reportLimit.tryAcquire(source, now)) {
            return page(429, frame("举报", s, scheme, "<p class=\"err\">举报太频繁了，过一会再试。</p>"));
        }
        String who = env.identify(r.remoteAddress);
        String by = (who == null ? "" : who + " ") + "@" + source;
        if (book.report(a, (known ? reason : "其他") + (detail.isEmpty() ? "" : "：" + detail), by, now)) {
            if (reported.size() > 100_000) {
                reported.clear();
            }
            reported.add(key);
            LOG.info("180.yr：广告 #{} 被举报（{}）：{}", a.id, by, reason);
        }
        return ok(frame("举报", s, scheme, "<p class=\"ok\">收到了，管理员会处理。</p><p><a href=\"" + NavPages.esc(NavPages.url(scheme, NAV, "/")) + "\">回 180</a></p>"));
    }

    private AdBook.Ad parseAd(String s) {
        if (s == null || !s.matches("[0-9]{1,9}")) {
            return null;
        }
        return book.get(Integer.parseInt(s));
    }

    // ---------------------------------------------------------------- 管理

    private HttpResponse admin(HttpRequest r, Session s, String scheme) {
        if (s == null) {
            return redirect("/login?next=/admin");
        }
        if (!isAdmin(s)) {
            return page(403, frame("管理", s, scheme, "<p class=\"err\">你不是管理员。</p>"));
        }
        return ok(adminPage(s, scheme, r.queryParameters().get("msg")));
    }

    String adminPage(Session s, String scheme, String msg) {
        long tick = env.tick();
        StringBuilder b = new StringBuilder();
        if (msg != null && msg.length() < 200) {
            b.append("<p class=\"ok\">").append(NavPages.esc(msg)).append("</p>");
        }
        b.append("<h1>管理</h1>\n<h3>在播与排队</h3>");
        List<AdBook.Ad> active = new ArrayList<>();
        for (AdBook.Ad a : book.all()) {
            if (a.state == AdBook.State.LIVE || a.state == AdBook.State.QUEUED) {
                active.add(a);
            }
        }
        active.sort((x, y) -> x.reports.size() != y.reports.size() ? y.reports.size() - x.reports.size() : x.id - y.id);
        if (active.isEmpty()) {
            b.append("<p class=\"gray\">没有。</p>");
        }
        for (AdBook.Ad a : active) {
            long unused = book.unusedYuan(a, tick);
            b.append("<table class=\"list\"><tr><th style=\"width: 150px;\">#").append(a.id).append(" ").append(a.slot.label).append(" ").append(a.position + 1)
                    .append(" 号</th><th>").append(NavPages.esc(a.ownerName)).append(" · ").append(stateText(a, tick)).append("</th></tr>");
            b.append("<tr><td>").append(NavPages.preview(a)).append("</td><td>").append(NavPages.esc(a.link)).append("<br />")
                    .append(a.slot.isImage() ? NavPages.esc(a.text) : "").append("<br /><span class=\"gray\">").append(a.days).append(" 天 · ")
                    .append(NavPages.yuan(a.priceYuan)).append("</span>");
            if (!a.reports.isEmpty()) {
                b.append("<br /><b style=\"color: #b02e26;\">举报 ").append(a.reports.size()).append(" 次</b>");
                int from = Math.max(0, a.reports.size() - 3);
                for (int i = a.reports.size() - 1; i >= from; i--) {
                    AdBook.Report rp = a.reports.get(i);
                    b.append("<br /><span class=\"small\">").append(NavPages.time(rp.time)).append(" ").append(NavPages.esc(rp.reason))
                            .append(" <span class=\"gray\">").append(NavPages.esc(rp.by)).append("</span></span>");
                }
            }
            b.append("</td></tr><tr><td colspan=\"2\"><form method=\"post\" action=\"/admin/remove\"><div><input type=\"hidden\" name=\"csrf\" value=\"")
                    .append(NavPages.esc(s.csrf)).append("\" /><input type=\"hidden\" name=\"ad\" value=\"").append(a.id).append("\" />")
                    .append("<select name=\"refund\"><option value=\"none\">不退</option><option value=\"unused\">退没播的 ").append(NavPages.yuan(unused))
                    .append("</option><option value=\"full\">全退 ").append(NavPages.yuan(a.priceYuan)).append("</option></select> ")
                    .append(" 理由 <input type=\"text\" name=\"reason\" maxlength=\"").append(AdInput.MAX_REASON).append("\" style=\"width: 120px;\" /> ")
                    .append("<input type=\"submit\" value=\"下架\" /></div></form>")
                    .append("<form method=\"post\" action=\"/admin/ban\"><div><input type=\"hidden\" name=\"csrf\" value=\"").append(NavPages.esc(s.csrf))
                    .append("\" /><input type=\"hidden\" name=\"ad\" value=\"").append(a.id).append("\" />")
                    .append("理由 <input type=\"text\" name=\"reason\" maxlength=\"").append(AdInput.MAX_REASON).append("\" style=\"width: 120px;\" /> ")
                    .append("<input type=\"checkbox\" name=\"remove_all\" value=\"1\" /> 同时下架他的广告（退没播的） ")
                    .append("<input type=\"submit\" value=\"封禁投放者\" /></div></form></td></tr></table>\n");
        }
        b.append("<h3>封禁</h3>");
        List<AdBook.Ban> bans = book.bans();
        if (bans.isEmpty()) {
            b.append("<p class=\"gray\">没有。</p>");
        } else {
            b.append("<table class=\"list\"><tr><th>玩家</th><th>理由</th><th>时间</th><th></th></tr>");
            for (AdBook.Ban ban : bans) {
                b.append("<tr><td>").append(NavPages.esc(ban.name)).append("</td><td>").append(NavPages.esc(ban.reason)).append(" <span class=\"gray\">（")
                        .append(NavPages.esc(ban.by)).append("）</span></td><td>").append(NavPages.time(ban.time)).append("</td><td>")
                        .append("<form method=\"post\" action=\"/admin/unban\"><div><input type=\"hidden\" name=\"csrf\" value=\"").append(NavPages.esc(s.csrf))
                        .append("\" /><input type=\"hidden\" name=\"player\" value=\"").append(ban.player).append("\" /><input type=\"submit\" value=\"解封\" /></div></form>")
                        .append("</td></tr>");
            }
            b.append("</table>");
        }
        return frame("管理", s, scheme, b.toString());
    }

    /** 管理操作的前置检查；不合格返回错误页 */
    private HttpResponse adminCheck(Session s, Map<String, String> f, String scheme) {
        if (s == null) {
            return redirect("/login?next=/admin");
        }
        if (!isAdmin(s)) {
            return page(403, frame("管理", s, scheme, "<p class=\"err\">你不是管理员。</p>"));
        }
        if (!s.csrf.equals(f.get("csrf"))) {
            return page(403, frame("管理", s, scheme, "<p class=\"err\">页面过期了，请回<a href=\"/admin\">管理页</a>刷新后再操作。</p>"));
        }
        return null;
    }

    private CompletableFuture<HttpResponse> adminRemove(HttpRequest r, Session s, String scheme, long now) {
        Map<String, String> f = r.form();
        HttpResponse bad = adminCheck(s, f, scheme);
        if (bad != null) {
            return done(bad);
        }
        AdBook.Ad a = parseAd(f.get("ad"));
        if (a == null || (a.state != AdBook.State.LIVE && a.state != AdBook.State.QUEUED)) {
            return done(redirect("/admin?msg=" + NavIdClient.enc("这条广告已经不在了")));
        }
        String reason;
        try {
            reason = AdInput.text(f.get("reason"), AdInput.MAX_REASON, false, "理由");
        } catch (AdInput.Bad e) {
            return done(page(422, frame("管理", s, scheme, "<p class=\"err\">" + NavPages.esc(e.getMessage()) + "</p>")));
        }
        String mode = nz(f.get("refund"));
        return takeDown(a, s, mode, reason).handle((done, e) -> {
            if (e != null) {
                Throwable c = e instanceof CompletionException && e.getCause() != null ? e.getCause() : e;
                return page(502, frame("管理", s, scheme, "<p class=\"err\">没有下架：退款失败（" + NavPages.esc(c.getMessage()) + "）。可以选“不退”再下架。</p>"
                        + "<p><a href=\"/admin\">回管理页</a></p>"));
            }
            return redirect("/admin?msg=" + NavIdClient.enc(done));
        });
    }

    /** 先退款（在主线程上），退好了再下架；完成时给一句结果 */
    private CompletableFuture<String> takeDown(AdBook.Ad a, Session s, String mode, String reason) {
        long tick = env.tick();
        long yuan = "full".equals(mode) ? a.priceYuan : "unused".equals(mode) ? book.unusedYuan(a, tick) : 0;
        String note = "180 广告 #" + a.id + " 下架退款" + (reason.isEmpty() ? "" : "：" + reason);
        return env.refund(a, yuan, note).thenApply(v -> {
            boolean removed = book.remove(a, s.name, reason, yuan, env.tick(), System.currentTimeMillis());
            LOG.info("180.yr：{} 下架广告 #{}（{}，退 {} 元）：{}", s.name, a.id, a.ownerName, yuan, reason);
            return removed ? "已下架 #" + a.id + (yuan > 0 ? "，退了 " + NavPages.yuan(yuan) : "") : "#" + a.id + " 已经不在了";
        });
    }

    private CompletableFuture<HttpResponse> adminBan(HttpRequest r, Session s, String scheme, long now) {
        Map<String, String> f = r.form();
        HttpResponse bad = adminCheck(s, f, scheme);
        if (bad != null) {
            return done(bad);
        }
        AdBook.Ad a = parseAd(f.get("ad"));
        if (a == null) {
            return done(redirect("/admin"));
        }
        String reason;
        try {
            reason = AdInput.text(f.get("reason"), AdInput.MAX_REASON, false, "理由");
        } catch (AdInput.Bad e) {
            return done(page(422, frame("管理", s, scheme, "<p class=\"err\">" + NavPages.esc(e.getMessage()) + "</p>")));
        }
        book.ban(a.owner, a.ownerName, reason, s.name, now);
        LOG.info("180.yr：{} 封禁了投放者 {}：{}", s.name, a.ownerName, reason);
        if (!"1".equals(f.get("remove_all"))) {
            return done(redirect("/admin?msg=" + NavIdClient.enc("已封禁 " + a.ownerName)));
        }
        CompletableFuture<Integer> chain = CompletableFuture.completedFuture(0);
        for (AdBook.Ad x : book.byOwner(a.owner)) {
            if (x.state == AdBook.State.LIVE || x.state == AdBook.State.QUEUED) {
                chain = chain.thenCompose(n -> takeDown(x, s, "unused", reason.isEmpty() ? "封禁" : reason).thenApply(m -> n + 1));
            }
        }
        return chain.handle((n, e) -> {
            if (e != null) {
                Throwable c = e instanceof CompletionException && e.getCause() != null ? e.getCause() : e;
                return redirect("/admin?msg=" + NavIdClient.enc("已封禁 " + a.ownerName + "，但有广告没下架：" + c.getMessage()));
            }
            return redirect("/admin?msg=" + NavIdClient.enc("已封禁 " + a.ownerName + "，下架 " + n + " 条"));
        });
    }

    private HttpResponse adminUnban(HttpRequest r, Session s, String scheme) {
        Map<String, String> f = r.form();
        HttpResponse bad = adminCheck(s, f, scheme);
        if (bad != null) {
            return bad;
        }
        try {
            UUID p = UUID.fromString(nz(f.get("player")));
            AdBook.Ban ban = book.ban(p);
            if (book.unban(p)) {
                LOG.info("180.yr：{} 解封了投放者 {}", s.name, ban == null ? p : ban.name);
                return redirect("/admin?msg=" + NavIdClient.enc("已解封 " + (ban == null ? p.toString() : ban.name)));
            }
        } catch (IllegalArgumentException ignored) {
            // 写法不对：当作没有
        }
        return redirect("/admin");
    }

    // ---------------------------------------------------------------- 登录（同 pay.yr）

    private HttpResponse login(HttpRequest r, long now) {
        String next = safeNext(r.queryParameters().get("next"));
        String scheme = r.secure ? "https" : "http";
        if (!env.idAvailable()) {
            return page(503, frame("不能登录", null, scheme, "<p class=\"err\">这个服务器没开游戏角色一键登录（" + NavPages.esc(id.domain()) + "），暂时不能登录。</p>"));
        }
        if (!loginLimit.tryAcquire(String.valueOf(r.remoteAddress), now)) {
            return page(429, frame("太快了", null, scheme, "<p class=\"err\">登录太频繁，请稍后再试。</p>"));
        }
        if (logins.size() >= MAX_LOGINS) {
            return page(503, frame("太忙了", null, scheme, "<p class=\"err\">现在太忙，请稍后再试。</p>"));
        }
        String state = random(24);
        String verifier = random(48);
        logins.put(state, new Login(verifier, scheme, next, r.remoteAddress, now));
        return redirect(id.authorizeUrl(scheme, state, verifier));
    }

    private CompletableFuture<HttpResponse> callback(HttpRequest r, long now) {
        Map<String, String> q = r.queryParameters();
        String scheme = r.secure ? "https" : "http";
        Login l = q.get("state") == null ? null : logins.remove(q.get("state"));
        if (l == null || now - l.created > LOGIN_TTL) {
            return done(page(400, frame("登录失败", null, scheme, "<p class=\"err\">这次登录过期了，请重新登录。</p><p><a href=\"/login\">重新登录</a></p>")));
        }
        if (!l.source.equals(r.remoteAddress)) {
            return done(page(403, frame("登录失败", null, scheme, "<p class=\"err\">要在发起登录的那台电脑上完成。</p>")));
        }
        if (!id.issuerOk(q.get("iss"))) {
            return done(page(400, frame("登录失败", null, scheme, "<p class=\"err\">登录服务对不上。</p>")));
        }
        String again = "<p><a href=\"/login?next=" + NavPages.esc(NavIdClient.enc(l.next)) + "\">重新登录</a></p>";
        String err = q.get("error");
        if (err != null) {
            return done(page(400, frame("登录失败", null, scheme, "<p class=\"err\">" + NavPages.esc(loginError(err, q.get("error_description"))) + "</p>" + again)));
        }
        String code = q.get("code");
        if (code == null || code.isEmpty()) {
            return done(page(400, frame("登录失败", null, scheme, "<p class=\"err\">登录服务没给授权码。</p>" + again)));
        }
        return id.exchange(l.scheme, code, l.verifier).handle((who, e) -> {
            if (who == null) {
                Throwable c = e instanceof CompletionException && e.getCause() != null ? e.getCause() : e;
                LOG.warn("180.yr：向 {} 兑换授权码失败：{}", id.domain(), c == null ? "?" : c.toString());
                return page(502, frame("登录失败", null, scheme, "<p class=\"err\">登录服务没响应，请重试。</p>" + again));
            }
            if (sessions.size() >= MAX_SESSIONS) {
                return page(503, frame("太忙了", null, scheme, "<p class=\"err\">现在太忙，请稍后再试。</p>"));
            }
            Session s = new Session(random(32), who.uuid, who.name, random(24), System.currentTimeMillis());
            sessions.put(s.id, s);
            LOG.info("180.yr：{} 登录了广告平台（来源 {}）", who.name, r.remoteAddress);
            return redirect(l.next).withHeader("Set-Cookie", cookie(s.id, l.scheme.equals("https"), -1));
        });
    }

    static String loginError(String error, String description) {
        switch (error) {
            case "login_unavailable":
                return "no_operator".equals(description) ? "这台电脑现在没人操作。" : "这台电脑不能用游戏角色登录。";
            case "access_denied":
                if ("timeout".equals(description)) {
                    return "确认框超时了。";
                }
                if ("operator_changed".equals(description)) {
                    return "确认时离开了电脑或换了人。";
                }
                return "你拒绝了登录。";
            case "temporarily_unavailable":
                return "busy".equals(description) ? "你已经有一个确认框开着。" : "登录服务暂时不可用。";
            default:
                return "登录失败（" + error + "）。";
        }
    }

    private HttpResponse logout(HttpRequest r, Session s) {
        if (s != null && s.csrf.equals(r.form().get("csrf"))) {
            sessions.remove(s.id);
        }
        return redirect("/").withHeader("Set-Cookie", cookie("", r.secure, 0));
    }

    static String cookie(String value, boolean secure, int maxAge) {
        return COOKIE + "=" + value + "; Path=/; HttpOnly; SameSite=Lax" + (secure ? "; Secure" : "") + (maxAge >= 0 ? "; Max-Age=" + maxAge : "");
    }

    Session session(HttpRequest r, long now) {
        String c = r.header("Cookie");
        if (c == null) {
            return null;
        }
        for (String part : c.split(";")) {
            String p = part.trim();
            if (p.startsWith(COOKIE + "=")) {
                Session s = sessions.get(p.substring(COOKIE.length() + 1));
                if (s == null) {
                    return null;
                }
                if (now - s.lastSeen > idleMs || now - s.created > SESSION_MAX) {
                    sessions.remove(s.id);
                    return null;
                }
                s.lastSeen = now;
                return s;
            }
        }
        return null;
    }

    /** 测试用：直接放一个登录 */
    Session addSession(UUID player, String name, long now) {
        Session s = new Session(random(32), player, name, random(24), now);
        sessions.put(s.id, s);
        return s;
    }

    /** 登录后只跳回平台自己的路径 */
    static String safeNext(String n) {
        if (n == null || !n.startsWith("/") || n.startsWith("//") || n.length() > 200 || !n.matches("[A-Za-z0-9/_.?=&%-]*")) {
            return "/";
        }
        return n;
    }

    // ---------------------------------------------------------------- 小工具

    private void sweep(long now) {
        if (random.nextInt(32) != 0) {
            return;
        }
        logins.values().removeIf(l -> now - l.created > LOGIN_TTL);
        sessions.values().removeIf(s -> now - s.lastSeen > idleMs || now - s.created > SESSION_MAX);
    }

    private String random(int bytes) {
        byte[] b = new byte[bytes];
        random.nextBytes(b);
        return Base64.getUrlEncoder().withoutPadding().encodeToString(b);
    }

    private static String nz(String s) {
        return s == null ? "" : s;
    }

    static HttpResponse ok(String page) {
        return HttpResponse.xhtml(200, page).withHeader("Cache-Control", "no-store");
    }

    static HttpResponse page(int status, String page) {
        return HttpResponse.xhtml(status, page).withHeader("Cache-Control", "no-store");
    }

    static HttpResponse redirect(String to) {
        return HttpResponse.of(303, "text/plain; charset=utf-8", new byte[0]).withHeader("Location", to).withHeader("Cache-Control", "no-store");
    }

    private static HttpResponse notAllowed(String allow) {
        return HttpResponse.text(405, "method not allowed").withHeader("Allow", allow);
    }

    private static <T> CompletableFuture<T> done(T v) {
        return CompletableFuture.completedFuture(v);
    }

    /** 测试用：举报过的来源 */
    Set<String> reportedKeys() {
        return new HashSet<>(reported);
    }
}
