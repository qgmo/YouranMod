package moe.qingu.youran.server.net.pay;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import moe.qingu.youran.api.network.HostContext;
import moe.qingu.youran.api.network.Ipv6Address;
import moe.qingu.youran.api.network.SystemConfirm;
import moe.qingu.youran.api.network.http.HttpRequest;
import moe.qingu.youran.api.network.http.HttpResponse;
import moe.qingu.youran.api.network.http.HttpServer;
import moe.qingu.youran.api.network.stack.DnsResolver;
import moe.qingu.youran.api.pay.AccountRef;
import moe.qingu.youran.api.pay.Amount;
import moe.qingu.youran.api.pay.Currency;
import moe.qingu.youran.api.pay.OrderInfo;
import moe.qingu.youran.api.pay.OrderSpec;
import moe.qingu.youran.api.pay.PayException;
import moe.qingu.youran.api.pay.PayItem;
import moe.qingu.youran.api.pay.PaymentInfo;
import moe.qingu.youran.server.net.service.captcha.SourceLimiter;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.util.ArrayList;
import java.util.Base64;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.BooleanSupplier;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static moe.qingu.youran.server.net.pay.PayPages.esc;
import static moe.qingu.youran.server.net.pay.PayPages.link;

/**
 * pay.yr 的网页与 HTTP 接口（在网络线程上；要动游戏的交给 {@link PayServer} 的主线程方法）。
 * <pre>
 *   GET  /                      介绍与接口说明
 *   GET  /c/&lt;单号&gt;             付款页（没登录时先登录）
 *   POST /c/&lt;单号&gt;/pay         付款：弹系统确认框，跳到 /op/&lt;号&gt; 等结果
 *   GET  /c/&lt;单号&gt;/cancel      不付了，回网站
 *   GET  /login、/login/callback  用 id.yr 游戏角色一键登录（OAuth 授权码 + PKCE）
 *   POST /logout
 *   GET  /op/&lt;号&gt;              等确认框与结果（自动刷新）
 *   GET  /account               账户：余额、提现、收到的付款、收支记录、密钥
 *   POST /account/withdraw、/account/key；GET、POST /account/refund/&lt;付款号&gt;
 *   POST /api/v1/orders；GET /api/v1/orders/&lt;单号&gt;；POST /api/v1/orders/&lt;单号&gt;/cancel；GET /api/v1/balance；GET /api/v1/payments/&lt;号&gt;
 * </pre>
 * 登录状态是 Cookie {@code yrpay}（跟着电脑走：同一台电脑的下一个人看得到，所以空闲 {@code sessionIdleMinutes} 分钟就失效）。
 * 付款、提现、退款、重置密钥都要在系统确认框里确认。确认框弹给这台电脑现在的操作者，不必是登录的角色（服主 11-15：另一个玩家坐到
 * 已登录的电脑前动钱是允许的，“相当于老婆帮你买东西”）；记账一律照登录的账户，确认框里写明是谁的账户。
 */
final class PayService {
    static final int PORT = 80;
    static final int TLS_PORT = 443;
    static final String COOKIE = "yrpay";
    static final int MAX_SESSIONS = 4096;
    static final int MAX_LOGINS = 1024;
    static final int MAX_OPS = 4096;
    static final long LOGIN_TTL = 5 * 60_000;
    static final long OP_TTL = 30 * 60_000;
    static final long SESSION_MAX = 2 * 3600_000;
    static final Pattern ORDER_PATH = Pattern.compile("/c/(ord_[a-z0-9]{16})(/pay|/cancel)?");
    static final Pattern OP_PATH = Pattern.compile("/op/(op_[a-z0-9]{16})");
    static final Pattern REFUND_PATH = Pattern.compile("/account/refund/(pay_[a-z0-9]{16})");
    static final Pattern API_ORDER = Pattern.compile("/api/v1/orders/(ord_[a-z0-9]{16})(/cancel)?");
    static final Pattern API_PAYMENT = Pattern.compile("/api/v1/payments/(pay_[a-z0-9]{16})");
    static final Pattern COUNT = Pattern.compile("[0-9]{1,9}");

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

    enum OpKind {
        PAY, WITHDRAW, REFUND, KEY
    }

    enum OpState {
        PREPARING, CONFIRMING, PROCESSING, DONE, FAILED
    }

    /** 一次要确认的操作（付款、提现……）：页面按它显示“请确认”、结果 */
    static final class Op {
        final String id;
        final String sessionId;
        final OpKind kind;
        final String orderId;
        final long created;
        volatile OpState state = OpState.PREPARING;
        volatile String message;
        volatile String txId;
        /** 新密钥（只显示一次） */
        volatile String secret;

        Op(String id, String sessionId, OpKind kind, String orderId, long now) {
            this.id = id;
            this.sessionId = sessionId;
            this.kind = kind;
            this.orderId = orderId;
            this.created = now;
        }

        void fail(String m) {
            message = m;
            state = OpState.FAILED;
        }

        void done(String m) {
            message = m;
            state = OpState.DONE;
        }
    }

    private final PayServer core;
    private final HostContext ctx;
    private final IdClient id;
    private final BooleanSupplier idAvailable;
    private final SecureRandom random = new SecureRandom();
    private final Map<String, Session> sessions = new ConcurrentHashMap<>();
    private final Map<String, Login> logins = new ConcurrentHashMap<>();
    private final Map<String, Op> ops = new ConcurrentHashMap<>();
    /** 收款单 → 正在付它的操作（同一张单同一时间只能一处在付） */
    private final Map<String, String> orderLocks = new ConcurrentHashMap<>();
    private final SourceLimiter pageLimit = new SourceLimiter(8, 40, 8192);
    private final SourceLimiter loginLimit = new SourceLimiter(0.5, 10, 8192);
    private final SourceLimiter apiLimit;
    private final SourceLimiter badKeyLimit = new SourceLimiter(0.2, 10, 8192);

    PayService(PayServer core, HostContext ctx, Ipv6Address resolver, BooleanSupplier idAvailable) {
        this.core = core;
        this.ctx = ctx;
        this.id = new IdClient(ctx.stack(), new DnsResolver(ctx.stack(), resolver), PayConfig.idDomain.trim().toLowerCase(java.util.Locale.ROOT),
                ctx.tls() != null);
        this.idAvailable = idAvailable;
        this.apiLimit = new SourceLimiter(PayConfig.apiPerMinute / 60.0, Math.max(10, PayConfig.apiPerMinute / 4), 8192);
    }

    void start() {
        HttpServer.Options o = new HttpServer.Options().maxBodyBytes(16 * 1024).handlerTimeoutMillis(20_000)
                .errorPages(s -> HttpResponse.xhtml(s, PayPages.message("出错了", null, null, "err", "HTTP " + s + " " + HttpResponse.reasonPhrase(s), null)));
        HttpServer.listen(ctx.stack(), PORT, null, o, this::handle);
        PayPages.docsBase = (ctx.tls() != null ? "https://" : "http://") + moe.qingu.youran.api.pay.PayApi.DOCS_DOMAIN;
        if (ctx.tls() != null) {
            HttpServer.listen(ctx.stack(), TLS_PORT, ctx.tls(), o, this::handle);
            core.setHttps(true);
        }
    }

    // ---------------------------------------------------------------- 分发

    CompletableFuture<HttpResponse> handle(HttpRequest r) {
        long now = System.currentTimeMillis();
        try {
            if (moe.qingu.youran.api.pay.PayApi.DOCS_DOMAIN.equals(host(r))) {
                return done(docs(r, now));
            }
            if (r.path.startsWith("/api/")) {
                return api(r, now);
            }
            if (!pageLimit.tryAcquire(String.valueOf(r.remoteAddress), now)) {
                return done(page(429, PayPages.message("太快了", null, null, "err", "请求太频繁，请稍后再试", null)));
            }
            sweep(now);
            Session s = session(r, now);
            boolean get = r.method.equals("GET") || r.method.equals("HEAD");
            boolean post = r.method.equals("POST");
            if (r.path.equals("/")) {
                return done(get ? ok(PayPages.index(nameOf(s), csrfOf(s), idAvailable.getAsBoolean(), id.domain(), core.https())) : notAllowed("GET"));
            }
            Matcher m = ORDER_PATH.matcher(r.path);
            if (m.matches()) {
                String orderId = m.group(1);
                if (m.group(2) == null) {
                    return get ? checkout(r, s, orderId, null, now) : done(notAllowed("GET"));
                }
                if (m.group(2).equals("/pay")) {
                    return done(post ? pay(r, s, orderId, now) : notAllowed("POST"));
                }
                return done(get ? cancel(orderId, now) : notAllowed("GET"));
            }
            switch (r.path) {
                case "/login":
                    return done(get ? login(r, now) : notAllowed("GET"));
                case "/login/callback":
                    return get ? callback(r, now) : done(notAllowed("GET"));
                case "/logout":
                    return done(post ? logout(r, s) : notAllowed("POST"));
                case "/account":
                    return get ? account(r, s, null) : done(notAllowed("GET"));
                case "/account/withdraw":
                    return done(post ? withdraw(r, s, now) : notAllowed("POST"));
                case "/account/key":
                    return done(post ? key(r, s, now) : notAllowed("POST"));
                default:
                    break;
            }
            m = OP_PATH.matcher(r.path);
            if (m.matches()) {
                return done(get ? op(s, m.group(1)) : notAllowed("GET"));
            }
            m = REFUND_PATH.matcher(r.path);
            if (m.matches()) {
                if (get) {
                    return refundForm(r, s, m.group(1), null);
                }
                return done(post ? refund(r, s, m.group(1), now) : notAllowed("GET, POST"));
            }
            return done(page(404, PayPages.message("没有这个页面", nameOf(s), csrfOf(s), "err", "没有这个页面。", link("/", "回首页"))));
        } catch (RuntimeException e) {
            Ledger.LOG.error("pay.yr 处理请求 {} {} 出错", r.method, r.path, e);
            return done(page(500, PayPages.message("出错了", null, null, "err", "pay.yr 出错了，请稍后再试。", null)));
        }
    }

    /** Host 首部（小写、不带端口） */
    static String host(HttpRequest r) {
        String h = r.header("Host");
        if (h == null) {
            return "";
        }
        int c = h.indexOf(':');
        return (c < 0 ? h : h.substring(0, c)).trim().toLowerCase(java.util.Locale.ROOT);
    }

    /** docs.pay.yr：开发者文档（静态页） */
    private HttpResponse docs(HttpRequest r, long now) {
        if (!pageLimit.tryAcquire(String.valueOf(r.remoteAddress), now)) {
            return page(429, PayPages.message("太快了", null, null, "err", "请求太频繁，请稍后再试", null));
        }
        if (!r.method.equals("GET") && !r.method.equals("HEAD")) {
            return notAllowed("GET");
        }
        String body = PayDocs.page(r.path, core.https());
        if (body == null) {
            return page(404, PayPages.message("没有这个页面", null, null, "err", "文档里没有这一页。", link("/", "文档首页")));
        }
        return HttpResponse.xhtml(200, body).withHeader("Cache-Control", "max-age=300");
    }

    // ---------------------------------------------------------------- 登录

    private HttpResponse login(HttpRequest r, long now) {
        String next = safeNext(r.queryParameters().get("next"));
        if (!idAvailable.getAsBoolean()) {
            return page(503, PayPages.message("不能登录", null, null, "err", "这个服务器现在没有开游戏角色一键登录（" + id.domain()
                    + "，由 YouranAuth 提供），pay.yr 暂时不能登录、不能付款。", link("/", "回首页")));
        }
        if (!loginLimit.tryAcquire(String.valueOf(r.remoteAddress), now)) {
            return page(429, PayPages.message("太快了", null, null, "err", "登录太频繁，请稍后再试", null));
        }
        if (logins.size() >= MAX_LOGINS) {
            return page(503, PayPages.message("太忙了", null, null, "err", "现在登录的人太多，请稍后再试", null));
        }
        String state = random(24);
        String verifier = random(48);
        String scheme = r.secure ? "https" : "http";
        logins.put(state, new Login(verifier, scheme, next, r.remoteAddress, now));
        return redirect(id.authorizeUrl(scheme, state, verifier));
    }

    private CompletableFuture<HttpResponse> callback(HttpRequest r, long now) {
        Map<String, String> q = r.queryParameters();
        Login l = q.get("state") == null ? null : logins.remove(q.get("state"));
        if (l == null || now - l.created > LOGIN_TTL) {
            return done(page(400, PayPages.message("登录失败", null, null, "err", "这次登录已经过期或用过了，请重新登录。", link("/", "回首页"))));
        }
        if (!l.source.equals(r.remoteAddress)) {
            return done(page(403, PayPages.message("登录失败", null, null, "err", "登录要在发起登录的同一台电脑上完成。", link("/", "回首页"))));
        }
        if (!id.issuerOk(q.get("iss"))) {
            return done(page(400, PayPages.message("登录失败", null, null, "err", "登录服务对不上（iss）。", link("/", "回首页"))));
        }
        String err = q.get("error");
        if (err != null) {
            return done(page(400, PayPages.message("登录失败", null, null, "err", loginError(err, q.get("error_description")),
                    link(l.next, "返回") + " · " + link("/login?next=" + IdClient.enc(l.next), "重新登录"))));
        }
        String code = q.get("code");
        if (code == null || code.isEmpty()) {
            return done(page(400, PayPages.message("登录失败", null, null, "err", "登录服务没给授权码。", link("/", "回首页"))));
        }
        return id.exchange(l.scheme, code, l.verifier).handle((who, e) -> {
            if (who == null) {
                Throwable c = e instanceof java.util.concurrent.CompletionException && e.getCause() != null ? e.getCause() : e;
                Ledger.LOG.warn("pay.yr：向 {} 兑换授权码失败：{}", id.domain(), c == null ? "?" : c.toString());
                return page(502, PayPages.message("登录失败", null, null, "err", "向登录服务（" + id.domain() + "）兑换失败，请重新登录。",
                        link("/login?next=" + IdClient.enc(l.next), "重新登录")));
            }
            if (sessions.size() >= MAX_SESSIONS) {
                return page(503, PayPages.message("太忙了", null, null, "err", "现在登录的人太多，请稍后再试", null));
            }
            Session s = new Session(random(32), who.uuid, who.name, random(24), System.currentTimeMillis());
            sessions.put(s.id, s);
            Ledger.LOG.info("pay.yr：{} 用游戏角色登录（来源 {}）", who.name, r.remoteAddress);
            return redirect(l.next).withHeader("Set-Cookie", cookie(s.id, l.scheme.equals("https"), -1));
        });
    }

    static String loginError(String error, String description) {
        switch (error) {
            case "login_unavailable":
                if ("no_operator".equals(description)) {
                    return "这台电脑现在没人操作，不能用游戏角色登录。";
                }
                return "这台电脑没有游戏角色一键登录的能力（或者认不出是哪台电脑），不能在这里登录 pay.yr、不能付款。";
            case "access_denied":
                if ("timeout".equals(description)) {
                    return "确认框超时了，没有登录。";
                }
                if ("operator_changed".equals(description)) {
                    return "确认的时候离开了电脑或换了人，没有登录。";
                }
                return "你在确认框里拒绝了登录。";
            case "temporarily_unavailable":
                return "busy".equals(description) ? "你已经有一个确认框开着，先处理那个再登录。" : "登录服务暂时不可用，请稍后再试。";
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

    private static String cookie(String value, boolean secure, int maxAge) {
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
                if (now - s.lastSeen > PayConfig.sessionIdleMinutes * 60_000L || now - s.created > SESSION_MAX) {
                    sessions.remove(s.id);
                    return null;
                }
                s.lastSeen = now;
                return s;
            }
        }
        return null;
    }

    /** 只许跳回 pay.yr 自己的路径 */
    static String safeNext(String n) {
        if (n == null || !n.startsWith("/") || n.startsWith("//") || n.length() > 200 || !n.matches("[A-Za-z0-9/_.?=&%-]*")) {
            return "/account";
        }
        return n;
    }

    // ---------------------------------------------------------------- 付款页

    private CompletableFuture<HttpResponse> checkout(HttpRequest r, Session s, String orderId, String error, long now) {
        OrderInfo o = core.order(orderId);
        if (o == null) {
            return done(page(404, PayPages.message("没有这张收款单", nameOf(s), csrfOf(s), "err", "没有这张收款单（单号不对，或者已经过了保留期）。", link("/", "回首页"))));
        }
        Map<String, String> q = r.queryParameters();
        CurrencyPlan.Prefer prefer = "yuan".equals(q.get("prefer")) ? CurrencyPlan.Prefer.YUAN : CurrencyPlan.Prefer.EMERALD;
        CurrencyPlan.BalanceUse use = balanceUse(q);
        String err = error;
        if (use == null) {
            use = CurrencyPlan.BalanceUse.NONE;
            err = err == null ? "用余额付的元数要填 1–9 位整数" : err;
        }
        CurrencyPlan.BalanceUse u = use;
        String shownError = err;
        if (s == null || o.status != OrderInfo.Status.OPEN) {
            return done(ok(checkoutPage(o, s, null, prefer, u, shownError)));
        }
        return ctx.onMainThread(() -> core.preview(orderId, s.player, prefer, u))
                .handle((pv, e) -> ok(checkoutPage(o, s, pv, prefer, u, shownError)));
    }

    /** 表单 / 网址里的余额用法：{@code bal=none|all|part}（{@code part} 时 {@code n=元数}），或 {@code bal=元数}；写错为 null */
    static CurrencyPlan.BalanceUse balanceUse(Map<String, String> f) {
        String b = f.get("bal");
        if ("part".equals(b)) {
            String n = f.get("n") == null ? "" : f.get("n").trim();
            return n.isEmpty() ? CurrencyPlan.BalanceUse.NONE : CurrencyPlan.BalanceUse.parse(n);
        }
        return CurrencyPlan.BalanceUse.parse(b == null ? null : b.trim());
    }

    private static String checkoutUrl(String orderId, CurrencyPlan.Prefer prefer, CurrencyPlan.BalanceUse use) {
        return "/c/" + orderId + "?prefer=" + (prefer == CurrencyPlan.Prefer.YUAN ? "yuan" : "emerald") + "&bal=" + use.code();
    }

    private String checkoutPage(OrderInfo o, Session s, PayServer.Preview pv, CurrencyPlan.Prefer prefer, CurrencyPlan.BalanceUse use, String error) {
        CurrencyPlan.PayPlan plan = pv == null ? null : pv.plan;
        StringBuilder b = new StringBuilder();
        b.append("<h1>向 ").append(esc(o.receiverName)).append(" 付款</h1>\n");
        if (error != null) {
            b.append("<p class=\"err\">").append(esc(error)).append("</p>\n");
        }
        b.append("<table class=\"info\">\n")
                .append("<tr><th>收款方</th><td><b>").append(esc(o.receiverName)).append("</b>")
                .append(o.receiver.isPlayer() ? "（玩家角色）" : o.receiver.virtual.equals(o.receiverName) ? "" : "（" + esc(o.receiver.virtual) + "）").append("</td></tr>\n")
                .append("<tr><th>网站</th><td><b>").append(esc(PayPages.site(o.site))).append("</b></td></tr>\n");
        if (o.description != null) {
            b.append("<tr><th>内容</th><td>").append(esc(o.description)).append("</td></tr>\n");
        }
        b.append("<tr><th>要付</th><td>");
        if (o.price.yuan > 0) {
            b.append("<span class=\"amount\">").append(esc(Currency.formatBoth(o.price.yuan, o.unit))).append("</span>")
                    .append(" <span class=\"hint\">绿宝石、悠然元都能付</span>");
        }
        if (!o.price.items.isEmpty()) {
            b.append("<table class=\"list\">").append(PayPages.amountRows(o.price, o.unit)).append("</table>");
        }
        b.append("</td></tr>\n")
                .append("<tr><th>单号</th><td>").append(esc(o.id)).append(o.reference == null ? "" : "（网站单号 " + esc(o.reference) + "）").append("</td></tr>\n")
                .append("<tr><th>状态</th><td>").append(PayPages.statusName(o.status))
                .append(o.status == OrderInfo.Status.OPEN ? "，" + PayPages.time(o.expiresAt) + " 前有效，只能付一次" : "").append("</td></tr>\n")
                .append("</table>\n");
        String back = link("/c/" + o.id + "/cancel", "不付了，返回网站");
        if (o.status != OrderInfo.Status.OPEN) {
            b.append("<p class=\"").append(o.status == OrderInfo.Status.PAID ? "ok" : "err").append("\">")
                    .append(esc(PayServer.statusText(o.status))).append("</p>\n");
            if (o.returnUrl != null) {
                b.append("<p>").append(link(o.status == OrderInfo.Status.PAID ? appendQuery(o.returnUrl, o.id, "paid") : "/c/" + o.id + "/cancel", "返回网站")).append("</p>\n");
            }
            return PayPages.page("付款", nameOf(s), csrfOf(s), b.toString(), 0, null);
        }
        if (s == null) {
            if (!idAvailable.getAsBoolean()) {
                b.append("<p class=\"err\">服务器没有开游戏角色登录（").append(esc(id.domain())).append("），暂时不能付款。</p>\n");
            } else {
                b.append("<form method=\"get\" action=\"/login\"><div><input type=\"hidden\" name=\"next\" value=\"/c/").append(o.id).append("\" />")
                        .append("<input type=\"submit\" class=\"go\" value=\"登录后付款\" /></div></form>\n");
            }
            b.append("<p>").append(back).append("</p>\n");
            return PayPages.page("付款", null, null, b.toString(), 0, null);
        }
        b.append("<h2>付款人：").append(esc(s.name)).append("</h2>\n");
        if (o.requiredPayer != null && !o.requiredPayer.equals(s.player)) {
            String who = core.ledger.name(AccountRef.player(o.requiredPayer));
            b.append("<p class=\"err\">这张单要由 <b>").append(esc(who)).append("</b> 付，现在登录的是 ").append(esc(s.name)).append("。</p>\n")
                    .append("<form method=\"post\" action=\"/logout\"><div><input type=\"hidden\" name=\"csrf\" value=\"").append(esc(s.csrf))
                    .append("\" /><input type=\"submit\" value=\"退出登录\" /></div></form>\n")
                    .append("<p>").append(back).append("</p>\n");
            return PayPages.page("付款", s.name, s.csrf, b.toString(), 0, null);
        }
        if (pv != null && pv.error != null) {
            b.append("<p class=\"err\">").append(esc(pv.error)).append("</p>\n");
        }
        if (pv != null && !pv.balance.isZero()) {
            b.append("<table class=\"info\"><tr><th>pay.yr 余额</th><td>").append(esc(ItemText.describe(pv.balance, Currency.Unit.YUAN)))
                    .append("</td></tr></table>\n")
                    .append("<form method=\"get\" action=\"/c/").append(o.id).append("\"><div>")
                    .append("<input type=\"hidden\" name=\"prefer\" value=\"").append(prefer == CurrencyPlan.Prefer.YUAN ? "yuan" : "emerald").append("\" />")
                    .append("用余额付：<input type=\"radio\" name=\"bal\" value=\"none\"").append(use.isNone() ? " checked=\"checked\"" : "").append(" /> 不用 ")
                    .append("<input type=\"radio\" name=\"bal\" value=\"all\"").append(use == CurrencyPlan.BalanceUse.ALL ? " checked=\"checked\"" : "").append(" /> 能用的全用上 ")
                    .append("<input type=\"radio\" name=\"bal\" value=\"part\"").append(!use.isNone() && use != CurrencyPlan.BalanceUse.ALL ? " checked=\"checked\"" : "")
                    .append(" /> 用 <input type=\"text\" name=\"n\" maxlength=\"9\" size=\"5\" value=\"")
                    .append(!use.isNone() && use != CurrencyPlan.BalanceUse.ALL ? Long.toString(use.yuan) : "").append("\" /> 元 ")
                    .append("<input type=\"submit\" value=\"重新算\" /></div></form>\n");
        }
        if (plan == null) {
            b.append("<p class=\"err\">").append(esc(s.name)).append(" 现在不在线，付不了。</p>\n");
        } else if (!plan.ok()) {
            b.append("<p class=\"err\">背包里的不够：还差 ").append(esc(ItemText.describe(plan.lack, o.unit))).append("。</p>\n");
        } else {
            b.append("<table class=\"info\">");
            if (pv != null && !pv.fromBalance.isZero()) {
                b.append("<tr><th>用余额付</th><td>").append(esc(ItemText.describe(pv.fromBalance, Currency.Unit.YUAN))).append("</td></tr>\n");
            }
            b.append("<tr><th>从背包拿出</th><td>").append(esc(plan.take.isEmpty() ? "（不用）" : PayPages.items(plan.take))).append("</td></tr>\n");
            if (!plan.change.isEmpty()) {
                b.append("<tr><th>找回</th><td>").append(esc(PayPages.items(plan.change))).append("（").append(plan.changeYuan).append(" 元）</td></tr>\n");
            }
            b.append("</table>\n");
        }
        if (o.price.yuan > 0) {
            b.append("<p>").append(prefer == CurrencyPlan.Prefer.EMERALD ? "<b>先用绿宝石</b>" : link(checkoutUrl(o.id, CurrencyPlan.Prefer.EMERALD, use), "先用绿宝石"))
                    .append(" · ").append(prefer == CurrencyPlan.Prefer.YUAN ? "<b>先用悠然元</b>" : link(checkoutUrl(o.id, CurrencyPlan.Prefer.YUAN, use), "先用悠然元"))
                    .append("</p>\n");
        }
        if (plan != null && plan.ok()) {
            b.append("<form method=\"post\" action=\"/c/").append(o.id).append("/pay\"><div>")
                    .append("<input type=\"hidden\" name=\"csrf\" value=\"").append(esc(s.csrf)).append("\" />")
                    .append("<input type=\"hidden\" name=\"prefer\" value=\"").append(prefer == CurrencyPlan.Prefer.YUAN ? "yuan" : "emerald").append("\" />")
                    .append("<input type=\"hidden\" name=\"bal\" value=\"").append(esc(use.code())).append("\" />")
                    .append("<input type=\"submit\" class=\"go\" value=\"付款\" /></div></form>\n")
                    .append("<p class=\"hint\">在游戏弹出的确认框里核对后才扣，扣 ").append(esc(s.name)).append(" 的。</p>\n");
        }
        b.append("<p>").append(back).append(" · ").append(link(checkoutUrl(o.id, prefer, use), "刷新")).append("</p>\n");
        return PayPages.page("付款", s.name, s.csrf, b.toString(), 0, null);
    }

    private HttpResponse cancel(String orderId, long now) {
        OrderInfo o = core.order(orderId);
        if (o == null) {
            return page(404, PayPages.message("没有这张收款单", null, null, "err", "没有这张收款单。", link("/", "回首页")));
        }
        String to = o.cancelUrl != null ? o.cancelUrl : o.returnUrl;
        if (to == null) {
            return ok(PayPages.message("不付了", null, null, "ok", "没有付款。可以关掉这个页面了。", null));
        }
        return redirect(appendQuery(to, o.id, o.status == OrderInfo.Status.PAID ? "paid" : "canceled"));
    }

    static String appendQuery(String url, String orderId, String status) {
        return url + (url.indexOf('?') >= 0 ? "&" : "?") + "order=" + orderId + "&status=" + status;
    }

    private HttpResponse pay(HttpRequest r, Session s, String orderId, long now) {
        Map<String, String> f = r.form();
        if (s == null) {
            return redirect("/c/" + orderId);
        }
        if (!s.csrf.equals(f.get("csrf"))) {
            return page(403, PayPages.message("页面过期了", s.name, s.csrf, "err", "页面过期了，请回付款页重新点“付款”。", link("/c/" + orderId, "回付款页")));
        }
        CurrencyPlan.Prefer prefer = "yuan".equals(f.get("prefer")) ? CurrencyPlan.Prefer.YUAN : CurrencyPlan.Prefer.EMERALD;
        CurrencyPlan.BalanceUse use = balanceUse(f);
        if (use == null) {
            return page(400, PayPages.message("付款", s.name, s.csrf, "err", "用余额付的元数要填 1–9 位整数", link("/c/" + orderId, "回付款页")));
        }
        if (ops.size() >= MAX_OPS) {
            return page(503, PayPages.message("太忙了", s.name, s.csrf, "err", "现在太忙，请稍后再试", null));
        }
        Op op = new Op("op_" + random(16).toLowerCase(java.util.Locale.ROOT).replaceAll("[^a-z0-9]", "a"), s.id, OpKind.PAY, orderId, now);
        if (orderLocks.putIfAbsent(orderId, op.id) != null) {
            return page(409, PayPages.message("正在付款", s.name, s.csrf, "err", "这张收款单正在别处付款（确认框还开着）。", link("/c/" + orderId, "回付款页")));
        }
        ops.put(op.id, op);
        Ipv6Address source = r.remoteAddress;
        core.onMain(() -> {
            PayServer.PreparedPay prep;
            try {
                prep = core.preparePay(orderId, s.player, prefer, use);
            } catch (PayException e) {
                op.fail(e.getMessage());
                orderLocks.remove(orderId, op.id);
                return;
            }
            op.state = OpState.CONFIRMING;
            SystemConfirm.ask(source, prep.request).whenComplete((res, err) -> core.onMain(() -> {
                try {
                    TxRunner.Result tr = core.executePay(prep, res);
                    finish(op, tr, "付款成功");
                } finally {
                    orderLocks.remove(orderId, op.id);
                }
            }));
        });
        return redirect("/op/" + op.id);
    }

    private void finish(Op op, TxRunner.Result tr, String okText) {
        op.txId = tr.txId;
        switch (tr.status) {
            case DONE:
                op.done(okText);
                break;
            case PROCESSING:
                op.state = OpState.PROCESSING;
                break;
            default:
                op.fail(tr.error == null ? "没做成" : tr.error.getMessage());
        }
    }

    // ---------------------------------------------------------------- 等结果

    private HttpResponse op(Session s, String opId) {
        Op op = ops.get(opId);
        if (op == null || s == null || !op.sessionId.equals(s.id)) {
            return page(404, PayPages.message("没有这个操作", nameOf(s), csrfOf(s), "err", "没有这个操作（过期了，或者登录换了）。", link("/account", "我的账户")));
        }
        if (op.state == OpState.PROCESSING && !core.processing(op.txId)) {
            // 存盘核对补上了：照账本看结果
            if (op.kind == OpKind.PAY) {
                OrderInfo o = core.order(op.orderId);
                if (o != null && o.status == OrderInfo.Status.PAID && op.txId != null) {
                    op.done("付款成功");
                } else {
                    op.fail("没有付成（存盘没成功，已经作废），背包里的东西没有扣");
                }
            } else {
                op.done("已完成");
            }
        }
        String back = op.kind == OpKind.PAY ? "/c/" + op.orderId : "/account";
        switch (op.state) {
            case PREPARING:
            case CONFIRMING:
                return ok(PayPages.page("请确认", s.name, s.csrf, "<h1>请在游戏弹出的确认框里确认</h1>\n"
                        + "<p class=\"wait\">" + (op.state == OpState.PREPARING ? "正在准备……" : "核对确认框里的内容，点“"
                        + (op.kind == OpKind.PAY ? "确认付款" : "确认") + "”或“拒绝”。") + "</p>\n"
                        + "<p class=\"hint\">本页自动刷新；" + PayConfig.confirmSeconds + " 秒不回应算拒绝。</p>\n"
                        + "<p>" + link("/op/" + op.id, "刷新") + "</p>\n", 2, "/op/" + op.id));
            case PROCESSING:
                return ok(PayPages.page("正在处理", s.name, s.csrf, "<h1>正在处理</h1>\n<p class=\"wait\">请稍等。</p>\n", 2, "/op/" + op.id));
            case FAILED:
                return ok(PayPages.message(op.kind == OpKind.PAY ? "没有付款" : "没有完成", s.name, s.csrf, "err", op.message,
                        op.kind == OpKind.PAY ? link(back, "回付款页") + " · " + link("/c/" + op.orderId + "/cancel", "不付了，返回网站") : link(back, "回我的账户")));
            default:
                break;
        }
        if (op.kind == OpKind.PAY) {
            OrderInfo o = core.order(op.orderId);
            if (o != null && o.returnUrl != null) {
                return redirect(appendQuery(o.returnUrl, o.id, "paid"));
            }
            return ok(PayPages.message("付款成功", s.name, s.csrf, "ok", "付款成功。可以关掉这个页面了。", null));
        }
        if (op.kind == OpKind.KEY) {
            String key = op.secret;
            op.secret = null;
            String body = "<h1>新的密钥</h1>\n" + (key == null ? "<p class=\"err\">密钥只显示一次，已经显示过了。忘了就再重置一次。</p>\n"
                    : "<p class=\"ok\">新的密钥（<b>只显示这一次</b>，旧的已经作废）：</p>\n<pre>" + esc(key) + "</pre>\n"
                    + "<p class=\"hint\">写进网站后台配置，别给别人看。</p>\n")
                    + "<p>" + link("/account", "回我的账户") + "</p>\n";
            return ok(PayPages.page("密钥", s.name, s.csrf, body, 0, null));
        }
        return ok(PayPages.message("完成了", s.name, s.csrf, "ok", op.message, link("/account", "回我的账户")));
    }

    // ---------------------------------------------------------------- 账户

    private CompletableFuture<HttpResponse> account(HttpRequest r, Session s, String error) {
        if (s == null) {
            return done(redirect("/login?next=/account"));
        }
        AccountRef me = AccountRef.player(s.player);
        // 物品名在主线程上算好（缓存），页面在网络线程上拼
        return ctx.onMainThread(() -> {
            ItemText.cacheAll(core.balance(me));
            for (Ledger.Payment p : core.ledger.paymentsTo(me, 20)) {
                ItemText.cacheAll(p.amount);
            }
            for (moe.qingu.youran.api.pay.PayRecord rec : core.records(me, 30)) {
                ItemText.cacheAll(rec.amount);
            }
            return Boolean.TRUE;
        }).handle((v, e) -> ok(accountPage(s, me, error)));
    }

    private String accountPage(Session s, AccountRef me, String error) {
        Amount bal = core.balance(me);
        Ledger.Account acct = core.ledger.account(me);
        StringBuilder b = new StringBuilder();
        b.append("<h1>").append(esc(s.name)).append(" 的 pay.yr 账户</h1>\n");
        if (error != null) {
            b.append("<p class=\"err\">").append(esc(error)).append("</p>\n");
        }
        b.append("<table class=\"info\"><tr><th>钱</th><td><span class=\"amount\">").append(bal.yuan).append(" 元</span>（合 ")
                .append(esc(Currency.format(bal.yuan, Currency.Unit.EMERALD))).append("）</td></tr>\n")
                .append("<tr><th>别的物品</th><td>").append(esc(PayPages.items(bal.items))).append("</td></tr></table>\n");
        // 提现
        b.append("<h2>提现</h2>\n");
        if (bal.isZero()) {
            b.append("<p class=\"hint\">账户里没有东西。</p>\n");
        } else {
            b.append("<form method=\"post\" action=\"/account/withdraw\"><div>")
                    .append("<input type=\"hidden\" name=\"csrf\" value=\"").append(esc(s.csrf)).append("\" />")
                    .append("<table class=\"info\">");
            if (bal.yuan > 0) {
                b.append("<tr><th>钱（元）</th><td><input type=\"text\" name=\"yuan\" maxlength=\"9\" value=\"").append(bal.yuan).append("\" /> ")
                        .append("<input type=\"radio\" name=\"form\" value=\"emerald\" checked=\"checked\" /> 要绿宝石 ")
                        .append("<input type=\"radio\" name=\"form\" value=\"yuan\" /> 要悠然元")
                        .append("</td></tr>\n");
            }
            for (Map.Entry<PayItem, Long> e : bal.items.entrySet()) {
                b.append("<tr><th>").append(esc(ItemText.name(e.getKey()))).append("</th><td><input type=\"text\" name=\"item_").append(itemKey(e.getKey()))
                        .append("\" maxlength=\"9\" value=\"\" /> 个（有 ").append(e.getValue()).append(" 个）</td></tr>\n");
            }
            b.append("</table><input type=\"submit\" class=\"go\" value=\"提现到背包\" /></div></form>\n")
                    .append("<p class=\"hint\">放进 ").append(esc(s.name)).append(" 的背包（要在线），放不下的掉在脚边。</p>\n");
        }
        // 收到的付款
        List<Ledger.Payment> pays = core.ledger.paymentsTo(me, 20);
        b.append("<h2>收到的付款</h2>\n");
        if (pays.isEmpty()) {
            b.append("<p class=\"hint\">还没有收到过付款。</p>\n");
        } else {
            b.append("<table class=\"list\"><tr><th>时间</th><th>付款人</th><th>网站</th><th>金额</th><th>已退</th><th></th></tr>\n");
            for (Ledger.Payment p : pays) {
                boolean full = p.amount.minus(p.refunded).isZero();
                b.append("<tr><td>").append(PayPages.time(p.time)).append("</td><td>").append(esc(p.payerName)).append("</td><td>")
                        .append(esc(p.site == null ? "" : PayPages.site(p.site))).append("</td><td>").append(esc(ItemText.describe(p.amount, Currency.Unit.YUAN)))
                        .append("</td><td>").append(p.refunded.isZero() ? "" : esc(ItemText.describe(p.refunded, Currency.Unit.YUAN))).append("</td><td>")
                        .append(full ? "已全退" : link("/account/refund/" + p.id, "退款")).append("</td></tr>\n");
            }
            b.append("</table>\n");
        }
        b.append("<h2>收支记录</h2>\n").append(PayPages.records(core.records(me, 30)));
        // 密钥
        b.append("<h2>收款密钥（给你的网站用）</h2>\n");
        if (acct != null && acct.keyHash != null) {
            b.append("<p>现在的密钥：<code>").append(esc(acct.keyPrefix)).append("…</code>（").append(PayPages.time(acct.keyCreated)).append(" 生成）</p>\n");
        } else {
            b.append("<p class=\"hint\">还没有密钥。</p>\n");
        }
        b.append("<form method=\"post\" action=\"/account/key\"><div><input type=\"hidden\" name=\"csrf\" value=\"").append(esc(s.csrf)).append("\" />")
                .append("<input type=\"submit\" value=\"").append(acct != null && acct.keyHash != null ? "重置密钥" : "生成密钥").append("\" /></div></form>\n")
                .append("<p class=\"hint\">").append(link(PayPages.docs("/"), "接入文档")).append("</p>\n");
        return PayPages.page("我的账户", s.name, s.csrf, b.toString(), 0, null);
    }

    /** 表单里物品字段的名字：物品写法的 SHA-256 前 12 位 */
    static String itemKey(PayItem i) {
        try {
            byte[] d = MessageDigest.getInstance("SHA-256").digest(i.toString().getBytes(StandardCharsets.UTF_8));
            StringBuilder sb = new StringBuilder();
            for (int k = 0; k < 6; k++) {
                sb.append(String.format("%02x", d[k]));
            }
            return sb.toString();
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }

    /** 表单里填的物品个数（只认 {@code from} 里有的物品）；填得不对抛异常 */
    static Map<PayItem, Long> itemCounts(Map<String, String> form, Iterable<PayItem> from) throws PayException {
        Map<PayItem, Long> m = new LinkedHashMap<>();
        for (PayItem i : from) {
            String v = form.get("item_" + itemKey(i));
            if (v == null || v.trim().isEmpty()) {
                continue;
            }
            if (!COUNT.matcher(v.trim()).matches()) {
                throw new PayException("invalid_amount", "个数要填整数：" + ItemText.name(i));
            }
            long n = Long.parseLong(v.trim());
            if (n > 0) {
                m.put(i, n);
            }
        }
        return m;
    }

    static long yuanField(String v) throws PayException {
        if (v == null || v.trim().isEmpty()) {
            return 0;
        }
        if (!COUNT.matcher(v.trim()).matches()) {
            throw new PayException("invalid_amount", "钱要填整数（元）");
        }
        return Long.parseLong(v.trim());
    }

    private HttpResponse withdraw(HttpRequest r, Session s, long now) {
        if (s == null) {
            return redirect("/login?next=/account");
        }
        Map<String, String> f = r.form();
        if (!s.csrf.equals(f.get("csrf"))) {
            return page(403, PayPages.message("页面过期了", s.name, s.csrf, "err", "页面过期了，请回账户页重新提交。", link("/account", "我的账户")));
        }
        long yuan;
        Map<PayItem, Long> items;
        try {
            yuan = yuanField(f.get("yuan"));
            items = itemCounts(f, core.balance(AccountRef.player(s.player)).items.keySet());
        } catch (PayException e) {
            return ok(PayPages.message("提现", s.name, s.csrf, "err", e.getMessage(), link("/account", "回我的账户")));
        }
        Currency.Unit form = "yuan".equals(f.get("form")) ? Currency.Unit.YUAN : Currency.Unit.EMERALD;
        Op op = newOp(s, OpKind.WITHDRAW, null, now);
        if (op == null) {
            return page(503, PayPages.message("太忙了", s.name, s.csrf, "err", "现在太忙，请稍后再试", null));
        }
        Ipv6Address source = r.remoteAddress;
        core.onMain(() -> {
            PayServer.PreparedWithdraw prep;
            try {
                prep = core.prepareWithdraw(s.player, yuan, form, items);
            } catch (PayException e) {
                op.fail(e.getMessage());
                return;
            }
            op.state = OpState.CONFIRMING;
            SystemConfirm.ask(source, prep.request).whenComplete((res, err) -> core.onMain(() ->
                    finish(op, core.executeWithdraw(prep, res), "提现成功：东西放进了背包（放不下的掉在脚边）")));
        });
        return redirect("/op/" + op.id);
    }

    private CompletableFuture<HttpResponse> refundForm(HttpRequest r, Session s, String paymentId, String error) {
        if (s == null) {
            return done(redirect("/login?next=/account/refund/" + paymentId));
        }
        Ledger.Payment p = core.ledger.payment(paymentId);
        if (p == null || !p.receiver.equals(AccountRef.player(s.player))) {
            return done(page(404, PayPages.message("没有这笔付款", s.name, s.csrf, "err", "没有这笔付款（只能退自己收到的）。", link("/account", "回我的账户"))));
        }
        return ctx.onMainThread(() -> {
            ItemText.cacheAll(p.amount);
            return Boolean.TRUE;
        }).handle((v, e) -> ok(refundPage(s, p, error)));
    }

    private String refundPage(Session s, Ledger.Payment p, String error) {
        Amount left = p.amount.minus(p.refunded);
        StringBuilder b = new StringBuilder();
        b.append("<h1>退款给 ").append(esc(p.payerName)).append("</h1>\n");
        if (error != null) {
            b.append("<p class=\"err\">").append(esc(error)).append("</p>\n");
        }
        b.append("<table class=\"info\"><tr><th>付款</th><td>").append(esc(p.id)).append("（").append(PayPages.time(p.time)).append("，")
                .append(esc(p.site == null ? "" : PayPages.site(p.site))).append("）</td></tr>\n")
                .append("<tr><th>付了</th><td>").append(esc(ItemText.describe(p.amount, Currency.Unit.YUAN))).append("</td></tr>\n")
                .append("<tr><th>已退</th><td>").append(esc(ItemText.describe(p.refunded, Currency.Unit.YUAN))).append("</td></tr>\n")
                .append("<tr><th>还能退</th><td>").append(esc(ItemText.describe(left, Currency.Unit.YUAN))).append("</td></tr></table>\n");
        if (left.isZero()) {
            b.append("<p class=\"hint\">已经全退了。</p>\n");
        } else {
            b.append("<form method=\"post\" action=\"/account/refund/").append(p.id).append("\"><div>")
                    .append("<input type=\"hidden\" name=\"csrf\" value=\"").append(esc(s.csrf)).append("\" /><table class=\"info\">");
            if (left.yuan > 0) {
                b.append("<tr><th>退多少钱（元）</th><td><input type=\"text\" name=\"yuan\" maxlength=\"9\" value=\"").append(left.yuan).append("\" /> 最多 ")
                        .append(left.yuan).append(" 元</td></tr>\n");
            }
            for (Map.Entry<PayItem, Long> e : left.items.entrySet()) {
                b.append("<tr><th>").append(esc(ItemText.name(e.getKey()))).append("</th><td><input type=\"text\" name=\"item_").append(itemKey(e.getKey()))
                        .append("\" maxlength=\"9\" value=\"").append(e.getValue()).append("\" /> 个，最多 ").append(e.getValue()).append("</td></tr>\n");
            }
            b.append("</table><input type=\"submit\" class=\"go\" value=\"退款\" /></div></form>\n")
                    .append("<p class=\"hint\">退进付款人的 pay.yr 账户，从 ").append(esc(s.name)).append(" 的余额扣回。</p>\n");
        }
        b.append("<p>").append(link("/account", "回我的账户")).append("</p>\n");
        return PayPages.page("退款", s.name, s.csrf, b.toString(), 0, null);
    }

    private HttpResponse refund(HttpRequest r, Session s, String paymentId, long now) {
        if (s == null) {
            return redirect("/login?next=/account");
        }
        Map<String, String> f = r.form();
        if (!s.csrf.equals(f.get("csrf"))) {
            return page(403, PayPages.message("页面过期了", s.name, s.csrf, "err", "页面过期了，请重新提交。", link("/account", "我的账户")));
        }
        Ledger.Payment p = core.ledger.payment(paymentId);
        if (p == null || !p.receiver.equals(AccountRef.player(s.player))) {
            return page(404, PayPages.message("没有这笔付款", s.name, s.csrf, "err", "没有这笔付款（只能退自己收到的）。", link("/account", "回我的账户")));
        }
        Amount amount;
        try {
            amount = Amount.of(yuanField(f.get("yuan")), itemCounts(f, p.amount.items.keySet()));
        } catch (PayException e) {
            return ok(PayPages.message("退款", s.name, s.csrf, "err", e.getMessage(), link("/account/refund/" + paymentId, "回退款页")));
        }
        Op op = newOp(s, OpKind.REFUND, null, now);
        if (op == null) {
            return page(503, PayPages.message("太忙了", s.name, s.csrf, "err", "现在太忙，请稍后再试", null));
        }
        Ipv6Address source = r.remoteAddress;
        core.onMain(() -> {
            PayServer.PreparedRefund prep;
            try {
                prep = core.prepareRefund(s.player, paymentId, amount);
            } catch (PayException e) {
                op.fail(e.getMessage());
                return;
            }
            op.state = OpState.CONFIRMING;
            SystemConfirm.ask(source, prep.request).whenComplete((res, err) -> core.onMain(() -> {
                try {
                    core.executeRefund(prep, res);
                    op.done("已退款：" + ItemText.describe(amount, Currency.Unit.YUAN) + "，退进了 " + p.payerName + " 的 pay.yr 账户");
                } catch (PayException e) {
                    op.fail(e.getMessage());
                }
            }));
        });
        return redirect("/op/" + op.id);
    }

    private HttpResponse key(HttpRequest r, Session s, long now) {
        if (s == null) {
            return redirect("/login?next=/account");
        }
        if (!s.csrf.equals(r.form().get("csrf"))) {
            return page(403, PayPages.message("页面过期了", s.name, s.csrf, "err", "页面过期了，请重新提交。", link("/account", "我的账户")));
        }
        Op op = newOp(s, OpKind.KEY, null, now);
        if (op == null) {
            return page(503, PayPages.message("太忙了", s.name, s.csrf, "err", "现在太忙，请稍后再试", null));
        }
        Ipv6Address source = r.remoteAddress;
        core.onMain(() -> {
            op.state = OpState.CONFIRMING;
            SystemConfirm.ask(source, core.keyRequest(s.name)).whenComplete((res, err) -> core.onMain(() -> {
                try {
                    op.secret = core.executeKey(s.player, res);
                    op.done("密钥已重置");
                } catch (PayException e) {
                    op.fail(e.getMessage());
                }
            }));
        });
        return redirect("/op/" + op.id);
    }

    private Op newOp(Session s, OpKind kind, String orderId, long now) {
        if (ops.size() >= MAX_OPS) {
            return null;
        }
        Op op = new Op("op_" + random(16).toLowerCase(java.util.Locale.ROOT).replaceAll("[^a-z0-9]", "a"), s.id, kind, orderId, now);
        ops.put(op.id, op);
        return op;
    }

    // ---------------------------------------------------------------- 接口

    private CompletableFuture<HttpResponse> api(HttpRequest r, long now) {
        Map<String, String> form = r.method.equals("POST") ? r.form() : java.util.Collections.<String, String>emptyMap();
        boolean text = "text".equals(r.queryParameters().get("format")) || "text".equals(form.get("format"));
        String auth = r.header("Authorization");
        String key = auth != null && auth.regionMatches(true, 0, "Bearer ", 0, 7) ? auth.substring(7).trim() : form.get("key");
        AccountRef acct = key == null ? null : core.ledger.accountByKey(key);
        if (acct == null) {
            if (!badKeyLimit.tryAcquire(String.valueOf(r.remoteAddress), now)) {
                return done(apiError(429, "too_many_requests", "密钥不对的请求太多，请稍后再试", text));
            }
            return done(apiError(401, "invalid_key", "密钥不对（Authorization: Bearer 密钥，或表单 key=密钥）", text)
                    .withHeader("WWW-Authenticate", "Bearer realm=\"pay.yr\""));
        }
        if (!apiLimit.tryAcquire(acct.id(), now)) {
            return done(apiError(429, "too_many_requests", "请求太频繁（每分钟最多 " + PayConfig.apiPerMinute + " 次）", text));
        }
        if (r.path.equals("/api/v1/orders")) {
            return r.method.equals("POST") ? createOrder(acct, form, text, now) : done(apiError(405, "method_not_allowed", "用 POST", text));
        }
        if (r.path.equals("/api/v1/balance")) {
            Amount b = core.balance(acct);
            JsonObject o = new JsonObject();
            o.addProperty("object", "balance");
            o.addProperty("yuan", b.yuan);
            o.add("items", itemsJson(b.items));
            return done(apiOk(200, o, text));
        }
        Matcher m = API_ORDER.matcher(r.path);
        if (m.matches()) {
            OrderInfo o = core.order(m.group(1));
            if (o == null || !o.receiver.equals(acct)) {
                return done(apiError(404, "no_such_order", "没有这张收款单（或者不是你的）", text));
            }
            if (m.group(2) != null) {
                if (!r.method.equals("POST")) {
                    return done(apiError(405, "method_not_allowed", "用 POST", text));
                }
                if (!core.cancelOrder(o.id, acct)) {
                    return done(apiError(409, "order_not_open", PayServer.statusText(o.status), text));
                }
                o = core.order(o.id);
            }
            return done(apiOk(200, orderJson(o), text));
        }
        m = API_PAYMENT.matcher(r.path);
        if (m.matches()) {
            PaymentInfo p = core.payment(m.group(1));
            if (p == null || !p.receiver.equals(acct)) {
                return done(apiError(404, "no_such_payment", "没有这笔付款（或者不是你的）", text));
            }
            return done(apiOk(200, paymentJson(p), text));
        }
        return done(apiError(404, "not_found", "没有这个接口", text));
    }

    private CompletableFuture<HttpResponse> createOrder(AccountRef acct, Map<String, String> f, boolean text, long now) {
        String ret = f.get("return_url");
        String site = null;
        if (ret != null) {
            moe.qingu.youran.api.network.stack.HttpClient.Url u = moe.qingu.youran.api.network.stack.HttpClient.Url.parse(ret.trim());
            site = u == null ? null : u.host;
        }
        if (site == null) {
            return done(apiError(400, "invalid_return_url", "要给 return_url（付完带玩家回哪里，http 或 https），它的主机就是付款页上写的网站", text));
        }
        OrderSpec spec;
        try {
            spec = new OrderSpec(acct, site);
            String em = f.get("emerald");
            String yu = f.get("yuan");
            if (em != null && yu != null) {
                throw new PayException("invalid_amount", "emerald 和 yuan 只能给一个（网站只标一种价）");
            }
            if (em != null) {
                spec.emeralds(positive(em, "emerald"));
            }
            if (yu != null) {
                spec.yuan(positive(yu, "yuan"));
            }
            for (int i = 1; i <= PayServer.MAX_ITEM_LINES; i++) {
                String id = f.get("item" + i);
                if (id == null || id.trim().isEmpty()) {
                    continue;
                }
                String c = f.get("item" + i + "_count");
                long n = c == null || c.trim().isEmpty() ? 1 : positive(c, "item" + i + "_count");
                spec.item(parseItem(id.trim(), f.get("item" + i + "_nbt"), "item" + i), n);
            }
            spec.description(f.get("description")).reference(f.get("reference")).returnUrl(ret).cancelUrl(f.get("cancel_url"));
            String payer = f.get("payer_uuid");
            if (payer != null && !payer.trim().isEmpty()) {
                try {
                    spec.payer(UUID.fromString(payer.trim()));
                } catch (IllegalArgumentException e) {
                    throw new PayException("invalid_payer_uuid", "payer_uuid 要是玩家的 UUID");
                }
            }
            String exp = f.get("expires_in");
            if (exp != null && !exp.trim().isEmpty()) {
                long e = positive(exp, "expires_in");
                spec.ttlSeconds((int) Math.min(Integer.MAX_VALUE, e));
            }
        } catch (PayException e) {
            return done(apiError(400, e.code, e.getMessage(), text));
        } catch (IllegalArgumentException | ArithmeticException e) {
            return done(apiError(400, "invalid_request", e.getMessage(), text));
        }
        OrderSpec finalSpec = spec;
        return ctx.onMainThread(() -> {
            try {
                long t = System.currentTimeMillis();
                return orderJson(core.ledger.info(core.create(core.draft(finalSpec, "api", t), t), t, core::checkoutUrl));
            } catch (PayException e) {
                JsonObject o = new JsonObject();
                o.addProperty("error", e.code);
                o.addProperty("message", e.getMessage());
                return o;
            }
        }).handle((o, e) -> {
            if (o == null) {
                return apiError(503, "unavailable", "pay.yr 暂时不可用", text);
            }
            if (o.has("error")) {
                return apiError(400, o.get("error").getAsString(), o.get("message").getAsString(), text);
            }
            return apiOk(201, o, text);
        });
    }

    static long positive(String s, String field) throws PayException {
        String t = s == null ? "" : s.trim();
        if (!COUNT.matcher(t).matches() || Long.parseLong(t) <= 0) {
            throw new PayException("invalid_" + field, field + " 要是正整数");
        }
        return Long.parseLong(t);
    }

    /** {@code minecraft:wool@14} + 可选 NBT（SNBT） */
    static PayItem parseItem(String id, String nbt, String field) throws PayException {
        int at = id.lastIndexOf('@');
        String name = (at < 0 ? id : id.substring(0, at)).toLowerCase(java.util.Locale.ROOT);
        if (!name.contains(":")) {
            name = "minecraft:" + name;
        }
        int meta = 0;
        if (at >= 0) {
            try {
                meta = Integer.parseInt(id.substring(at + 1));
            } catch (NumberFormatException e) {
                throw new PayException("invalid_" + field, field + " 的数据值不对：" + id);
            }
        }
        String canon = null;
        if (nbt != null && !nbt.trim().isEmpty()) {
            try {
                canon = moe.qingu.youran.api.pay.NbtCanon.write(moe.qingu.youran.api.pay.NbtCanon.parse(nbt.trim()));
            } catch (IllegalArgumentException e) {
                throw new PayException("invalid_" + field + "_nbt", field + "_nbt 不是合法的 SNBT：" + e.getMessage());
            }
        }
        try {
            return new PayItem(name, meta, canon, null);
        } catch (IllegalArgumentException e) {
            throw new PayException("invalid_" + field, e.getMessage());
        }
    }

    static JsonArray itemsJson(Map<PayItem, Long> m) {
        JsonArray a = new JsonArray();
        for (Map.Entry<PayItem, Long> e : m.entrySet()) {
            JsonObject o = new JsonObject();
            o.addProperty("id", e.getKey().id);
            o.addProperty("meta", e.getKey().meta);
            if (e.getKey().nbt != null) {
                o.addProperty("nbt", e.getKey().nbt);
            }
            o.addProperty("count", e.getValue());
            a.add(o);
        }
        return a;
    }

    JsonObject orderJson(OrderInfo o) {
        JsonObject j = new JsonObject();
        j.addProperty("id", o.id);
        j.addProperty("object", "order");
        j.addProperty("status", o.status.code);
        j.addProperty("url", o.checkoutUrl);
        j.addProperty("site", o.site);
        j.addProperty("currency", o.unit.code);
        if (o.price.yuan > 0) {
            if (o.unit == Currency.Unit.EMERALD && o.price.yuan % Currency.YUAN_PER_EMERALD == 0) {
                j.addProperty("amount", o.price.yuan / Currency.YUAN_PER_EMERALD);
            } else if (o.unit == Currency.Unit.EMERALD) {
                j.addProperty("amount", o.price.yuan / (double) Currency.YUAN_PER_EMERALD);
            } else {
                j.addProperty("amount", o.price.yuan);
            }
        }
        j.addProperty("amount_yuan", o.price.yuan);
        j.add("items", itemsJson(o.price.items));
        if (o.description != null) {
            j.addProperty("description", o.description);
        }
        if (o.reference != null) {
            j.addProperty("reference", o.reference);
        }
        if (o.requiredPayer != null) {
            j.addProperty("required_payer_uuid", o.requiredPayer.toString());
        }
        j.addProperty("created", o.createdAt / 1000);
        j.addProperty("expires_at", o.expiresAt / 1000);
        if (o.status == OrderInfo.Status.PAID) {
            j.addProperty("paid_at", o.paidAt / 1000);
            j.addProperty("payment", o.paymentId);
            j.addProperty("payer_uuid", o.payer.toString());
            j.addProperty("payer_name", o.payerName);
            j.addProperty("refunded_yuan", o.refunded.yuan);
            j.add("refunded_items", itemsJson(o.refunded.items));
        }
        return j;
    }

    static JsonObject paymentJson(PaymentInfo p) {
        JsonObject j = new JsonObject();
        j.addProperty("id", p.id);
        j.addProperty("object", "payment");
        j.addProperty("order", p.orderId);
        j.addProperty("payer_uuid", p.payer.toString());
        j.addProperty("payer_name", p.payerName);
        j.addProperty("amount_yuan", p.amount.yuan);
        j.add("items", itemsJson(p.amount.items));
        j.addProperty("refunded_yuan", p.refunded.yuan);
        j.add("refunded_items", itemsJson(p.refunded.items));
        j.addProperty("time", p.time / 1000);
        return j;
    }

    /** JSON，或 {@code format=text} 时的纯文本（一行一项 名字=值；物品写成 item1=…、item1_count=…） */
    static HttpResponse apiOk(int status, JsonObject o, boolean text) {
        if (!text) {
            return HttpResponse.of(status, "application/json; charset=utf-8", o.toString().getBytes(StandardCharsets.UTF_8))
                    .withHeader("Cache-Control", "no-store");
        }
        StringBuilder sb = new StringBuilder();
        for (Map.Entry<String, com.google.gson.JsonElement> e : o.entrySet()) {
            if (e.getValue().isJsonArray()) {
                int i = 0;
                for (com.google.gson.JsonElement x : e.getValue().getAsJsonArray()) {
                    i++;
                    JsonObject it = x.getAsJsonObject();
                    String p = (e.getKey().equals("items") ? "item" : e.getKey().replace("_items", "_item")) + i;
                    sb.append(p).append('=').append(it.get("id").getAsString()).append(it.get("meta").getAsInt() == 0 ? "" : "@" + it.get("meta").getAsInt()).append('\n');
                    sb.append(p).append("_count=").append(it.get("count").getAsLong()).append('\n');
                    if (it.has("nbt")) {
                        sb.append(p).append("_nbt=").append(it.get("nbt").getAsString()).append('\n');
                    }
                }
            } else if (!e.getValue().isJsonNull()) {
                sb.append(e.getKey()).append('=').append(e.getValue().getAsString().replace('\n', ' ')).append('\n');
            }
        }
        return HttpResponse.of(status, "text/plain; charset=utf-8", sb.toString().getBytes(StandardCharsets.UTF_8)).withHeader("Cache-Control", "no-store");
    }

    static HttpResponse apiError(int status, String code, String message, boolean text) {
        JsonObject o = new JsonObject();
        o.addProperty("error", code);
        o.addProperty("message", message);
        return apiOk(status, o, text);
    }

    // ---------------------------------------------------------------- 小工具

    private void sweep(long now) {
        if (random.nextInt(32) != 0) {
            return;
        }
        logins.values().removeIf(l -> now - l.created > LOGIN_TTL);
        sessions.values().removeIf(s -> now - s.lastSeen > PayConfig.sessionIdleMinutes * 60_000L || now - s.created > SESSION_MAX);
        for (Iterator<Op> it = ops.values().iterator(); it.hasNext(); ) {
            Op op = it.next();
            if (now - op.created > OP_TTL && op.state != OpState.CONFIRMING && op.state != OpState.PREPARING) {
                it.remove();
            }
        }
    }

    private String random(int bytes) {
        byte[] b = new byte[bytes];
        random.nextBytes(b);
        String s = Base64.getUrlEncoder().withoutPadding().encodeToString(b);
        return s.length() > bytes ? s.substring(0, Math.max(bytes, 16)) : s;
    }

    private static String nameOf(Session s) {
        return s == null ? null : s.name;
    }

    private static String csrfOf(Session s) {
        return s == null ? null : s.csrf;
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

    /** 测试用 */
    int sessionCount() {
        return sessions.size();
    }

    List<String> opIds() {
        return new ArrayList<>(ops.keySet());
    }
}
