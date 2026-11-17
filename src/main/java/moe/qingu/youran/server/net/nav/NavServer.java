package moe.qingu.youran.server.net.nav;

import com.google.gson.JsonObject;
import moe.qingu.youran.api.network.HostContext;
import moe.qingu.youran.api.network.Ipv6Address;
import moe.qingu.youran.api.network.SiteInfo;
import moe.qingu.youran.api.network.SourceIdentity;
import moe.qingu.youran.api.network.YouranNetworkApi;
import moe.qingu.youran.api.network.http.HttpResponse;
import moe.qingu.youran.api.network.http.HttpServer;
import moe.qingu.youran.api.network.stack.DnsResolver;
import moe.qingu.youran.api.pay.AccountRef;
import moe.qingu.youran.api.pay.Amount;
import moe.qingu.youran.api.pay.OrderInfo;
import moe.qingu.youran.api.pay.OrderSpec;
import moe.qingu.youran.api.pay.PayApi;
import moe.qingu.youran.api.pay.PayException;
import moe.qingu.youran.api.pay.PaymentInfo;
import moe.qingu.youran.api.pay.PaymentListener;
import moe.qingu.youran.network.NetworkRegistry;
import net.minecraft.server.MinecraftServer;
import net.minecraft.world.WorldServer;
import net.minecraftforge.common.MinecraftForge;
import net.minecraftforge.fml.common.eventhandler.SubscribeEvent;
import net.minecraftforge.fml.common.gameevent.TickEvent;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.function.BooleanSupplier;
import java.util.function.Predicate;

/**
 * 180.yr 的服务端本体（主线程这一侧）：广告账本的读写与计时、收款回调、给网页用的“今日”与管理员名单快照、在 pay.yr 开单与退款。
 * 网页在 {@link NavService}（网络线程）。数据在世界存档的 {@code youranmod/180/}。
 */
public final class NavServer implements NavService.Env {
    /** 主机登记名 */
    public static final String HOST = "youranmod:180";
    /** 这台主机上的三个名字 */
    public static final List<String> NAMES = Collections.unmodifiableList(Arrays.asList(NavService.NAV, NavService.PLATFORM, NavService.HELP));
    /** 广告收款进 YouranMod 在 pay.yr 的这个虚拟账户（回收货币，服主 11-15） */
    public static final String ACCOUNT = "youranmod:180";
    /** 收款账户给人看的名字（付款页、确认框里） */
    public static final String DISPLAY_NAME = "180 广告";
    /** 收款单多久过期（秒） */
    static final int ORDER_TTL = 900;

    private final MinecraftServer server;
    private final AdBook book;
    private final AdStore store;
    private final Events events = new Events();
    private final PaymentListener payListener = new PaymentListener() {
        @Override
        public void onPaid(OrderInfo order, PaymentInfo payment) {
            AdBook.Ad ad = book.byOrder(order.id);
            if (ad != null && book.paid(ad, payment.id, System.currentTimeMillis(), System.currentTimeMillis())) {
                NavService.LOG.info("180.yr：{} 付了广告 #{}（{}）", payment.payerName, ad.id, payment.id);
            }
        }

        @Override
        public void onRefunded(PaymentInfo payment, Amount refund) {
        }
    };
    private volatile Today today = Today.UNKNOWN;
    private volatile Set<String> ops = Collections.emptySet();
    private volatile boolean https;
    private int ticks;

    private NavServer(MinecraftServer server, AdBook book, AdStore store) {
        this.server = server;
        this.book = book;
        this.store = store;
    }

    /** 开服（主线程，世界已加载）：读广告账本；读不了时返回 null（180.yr 不开，文件不动） */
    public static NavServer start(MinecraftServer server) {
        File dir = new File(new File(server.getWorld(0).getSaveHandler().getWorldDirectory(), "youranmod"), "180");
        AdStore store = new AdStore(dir.toPath());
        AdBook.Listener saver = new AdBook.Listener() {
            @Override
            public void changed(AdBook b) {
                try {
                    store.save(b.toJson());
                } catch (IOException e) {
                    NavService.LOG.error("180.yr：写广告账本失败（{}）", dir, e);
                }
            }

            @Override
            public void dropped(AdBook.Ad ad) {
                store.deleteImage(ad.id);
            }
        };
        AdBook book = new AdBook(AdRules.fromConfig(), saver);
        try {
            JsonObject o = store.load();
            if (o != null) {
                List<String> problems = new ArrayList<>();
                book.load(o, problems);
                for (String p : problems) {
                    NavService.LOG.warn("180.yr：{}", p);
                }
            }
        } catch (IOException | RuntimeException e) {
            NavService.LOG.error("180.yr：读不了广告账本 {}，180.yr 不开（文件没有动；修好后重启）", dir, e);
            return null;
        }
        NavServer s = new NavServer(server, book, store);
        s.snapshot();
        MinecraftForge.EVENT_BUS.register(s.events);
        PayApi.addListener(AccountRef.virtual(ACCOUNT), s.payListener);
        PayApi.setDisplayName(ACCOUNT, DISPLAY_NAME);
        NavService.LOG.info("180.yr：广告账本 {} 已读（{} 条）", dir, book.all().size());
        return s;
    }

    /**
     * 网络线程：在这台主机上开网页（80，配了中间 CA 时也开 443）。
     *
     * 导航只列现在解析得到的网站：经递归解析器查（这台主机的协议栈，{@link moe.qingu.youran.api.network.stack.NameCache} 按 TTL 缓存），
     * 显示时用上一次的回答。id.yr 开没开也这样查。
     */
    public void startWeb(HostContext ctx, Ipv6Address resolver) {
        List<String> problems = new ArrayList<>();
        moe.qingu.youran.api.network.stack.NameCache names = new moe.qingu.youran.api.network.stack.NameCache(
                new DnsResolver(ctx.stack(), resolver), ctx.stack().loop());
        Predicate<String> exists = names::resolves;
        NavDirectory dir = NavDirectory.build(decls(), NavConfig.extraSites, NavConfig.hiddenSites, exists, problems);
        for (String p : problems) {
            NavService.LOG.warn("180.yr：{}", p);
        }
        // 自己向证书服务申请证书（HTTP 认证，80 口自动回），到期前续
        moe.qingu.youran.api.network.tls.TlsServerContext tls = moe.qingu.youran.api.network.ca.CertificateClient.start(ctx.stack(), NAMES, null).tls();
        https = tls != null;
        moe.qingu.youran.api.network.idyr.IdYrClient id = new moe.qingu.youran.api.network.idyr.IdYrClient(ctx.stack(),
                new DnsResolver(ctx.stack(), resolver), NavConfig.idDomain, https, NavService.PLATFORM);
        this.idAvailable = id::available;
        // 先把导航上的名字和 id.yr 都问一遍
        dir.all();
        id.available();
        NavService service = new NavService(book, store, dir, this, id, logo(), NavConfig.sessionIdleMinutes * 60_000L);
        HttpServer.Options o = new HttpServer.Options().maxBodyBytes(NavService.MAX_BODY).handlerTimeoutMillis(20_000)
                .errorPages(st -> HttpResponse.xhtml(st, NavPages.frame("出错了", "", null, null, false, "http",
                        "<p class=\"err\">HTTP " + st + " " + HttpResponse.reasonPhrase(st) + "</p>", 0, null)));
        HttpServer.listen(ctx.stack(), 80, null, o, service::handle);
        if (tls != null) {
            HttpServer.listen(ctx.stack(), 443, tls, o, service::handle);
        }
        NavService.LOG.info("180.yr：导航登记了 {} 个网站（显示时只列解析得到的）；网页开在 {}（{}）", dir.candidateCount(), ctx.address(), https ? "HTTP 与 HTTPS" : "HTTP");
    }

    private volatile BooleanSupplier idAvailable = () -> false;

    public void stop() {
        MinecraftForge.EVENT_BUS.unregister(events);
        PayApi.removeListener(AccountRef.virtual(ACCOUNT), payListener);
    }

    /** 导航信息：YouranMod 自己的网站、替别的模组写的兜底项、模组登记的 */
    static List<NavDirectory.Decl> decls() {
        List<NavDirectory.Decl> out = new ArrayList<>();
        // 别的模组登记了同一个名字时用别人的（兜底）
        out.add(new NavDirectory.Decl("portal.yr", SiteInfo.of("悠然面板", SiteInfo.Category.SERVICE, "公告、消息、我的角色").featured(), true));
        out.add(new NavDirectory.Decl("id.yr", SiteInfo.of("角色登录", SiteInfo.Category.SERVICE, "用游戏角色一键登录网站"), true));
        for (NetworkRegistry.NavDecl d : NetworkRegistry.navs()) {
            out.add(new NavDirectory.Decl(d.name, d.info, d.fallback));
        }
        out.add(new NavDirectory.Decl("pay.yr", SiteInfo.of("pay.yr 支付", SiteInfo.Category.SERVICE, "付款、收款、提现").featured(), false));
        out.add(new NavDirectory.Decl(NavService.PLATFORM, SiteInfo.of("180 广告", SiteInfo.Category.SERVICE, "在 180 上投放广告").featured(), false));
        out.add(new NavDirectory.Decl(NavService.HELP, SiteInfo.of("180 帮助", SiteInfo.Category.TOOL, "设主页、投广告、举报"), false));
        out.add(new NavDirectory.Decl("captcha.yr", SiteInfo.of("人机验证", SiteInfo.Category.TOOL, "给网站用的图片验证码"), false));
        out.add(new NavDirectory.Decl("location.yr", SiteInfo.of("位置验证", SiteInfo.Category.TOOL, "核对电脑位置证明"), false));
        return out;
    }

    static byte[] logo() {
        try (InputStream in = NavServer.class.getResourceAsStream("/assets/youranmod/nav/logo.png")) {
            if (in == null) {
                return new byte[0];
            }
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            byte[] buf = new byte[8192];
            int n;
            while ((n = in.read(buf)) > 0) {
                out.write(buf, 0, n);
            }
            return out.toByteArray();
        } catch (IOException e) {
            return new byte[0];
        }
    }

    // ---------------------------------------------------------------- 主线程

    private final class Events {
        @SubscribeEvent
        public void onTick(TickEvent.ServerTickEvent e) {
            if (e.phase != TickEvent.Phase.END) {
                return;
            }
            if (++ticks % 20 != 0) {
                return;
            }
            snapshot();
            long now = System.currentTimeMillis();
            book.tick(now, now);
            if (ticks % 200 == 0) {
                reconcile(System.currentTimeMillis());
            }
        }
    }

    /** 拍“今日”、OP 名单 */
    private void snapshot() {
        WorldServer w = server.getWorld(0);
        if (w == null) {
            return;
        }
        Today.Weather weather = w.isThundering() ? Today.Weather.THUNDER : w.isRaining() ? Today.Weather.RAIN : Today.Weather.CLEAR;
        today = Today.of(w.getWorldTime(), weather, server.getCurrentPlayerCount(), server.getMaxPlayers());
        Set<String> o = new HashSet<>();
        for (String n : server.getPlayerList().getOppedPlayerNames()) {
            o.add(n.toLowerCase(Locale.ROOT));
        }
        ops = o;
    }

    /** 待付款的：收款单付了的补记（回调丢了时），过期、取消的作废 */
    void reconcile(long now) {
        for (AdBook.Ad a : book.all()) {
            if (a.state != AdBook.State.DRAFT) {
                continue;
            }
            if (a.orderId == null) {
                if (now - a.createdMs > 120_000) {
                    book.cancelDraft(a, now);
                }
                continue;
            }
            OrderInfo o = PayApi.order(a.orderId);
            if (o == null) {
                if (now - a.createdMs > (ORDER_TTL + 3600) * 1000L) {
                    book.cancelDraft(a, now);
                }
            } else if (o.status == OrderInfo.Status.PAID) {
                if (book.paid(a, o.paymentId, now, now)) {
                    NavService.LOG.info("180.yr：广告 #{} 的付款补记上了（{}）", a.id, o.paymentId);
                }
            } else if (o.status == OrderInfo.Status.EXPIRED || o.status == OrderInfo.Status.CANCELED) {
                book.cancelDraft(a, now);
            }
        }
    }

    // ---------------------------------------------------------------- 给网页的（任意线程）

    @Override
    public Today today() {
        return today;
    }

    /** 广告的时钟：现实时间（毫秒，服主 11-16） */
    @Override
    public long tick() {
        return System.currentTimeMillis();
    }

    @Override
    public boolean isAdmin(UUID player, String name) {
        for (String a : NavConfig.admins) {
            String t = a == null ? "" : a.trim();
            if (t.equalsIgnoreCase(name) || t.equalsIgnoreCase(player.toString())) {
                return true;
            }
        }
        return NavConfig.opsAreAdmins && name != null && ops.contains(name.toLowerCase(Locale.ROOT));
    }

    @Override
    public boolean idAvailable() {
        return idAvailable.getAsBoolean();
    }

    @Override
    public boolean payAvailable() {
        return PayApi.isAvailable();
    }

    @Override
    public CompletableFuture<String> openOrder(AdBook.Ad ad, String scheme) {
        CompletableFuture<String> f = new CompletableFuture<>();
        server.addScheduledTask(() -> {
            try {
                String base = scheme + "://" + NavService.PLATFORM + "/ad/" + ad.id;
                OrderInfo o = PayApi.createOrder(new OrderSpec(AccountRef.virtual(ACCOUNT), NavService.PLATFORM)
                        .emeralds(book.rules().emeralds(ad.slot, ad.days))
                        .description("180 广告 #" + ad.id + " " + ad.slot.label + " " + ad.days + " 天")
                        .reference("ad-" + ad.id).returnUrl(base + "?msg=paid").cancelUrl(base).ttlSeconds(ORDER_TTL));
                book.attachOrder(ad, o.id);
                f.complete(o.checkoutUrl);
            } catch (PayException e) {
                f.completeExceptionally(new IllegalStateException(e.getMessage(), e));
            } catch (RuntimeException e) {
                f.completeExceptionally(e);
            }
        });
        return f;
    }

    @Override
    public CompletableFuture<Void> refund(AdBook.Ad ad, long yuan, String note) {
        CompletableFuture<Void> f = new CompletableFuture<>();
        if (yuan <= 0) {
            f.complete(null);
            return f;
        }
        server.addScheduledTask(() -> {
            try {
                if (ad.paymentId == null) {
                    throw new IllegalStateException("找不到这条广告的付款记录");
                }
                PayApi.refund(ad.paymentId, Amount.yuan(yuan), note);
                f.complete(null);
            } catch (PayException e) {
                f.completeExceptionally(new IllegalStateException(e.getMessage(), e));
            } catch (RuntimeException e) {
                f.completeExceptionally(e);
            }
        });
        return f;
    }

    @Override
    public String orderStatus(String orderId) {
        OrderInfo o = PayApi.order(orderId);
        return o == null ? null : o.status.code;
    }

    @Override
    public String checkoutUrl(String orderId) {
        OrderInfo o = PayApi.order(orderId);
        return o == null ? null : o.checkoutUrl;
    }

    @Override
    public String identify(Ipv6Address source) {
        SourceIdentity id = YouranNetworkApi.identify(source);
        return id == null ? null : id.operatorName;
    }
}
