package moe.qingu.youran.server.net.nav;

import moe.qingu.youran.api.image.DyePng;
import moe.qingu.youran.api.network.Ipv6Address;
import moe.qingu.youran.api.network.SiteInfo;
import moe.qingu.youran.api.network.http.HttpHeaders;
import moe.qingu.youran.api.network.http.HttpRequest;
import moe.qingu.youran.api.network.http.HttpResponse;
import moe.qingu.youran.api.network.idyr.IdYrClient;
import moe.qingu.youran.api.network.idyr.IdYrException;
import org.junit.jupiter.api.Test;

import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** 三个站的网页：主页、搜索、投放、举报、管理（不走网络，直接调处理函数） */
class NavServiceTest {
    static final Ipv6Address PC = Ipv6Address.parse("fd00::1:2");
    static final Ipv6Address PC2 = Ipv6Address.parse("fd00::1:3");
    final UUID alice = UUID.fromString("00000000-0000-0000-0000-00000000000a");
    final UUID admin = UUID.fromString("00000000-0000-0000-0000-0000000000ad");

    /** 假的游戏一侧：开单记下来、退款记下来 */
    static final class FakeEnv implements NavService.Env {
        long tick = 1000;
        boolean pay = true;
        String failOrder;
        String failRefund;
        final List<String> refunds = new ArrayList<>();
        final List<Integer> orders = new ArrayList<>();
        AdBook book;

        @Override
        public Today today() {
            return Today.of(6000, Today.Weather.RAIN, 3, 20);
        }

        @Override
        public long tick() {
            return tick;
        }

        @Override
        public boolean isAdmin(UUID player, String name) {
            return "Admin".equals(name);
        }

        @Override
        public boolean idAvailable() {
            return true;
        }

        @Override
        public boolean payAvailable() {
            return pay;
        }

        @Override
        public CompletableFuture<String> openOrder(AdBook.Ad ad, String scheme) {
            CompletableFuture<String> f = new CompletableFuture<>();
            if (failOrder != null) {
                f.completeExceptionally(new IllegalStateException(failOrder));
                return f;
            }
            orders.add(ad.id);
            book.attachOrder(ad, "ord_" + ad.id);
            f.complete("http://pay.yr/c/ord_" + ad.id);
            return f;
        }

        @Override
        public CompletableFuture<Void> refund(AdBook.Ad ad, long yuan, String note) {
            CompletableFuture<Void> f = new CompletableFuture<>();
            if (failRefund != null && yuan > 0) {
                f.completeExceptionally(new IllegalStateException(failRefund));
                return f;
            }
            refunds.add(ad.id + ":" + yuan);
            f.complete(null);
            return f;
        }

        @Override
        public String orderStatus(String orderId) {
            return "open";
        }

        @Override
        public String checkoutUrl(String orderId) {
            return "http://pay.yr/c/" + orderId;
        }

        @Override
        public String identify(Ipv6Address source) {
            return source.equals(PC) ? "Carol" : null;
        }
    }

    final FakeEnv env = new FakeEnv();
    final AdBook book = new AdBook(new AdRules(1, 2, 2, 2, 4, 2, 1, 1, 2, 10), b -> { });
    final AdStore store;
    final NavService service;
    final NavDirectory directory;

    /** 假的 id.yr：记下建的验证请求，结果由测试定 */
    static final class FakeId extends IdYrClient {
        final List<IdYrClient.Ask> asks = new ArrayList<>();
        final List<String> checked = new ArrayList<>();
        IdYrClient.Outcome next = new IdYrClient.Outcome(IdYrClient.Outcome.Status.APPROVED, null, "fd00::1:2");
        RuntimeException failAsk;

        FakeId() {
            super(null, null, "id.yr", false, NavService.PLATFORM);
        }

        @Override
        public CompletableFuture<IdYrClient.Ticket> ask(String accessToken, IdYrClient.Ask ask) {
            CompletableFuture<IdYrClient.Ticket> f = new CompletableFuture<>();
            if (failAsk != null) {
                f.completeExceptionally(failAsk);
                return f;
            }
            asks.add(ask);
            f.complete(new IdYrClient.Ticket("cr_" + asks.size(), "http://id.yr/confirm?request=cr_" + asks.size(), "secret"));
            return f;
        }

        @Override
        public CompletableFuture<IdYrClient.Outcome> result(String accessToken, String requestId) {
            checked.add(requestId);
            return CompletableFuture.completedFuture(next);
        }

        /** 追加授权同意了：带新令牌（加上 verify） */
        static IdYrClient.Outcome granted(UUID who, String name) {
            return new IdYrClient.Outcome(IdYrClient.Outcome.Status.APPROVED, null, "fd00::1:2",
                    new IdYrClient.Token(who, name, "token-with-verify", Long.MAX_VALUE, Collections.singleton(IdYrClient.VERIFY)));
        }

        IdYrClient.Ask last() {
            return asks.get(asks.size() - 1);
        }

        /** 最近一次请求跳回的路径（/verified/编号） */
        String back() {
            String u = asks.get(asks.size() - 1).redirectUri;
            return u.substring(u.indexOf("/verified/"));
        }
    }

    final FakeId fakeId = new FakeId();
    final NavService gatedService;

    NavServiceTest() throws java.io.IOException {
        env.book = book;
        Path dir = java.nio.file.Files.createTempDirectory("nav180test");
        dir.toFile().deleteOnExit();
        store = new AdStore(dir);
        List<NavDirectory.Decl> decls = Arrays.asList(
                new NavDirectory.Decl("minebay.yr", SiteInfo.of("MineBay", SiteInfo.Category.SHOPPING, "网上商城").featured(), false),
                new NavDirectory.Decl("pay.yr", SiteInfo.of("pay.yr 支付", SiteInfo.Category.SERVICE, "付款").featured(), false),
                new NavDirectory.Decl("captcha.yr", SiteInfo.of("人机验证", SiteInfo.Category.TOOL, "验证码"), false));
        NavDirectory d = NavDirectory.build(decls, new String[0], new String[0], new HashSet<>(Arrays.asList("minebay.yr", "pay.yr", "captcha.yr"))::contains,
                new ArrayList<>());
        service = new NavService(book, store, d, env, null, new byte[]{1, 2, 3}, 600_000);
        directory = d;
        gatedService = new NavService(book, store, d, env, fakeId, new byte[]{1, 2, 3}, 600_000);
    }

    HttpResponse get(String host, String path, NavService.Session s) {
        return send("GET", host, path, null, s, PC);
    }

    HttpResponse post(String host, String path, String form, NavService.Session s, Ipv6Address from) {
        return send("POST", host, path, form, s, from);
    }

    HttpResponse send(String method, String host, String path, String form, NavService.Session s, Ipv6Address from) {
        return send(service, method, host, path, form, s, from);
    }

    HttpResponse send(NavService svc, String method, String host, String path, String form, NavService.Session s, Ipv6Address from) {
        HttpHeaders h = new HttpHeaders().add("Host", host);
        if (s != null) {
            h.add("Cookie", NavService.COOKIE + "=" + s.id);
        }
        byte[] body = new byte[0];
        if (form != null) {
            h.add("Content-Type", "application/x-www-form-urlencoded");
            body = form.getBytes(StandardCharsets.US_ASCII);
        }
        HttpRequest r = new HttpRequest(method, path, "HTTP/1.1", h, body, from, 40000, Ipv6Address.parse("fd00::180"), 80, false);
        return svc.handle(r).join();
    }

    static String text(HttpResponse r) {
        return new String(r.body, StandardCharsets.UTF_8);
    }

    static String enc(String s) throws Exception {
        return URLEncoder.encode(s, "UTF-8");
    }

    static String squareImage() {
        byte[] p = new byte[125 * 125];
        for (int i = 0; i < 125; i++) {
            p[i * 125 + i] = 15;
        }
        return DyePng.toDataUrl(DyePng.encode(125, 125, p));
    }

    @Test
    void homePage() throws Exception {
        String home = text(get("180.yr", "/", null));
        assertTrue(home.contains(">MineBay</a>"));
        assertTrue(home.contains("http://minebay.yr/"));
        assertTrue(home.contains("名站导航"));
        assertTrue(home.contains("第 1 天"));
        assertTrue(home.contains("12:00"));
        assertTrue(home.contains("雨"));
        assertTrue(home.contains("在线 3 人"));
        assertTrue(home.contains("广告位招租 468×60"), "空着的横幅");
        assertTrue(home.contains("http://platform.180.yr/buy?slot=square"));
        assertFalse(home.contains("<script"));
        // 同一台主机上的另外两个名字
        assertTrue(text(get("help.180.yr", "/", null)).contains("帮助中心"));
        assertTrue(text(get("help.180.yr", "/canvas", null)).contains("x-yr-canvas"));
        assertEquals(404, get("help.180.yr", "/nope", null).status);
        assertEquals(200, get("180.yr:80", "/logo.png", null).status);
        assertEquals(405, post("180.yr", "/", "a=1", null, PC).status);
        // 页面是合法的 XML（浏览器按 XHTML 读）
        for (String p : new String[]{"/", "/s?q=pay"}) {
            javax.xml.parsers.DocumentBuilderFactory f = javax.xml.parsers.DocumentBuilderFactory.newInstance();
            f.setFeature("http://apache.org/xml/features/nonvalidating/load-external-dtd", false);
            f.newDocumentBuilder().parse(new java.io.ByteArrayInputStream(get("180.yr", p, null).body));
        }
    }

    @Test
    void search() {
        String r = text(get("180.yr", "/s?q=%E5%95%86%E5%9F%8E", null));
        assertTrue(r.contains("找到 1 个网站"), r);
        assertTrue(r.contains("MineBay"));
        assertTrue(text(get("180.yr", "/s?q=zzz", null)).contains("没有找到"));
        assertTrue(text(get("180.yr", "/s?q=%3Cb%3E", null)).contains("&lt;b&gt;"), "搜索词转义");
    }

    @Test
    void buyImageAdGoesLiveAfterPayment() throws Exception {
        NavService.Session s = service.addSession(alice, "Alice", System.currentTimeMillis());
        HttpResponse form = get("platform.180.yr", "/buy?slot=square", s);
        assertTrue(text(form).contains("type=\"x-yr-canvas\" name=\"image\" width=\"125\" height=\"125\""));
        // 没登录：去登录
        assertEquals("/login?next=%2Fbuy%3Fslot%3Dsquare", get("platform.180.yr", "/buy?slot=square", null).headers.get("Location"));
        String img = squareImage();
        HttpResponse r = post("platform.180.yr", "/buy", "csrf=" + s.csrf + "&slot=square&days=2&link=wood.yr&text=" + enc("好木头") + "&image=" + enc(img), s, PC);
        assertEquals(303, r.status, text(r));
        assertEquals("http://pay.yr/c/ord_1", r.headers.get("Location"));
        AdBook.Ad a = book.get(1);
        assertEquals(AdBook.State.DRAFT, a.state);
        assertEquals("http://wood.yr/", a.link);
        assertEquals(8, a.priceYuan);
        assertNotNull(store.image(1));
        // 没付：主页上没有
        assertEquals(404, get("180.yr", "/ad/1.png", null).status);
        // 付了（回调）：马上上线
        book.paid(a, "pay_1", env.tick, 1);
        assertEquals(200, get("180.yr", "/ad/1.png", null).status);
        String home = text(get("180.yr", "/", null));
        assertTrue(home.contains("src=\"/ad/1.png\""));
        assertTrue(home.contains("href=\"http://wood.yr/\""));
        assertTrue(home.contains("http://platform.180.yr/report?ad=1"));
        // 我的广告
        String mine = text(get("platform.180.yr", "/", s));
        assertTrue(mine.contains("在播"), mine);
        assertTrue(text(get("platform.180.yr", "/ad/1", s)).contains("在播"));
    }

    @Test
    void badInputKeepsWhatWasTyped() throws Exception {
        NavService.Session s = service.addSession(alice, "Alice", System.currentTimeMillis());
        String img = squareImage();
        // 链接不对：422，画的图、填的字原样带回
        HttpResponse r = post("platform.180.yr", "/buy", "csrf=" + s.csrf + "&slot=square&days=2&link=" + enc("http://example.org/") + "&text=abc&image=" + enc(img), s, PC);
        assertEquals(422, r.status);
        String t = text(r);
        assertTrue(t.contains("游戏内的网站"));
        assertTrue(t.contains("value=\"" + img + "\""), "画的图带回表单");
        assertTrue(t.contains("value=\"abc\""));
        // 尺寸不对的图
        String banner = DyePng.toDataUrl(DyePng.encode(468, 60, new byte[468 * 60]));
        assertEquals(422, post("platform.180.yr", "/buy", "csrf=" + s.csrf + "&slot=square&days=1&link=a.yr&image=" + enc(banner), s, PC).status);
        // 文字链没写字
        assertEquals(422, post("platform.180.yr", "/buy", "csrf=" + s.csrf + "&slot=text&days=1&link=a.yr&text=", s, PC).status);
        // 页面过期（csrf 不对）
        assertEquals(403, post("platform.180.yr", "/buy", "csrf=x&slot=text&days=1&link=a.yr&text=hi", s, PC).status);
        // pay.yr 没开
        env.pay = false;
        assertEquals(503, post("platform.180.yr", "/buy", "csrf=" + s.csrf + "&slot=text&days=1&link=a.yr&text=hi", s, PC).status);
        env.pay = true;
        // 开单失败：作废、让出位置
        env.failOrder = "pay.yr 记账失败";
        HttpResponse f = post("platform.180.yr", "/buy", "csrf=" + s.csrf + "&slot=text&days=1&link=a.yr&text=hi", s, PC);
        assertEquals(502, f.status);
        assertTrue(text(f).contains("pay.yr 记账失败"));
        assertEquals(AdBook.State.CANCELED, book.all().get(0).state);
        assertTrue(book.all().isEmpty() || book.nextStart(AdSlot.TEXT, 0) == 0);
    }

    @Test
    void textAdAndAgain() throws Exception {
        NavService.Session s = service.addSession(alice, "Alice", System.currentTimeMillis());
        assertEquals(303, post("platform.180.yr", "/buy", "csrf=" + s.csrf + "&slot=text&days=1&link=a.yr&text=" + enc("<b>木头</b>"), s, PC).status);
        AdBook.Ad a = book.get(1);
        book.paid(a, "pay_1", env.tick, 1);
        String home = text(get("180.yr", "/", null));
        assertTrue(home.contains("&lt;b&gt;木头&lt;/b&gt;"), "文字转义");
        // 作废的可以“重新下单”
        NavService.Session s2 = s;
        book.draft(AdSlot.TEXT, alice, "Alice", 1, "再来", "http://b.yr/", 0, 2);
        AdBook.Ad d = book.get(2);
        book.cancelDraft(d, 3);
        HttpResponse r = post("platform.180.yr", "/ad/2/again", "csrf=" + s2.csrf, s2, PC);
        assertEquals(303, r.status, text(r));
        assertEquals("再来", book.get(3).text);
    }

    @Test
    void reportOncePerComputer() throws Exception {
        AdBook.Ad a = book.draft(AdSlot.SQUARE, alice, "Alice", 1, "", "http://a.yr/", 0, 1);
        book.paid(a, "pay_1", 0, 1);
        assertTrue(text(get("platform.180.yr", "/report?ad=1", null)).contains("举报广告 #1"));
        HttpResponse r = post("platform.180.yr", "/report", "ad=1&reason=" + enc("骗人、虚假") + "&detail=" + enc("链接是假的"), null, PC);
        assertTrue(text(r).contains("收到了"));
        assertEquals(1, a.reports.size());
        assertEquals("骗人、虚假：链接是假的", a.reports.get(0).reason);
        assertTrue(a.reports.get(0).by.startsWith("Carol @"));
        // 同一台电脑再报：不再记
        assertTrue(text(post("platform.180.yr", "/report", "ad=1&reason=x", null, PC)).contains("已经举报过了"));
        // 别的电脑：记，原因不认识的算“其他”
        post("platform.180.yr", "/report", "ad=1&reason=whatever", null, PC2);
        assertEquals(2, a.reports.size());
        assertEquals("其他", a.reports.get(1).reason);
        assertEquals(404, post("platform.180.yr", "/report", "ad=99&reason=x", null, PC2).status);
    }

    @Test
    void adminTakeDownRefundAndBan() throws Exception {
        NavService.Session s = service.addSession(alice, "Alice", System.currentTimeMillis());
        NavService.Session root = service.addSession(admin, "Admin", System.currentTimeMillis());
        AdBook.Ad a = book.draft(AdSlot.BANNER, alice, "Alice", 4, "", "http://a.yr/", 0, 1);
        book.paid(a, "pay_1", 0, 1);
        env.tick = AdRules.DAY_MS;
        // 不是管理员
        assertEquals(403, get("platform.180.yr", "/admin", s).status);
        assertEquals(403, post("platform.180.yr", "/admin/remove", "csrf=" + s.csrf + "&ad=1&refund=full", s, PC).status);
        assertEquals(AdBook.State.LIVE, a.state);
        String page = text(get("platform.180.yr", "/admin", root));
        assertTrue(page.contains("退没播的 12 绿宝石"), page);
        // 退款失败：不下架
        env.failRefund = "余额不够";
        HttpResponse bad = post("platform.180.yr", "/admin/remove", "csrf=" + root.csrf + "&ad=1&refund=unused&reason=x", root, PC);
        assertEquals(502, bad.status);
        assertEquals(AdBook.State.LIVE, a.state);
        env.failRefund = null;
        HttpResponse r = post("platform.180.yr", "/admin/remove", "csrf=" + root.csrf + "&ad=1&refund=unused&reason=" + enc("违规"), root, PC);
        assertEquals(303, r.status);
        assertEquals(AdBook.State.REMOVED, a.state);
        assertEquals(Arrays.asList("1:24"), env.refunds);
        assertEquals("Admin", a.removedBy);
        // 封禁并下架他所有的
        AdBook.Ad b = book.draft(AdSlot.SQUARE, alice, "Alice", 1, "", "http://a.yr/", env.tick, 2);
        book.paid(b, "pay_2", env.tick, 2);
        AdBook.Ad c = book.draft(AdSlot.TEXT, alice, "Alice", 2, "x", "http://a.yr/", env.tick, 3);
        book.paid(c, "pay_3", env.tick, 3);
        r = post("platform.180.yr", "/admin/ban", "csrf=" + root.csrf + "&ad=2&reason=" + enc("骗人") + "&remove_all=1", root, PC);
        assertEquals(303, r.status);
        assertTrue(book.isBanned(alice));
        assertEquals(AdBook.State.REMOVED, b.state);
        assertEquals(AdBook.State.REMOVED, c.state);
        assertEquals(new HashSet<>(Arrays.asList("1:24", "2:4", "3:4")), new HashSet<>(env.refunds));
        // 封了不能再买
        assertTrue(text(get("platform.180.yr", "/buy?slot=square", s)).contains("禁止投放"));
        assertEquals(409, post("platform.180.yr", "/buy", "csrf=" + s.csrf + "&slot=text&days=1&link=a.yr&text=hi", s, PC).status);
        // 解封
        assertEquals(303, post("platform.180.yr", "/admin/unban", "csrf=" + root.csrf + "&player=" + alice, root, PC).status);
        assertFalse(book.isBanned(alice));
    }

    @Test
    void platformPagesAreXml() throws Exception {
        NavService.Session s = service.addSession(alice, "Alice", System.currentTimeMillis());
        NavService.Session root = service.addSession(admin, "Admin", System.currentTimeMillis());
        AdBook.Ad a = book.draft(AdSlot.BANNER, alice, "Alice", 4, "<&>", "http://a.yr/?a=1&b=2", 0, 1);
        book.paid(a, "pay_1", 0, 1);
        book.report(a, "其他：<x>", "Bob", 1);
        for (Object[] p : new Object[][]{{"platform.180.yr", "/", null}, {"platform.180.yr", "/", s}, {"platform.180.yr", "/buy?slot=banner", s},
                {"platform.180.yr", "/ad/1", s}, {"platform.180.yr", "/admin", root}, {"platform.180.yr", "/report?ad=1", null},
                {"help.180.yr", "/", null}, {"help.180.yr", "/home", null}, {"help.180.yr", "/ads", null}, {"help.180.yr", "/rules", null},
                {"help.180.yr", "/report", null}, {"help.180.yr", "/canvas", null}, {"help.180.yr", "/verify", null}, {"180.yr", "/", null}}) {
            HttpResponse r = get((String) p[0], (String) p[1], (NavService.Session) p[2]);
            assertEquals(200, r.status, p[0] + "" + p[1]);
            javax.xml.parsers.DocumentBuilderFactory f = javax.xml.parsers.DocumentBuilderFactory.newInstance();
            f.setFeature("http://apache.org/xml/features/nonvalidating/load-external-dtd", false);
            f.newDocumentBuilder().parse(new java.io.ByteArrayInputStream(r.body));
        }
    }

    /**
     * 打开“操作前必须验证身份”只要 id.yr 的同意框（追加授权 verify，换上新令牌）；打开以后投放先验证，没通过不下单、填的原样带回、
     * 给“重新验证”（换人坐下重新打开这一页也一样），点了用同样的内容重新验证；重新登录后先追加授权再验证；关闭要本人验证身份
     */
    @Test
    void verifyBeforeBuy() throws Exception {
        NavService.Session s = gatedService.addSession(alice, "Alice", System.currentTimeMillis());
        assertTrue(text(send(gatedService, "GET", "platform.180.yr", "/", null, s, PC)).contains("操作前验证身份：<b>已关闭</b>"));
        // 打开：id.yr 的同意框（追加授权 verify），不是验证身份
        HttpResponse r = send(gatedService, "POST", "platform.180.yr", "/account/security", "csrf=" + s.csrf + "&verify=1", s, PC);
        assertEquals(303, r.status);
        assertEquals("http://id.yr/confirm?request=cr_1", r.headers.get("Location"));
        IdYrClient.Ask ask = fakeId.asks.get(0);
        assertEquals("verify", ask.scope, "打开时追加授权“验证身份”");
        assertFalse(ask.verify, "打开不用本人验证身份");
        assertEquals(PC, ask.source, "只能在这台电脑上打开");
        assertTrue(ask.redirectUri.startsWith("http://platform.180.yr/verified/g_"));
        assertFalse(book.verifyRequired(alice), "还没同意");
        // 不同意：没打开，可以重新确认
        fakeId.next = new IdYrClient.Outcome(IdYrClient.Outcome.Status.DENIED, "denied", "fd00::1:2");
        r = send(gatedService, "GET", "platform.180.yr", fakeId.back(), null, s, PC);
        assertEquals(403, r.status);
        assertTrue(text(r).contains("没有同意") && text(r).contains("重新确认"), text(r));
        assertFalse(book.verifyRequired(alice));
        String gate = fakeId.back();
        r = send(gatedService, "POST", "platform.180.yr", gate + "/again", "csrf=" + s.csrf, s, PC);
        assertEquals("http://id.yr/confirm?request=cr_2", r.headers.get("Location"), "重新确认：新的请求");
        assertEquals("verify", fakeId.last().scope);
        fakeId.next = FakeId.granted(alice, "Alice");
        r = send(gatedService, "GET", "platform.180.yr", fakeId.back(), null, s, PC);
        assertEquals("/?msg=security_on", r.headers.get("Location"));
        assertTrue(book.verifyRequired(alice));
        assertEquals("token-with-verify", s.accessToken, "换上了带 verify 的令牌");
        assertEquals(Arrays.asList("cr_1", "cr_2"), fakeId.checked, "向 id.yr 核对了");
        assertEquals(404, send(gatedService, "GET", "platform.180.yr", fakeId.back(), null, s, PC).status, "同一个结果不能再用");

        // 投放：先验证；不是本人（id.yr 回 denied）→ 不下单，图和字原样带回，有“重新验证”
        String img = squareImage();
        String form = "csrf=" + s.csrf + "&slot=square&days=2&link=wood.yr&text=" + enc("好木头") + "&image=" + enc(img);
        r = send(gatedService, "POST", "platform.180.yr", "/buy", form, s, PC);
        assertEquals("http://id.yr/confirm?request=cr_3", r.headers.get("Location"));
        assertTrue(env.orders.isEmpty(), "验证前不开单");
        assertTrue(fakeId.last().verify);
        assertEquals("投放侧栏方块", fakeId.last().title);
        fakeId.next = new IdYrClient.Outcome(IdYrClient.Outcome.Status.DENIED, "denied", "fd00::1:2");
        gate = fakeId.back();
        r = send(gatedService, "GET", "platform.180.yr", gate, null, s, PC);
        assertEquals(403, r.status);
        assertTrue(text(r).contains("验证身份没有通过"), text(r));
        assertTrue(text(r).contains("value=\"" + img + "\""), "画的图带回表单");
        assertTrue(text(r).contains("value=\"好木头\""));
        assertTrue(text(r).contains("action=\"" + gate + "/again\"") && text(r).contains("重新验证"), "有“重新验证”");
        assertTrue(env.orders.isEmpty());
        assertTrue(book.all().isEmpty(), "也没占位置");
        // 换人坐下（他的浏览器重新打开这一页）：还是没通过的页面，图和字都在，不再去核对
        int checked = fakeId.checked.size();
        r = send(gatedService, "GET", "platform.180.yr", gate, null, s, PC);
        assertEquals(403, r.status);
        assertTrue(text(r).contains("value=\"好木头\"") && text(r).contains("重新验证"));
        assertEquals(checked, fakeId.checked.size());
        // 别的登录拿着这个地址没用
        NavService.Session other = gatedService.addSession(admin, "Admin", System.currentTimeMillis());
        assertEquals(404, send(gatedService, "GET", "platform.180.yr", gate, null, other, PC).status);
        assertEquals(404, send(gatedService, "POST", "platform.180.yr", gate + "/again", "csrf=" + other.csrf, other, PC).status);
        assertEquals(403, send(gatedService, "POST", "platform.180.yr", gate + "/again", "csrf=nope", s, PC).status);
        // 点“重新验证”：同样的内容、新的请求；这回通过：照原样下单、跳到付款页
        r = send(gatedService, "POST", "platform.180.yr", gate + "/again", "csrf=" + s.csrf, s, PC);
        assertEquals("http://id.yr/confirm?request=cr_4", r.headers.get("Location"));
        assertTrue(fakeId.last().verify);
        assertEquals("投放侧栏方块", fakeId.last().title);
        assertEquals(gate, fakeId.back(), "还是同一个操作");
        assertEquals(gate, send(gatedService, "POST", "platform.180.yr", gate + "/again", "csrf=" + s.csrf, s, PC).headers.get("Location"),
                "进行中再点：回等结果的页面，不再建请求");
        assertEquals(4, fakeId.asks.size());
        fakeId.next = new IdYrClient.Outcome(IdYrClient.Outcome.Status.APPROVED, null, "fd00::1:2");
        r = send(gatedService, "GET", "platform.180.yr", gate, null, s, PC);
        assertEquals(303, r.status, text(r));
        assertEquals("http://pay.yr/c/ord_1", r.headers.get("Location"));
        assertEquals(Arrays.asList(1), env.orders);
        assertEquals("http://wood.yr/", book.get(1).link);

        // 还没确认完就回来：等结果（自动刷新），不做
        send(gatedService, "POST", "platform.180.yr", "/buy", "csrf=" + s.csrf + "&slot=text&days=1&link=a.yr&text=hi", s, PC);
        fakeId.next = new IdYrClient.Outcome(IdYrClient.Outcome.Status.PENDING, null, null);
        r = send(gatedService, "GET", "platform.180.yr", fakeId.back(), null, s, PC);
        assertEquals(200, r.status);
        assertTrue(text(r).contains("等你确认"));
        assertEquals(1, env.orders.size());

        // 建不了验证请求（登录过期）：不下单，填的带回
        fakeId.failAsk = new java.util.concurrent.CompletionException(new IdYrException(401, "invalid_token", "过期"));
        r = send(gatedService, "POST", "platform.180.yr", "/buy", "csrf=" + s.csrf + "&slot=text&days=1&link=a.yr&text=" + enc("再来"), s, PC);
        assertEquals(403, r.status);
        assertTrue(text(r).contains("登录过期了"));
        assertTrue(text(r).contains("value=\"再来\""));
        fakeId.failAsk = null;

        // 重新登录（这次登录没有 verify）：先追加授权，通过了接着验证身份，再下单
        NavService.Session again = gatedService.addSession(alice, "Alice", System.currentTimeMillis());
        int before = fakeId.asks.size();
        r = send(gatedService, "POST", "platform.180.yr", "/buy", "csrf=" + again.csrf + "&slot=text&days=1&link=b.yr&text=" + enc("新登录"), again, PC);
        assertEquals("verify", fakeId.last().scope, "先追加授权");
        fakeId.next = FakeId.granted(alice, "Alice");
        r = send(gatedService, "GET", "platform.180.yr", fakeId.back(), null, again, PC);
        assertEquals(303, r.status);
        assertEquals("http://id.yr/confirm?request=cr_" + (before + 2), r.headers.get("Location"), "接着验证身份（新的请求）");
        assertTrue(fakeId.last().verify);
        assertEquals("token-with-verify", again.accessToken);
        fakeId.next = new IdYrClient.Outcome(IdYrClient.Outcome.Status.APPROVED, null, "fd00::1:2");
        r = send(gatedService, "GET", "platform.180.yr", fakeId.back(), null, again, PC);
        assertTrue(r.headers.get("Location").startsWith("http://pay.yr/c/"), text(r));
        assertEquals(2, env.orders.size());

        // 关闭：要本人验证身份；没通过就还开着
        send(gatedService, "POST", "platform.180.yr", "/account/security", "csrf=" + s.csrf + "&verify=0", s, PC);
        assertEquals("关闭操作前验证身份", fakeId.last().title);
        assertTrue(fakeId.last().verify, "关闭要验证身份");
        fakeId.next = new IdYrClient.Outcome(IdYrClient.Outcome.Status.DENIED, "denied", null);
        gate = fakeId.back();
        r = send(gatedService, "GET", "platform.180.yr", gate, null, s, PC);
        assertEquals(403, r.status);
        assertTrue(text(r).contains("重新验证"));
        assertTrue(book.verifyRequired(alice));
        send(gatedService, "POST", "platform.180.yr", gate + "/again", "csrf=" + s.csrf, s, PC);
        fakeId.next = new IdYrClient.Outcome(IdYrClient.Outcome.Status.APPROVED, null, null);
        assertEquals("/?msg=security_off", send(gatedService, "GET", "platform.180.yr", gate, null, s, PC).headers.get("Location"));
        assertFalse(book.verifyRequired(alice));
        // 关了：投放直接去付款
        before = fakeId.asks.size();
        r = send(gatedService, "POST", "platform.180.yr", "/buy", "csrf=" + s.csrf + "&slot=text&days=1&link=a.yr&text=hi", s, PC);
        assertTrue(r.headers.get("Location").startsWith("http://pay.yr/c/"));
        assertEquals(before, fakeId.asks.size());
    }

    /** 管理员开了“操作前必须验证身份”：下架、封禁前要验证；设置随广告账本存下来 */
    @Test
    void verifyBeforeAdmin() throws Exception {
        NavService.Session root = gatedService.addSession(admin, "Admin", System.currentTimeMillis());
        root.scopes = Collections.singleton(IdYrClient.VERIFY);
        AdBook.Ad a = book.draft(AdSlot.BANNER, alice, "Alice", 4, "", "http://a.yr/", 0, 1);
        book.paid(a, "pay_1", 0, 1);
        book.setVerifyRequired(admin, true);
        HttpResponse r = send(gatedService, "POST", "platform.180.yr", "/admin/remove", "csrf=" + root.csrf + "&ad=1&refund=none&reason=" + enc("违规"), root, PC);
        assertEquals("http://id.yr/confirm?request=cr_1", r.headers.get("Location"));
        assertEquals("下架广告 #1", fakeId.asks.get(0).title);
        assertEquals(AdBook.State.LIVE, a.state);
        fakeId.next = new IdYrClient.Outcome(IdYrClient.Outcome.Status.DENIED, "timeout", null);
        String gate = fakeId.back();
        r = send(gatedService, "GET", "platform.180.yr", gate, null, root, PC);
        assertEquals(403, r.status);
        assertTrue(text(r).contains("超时") && text(r).contains("重新验证"), text(r));
        assertEquals(AdBook.State.LIVE, a.state);
        send(gatedService, "POST", "platform.180.yr", gate + "/again", "csrf=" + root.csrf, root, PC);
        assertEquals("下架广告 #1", fakeId.last().title);
        fakeId.next = new IdYrClient.Outcome(IdYrClient.Outcome.Status.APPROVED, null, null);
        assertEquals(303, send(gatedService, "GET", "platform.180.yr", gate, null, root, PC).status);
        assertEquals(AdBook.State.REMOVED, a.state);
        // 存档往返
        AdBook copy = new AdBook(book.rules(), b -> { });
        copy.load(book.toJson(), new ArrayList<>());
        assertTrue(copy.verifyRequired(admin));
        assertFalse(copy.verifyRequired(alice));
        assertTrue(text(get("help.180.yr", "/verify", null)).contains("确认你是"));
    }

    @Test
    void misc() {
        assertEquals("/", NavService.safeNext("//evil"));
        assertEquals("/buy?slot=square", NavService.safeNext("/buy?slot=square"));
        assertEquals("platform.180.yr", NavService.hostOf(new HttpRequest("GET", "/", "HTTP/1.1", new HttpHeaders().add("Host", "PLATFORM.180.YR:443"),
                new byte[0], PC, 1, PC, 80, true)));
        assertTrue(NavService.loginError("access_denied", "timeout").contains("超时"));
    }
}
