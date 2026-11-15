package moe.qingu.youran.server.net.service.captcha;

import moe.qingu.youran.api.network.http.HttpRequest;
import moe.qingu.youran.api.network.http.HttpResponse;
import moe.qingu.youran.api.network.http.HttpServer;
import moe.qingu.youran.api.network.stack.NetStack;
import moe.qingu.youran.api.network.tls.TlsServerContext;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.function.LongSupplier;

/**
 * 游戏内人机验证服务 {@code captcha.yr}（服主 11-15）：任何网站都能用的图片验证码，纯文本接口（Z80 上的程序也好解析）。
 * <pre>
 *   POST /new                         → 200 text/plain，正文是题号（一行，32 位十六进制，没有换行）
 *   GET  /img/&lt;题号&gt;.png             → 200 image/png（160×60）；题不在、过期、核对过了 → 404
 *   POST /verify（表单 id=题号&amp;answer=答案） → 200 text/plain，正文 ok 或 fail（每题只核对一次，核对过就作废，不论对错）
 * </pre>
 * 按来源地址限频（超了回 429）；题库满了回 503。图片在单独的一条画图线程上画（网络线程不阻塞），画好的存在题里，同一题再要不重画。
 * 游戏外的网页面板用 {@link CaptchaLocalServer}（只对本机开放的端口，同样的 /new、/verify，共用这个题库）。
 */
public final class CaptchaService {
    static final Logger LOG = LogManager.getLogger("YouranMod");
    public static final int PORT = 80;
    public static final int TLS_PORT = 443;
    /** 画图线程排队最多这么多（再多回 503） */
    static final int DRAW_QUEUE = 64;

    final CaptchaStore store;
    private final LongSupplier clock;
    /** 游戏内：按来源地址 */
    final SourceLimiter newLimit = new SourceLimiter(0.2, 6, 8192);
    final SourceLimiter imageLimit = new SourceLimiter(2, 12, 8192);
    final SourceLimiter verifyLimit = new SourceLimiter(1, 6, 8192);
    private final ExecutorService drawer;

    public CaptchaService(CaptchaStore store, LongSupplier clock, ExecutorService drawer) {
        this.store = store;
        this.clock = clock;
        this.drawer = drawer;
    }

    /** 默认的画图线程：一条守护线程，排队有上限 */
    public static ExecutorService newDrawer() {
        return new ThreadPoolExecutor(1, 1, 30, TimeUnit.SECONDS, new ArrayBlockingQueue<>(DRAW_QUEUE), r -> {
            Thread t = new Thread(r, "YouranMod 验证码");
            t.setDaemon(true);
            t.setPriority(Thread.NORM_PRIORITY - 1);
            return t;
        });
    }

    public CaptchaStore store() {
        return store;
    }

    /** 在机房主机的协议栈上开 80（与 {@code tls} 不为 null 时的 443） */
    public void start(NetStack stack, TlsServerContext tls) {
        HttpServer.Options o = new HttpServer.Options().maxBodyBytes(1024).maxHeaderBytes(8 * 1024).handlerTimeoutMillis(15_000);
        HttpServer.listen(stack, PORT, null, o, this::handle);
        if (tls != null) {
            HttpServer.listen(stack, TLS_PORT, tls, o, this::handle);
        }
    }

    /** 网络线程上：分发请求（不阻塞） */
    CompletableFuture<HttpResponse> handle(HttpRequest req) {
        String source = String.valueOf(req.remoteAddress);
        long now = clock.getAsLong();
        String path = req.path;
        if (path.equals("/new")) {
            if (!req.method.equals("POST")) {
                return done(notAllowed("POST"));
            }
            if (!newLimit.tryAcquire(source, now)) {
                return done(text(429, "too many requests"));
            }
            return done(create(source, CaptchaStore.Pool.GAME, now));
        }
        if (path.equals("/verify")) {
            if (!req.method.equals("POST")) {
                return done(notAllowed("POST"));
            }
            if (!verifyLimit.tryAcquire(source, now)) {
                return done(text(429, "too many requests"));
            }
            Map<String, String> form = req.form();
            return done(verify(form.get("id"), form.get("answer"), now));
        }
        if (path.startsWith("/img/") && path.endsWith(".png")) {
            if (!req.method.equals("GET") && !req.method.equals("HEAD")) {
                return done(notAllowed("GET, HEAD"));
            }
            if (!imageLimit.tryAcquire(source, now)) {
                return done(text(429, "too many requests"));
            }
            String id = path.substring(5, path.length() - 4);
            return image(id, now);
        }
        if (path.equals("/") || path.equals("/index.xhtml")) {
            if (!req.method.equals("GET") && !req.method.equals("HEAD")) {
                return done(notAllowed("GET, HEAD"));
            }
            return done(HttpResponse.xhtml(200, CaptchaPages.INDEX));
        }
        return done(text(404, "not found"));
    }

    /** 出题（游戏内与本机端口共用） */
    HttpResponse create(String source, CaptchaStore.Pool pool, long now) {
        CaptchaStore.Created c = store.create(source, pool, now);
        if (c.id == null) {
            return text(503, "busy");
        }
        return text(200, c.id);
    }

    HttpResponse verify(String id, String answer, long now) {
        return text(200, store.verify(id, answer, now) ? "ok" : "fail");
    }

    private CompletableFuture<HttpResponse> image(String id, long now) {
        CompletableFuture<byte[]> png = store.image(id, now, c -> {
            CompletableFuture<byte[]> f = new CompletableFuture<>();
            try {
                drawer.execute(() -> {
                    try {
                        f.complete(CaptchaImage.png(c.answer, c.seed));
                    } catch (Throwable t) {
                        LOG.error("画验证码图片失败", t);
                        f.completeExceptionally(t);
                    }
                });
            } catch (RejectedExecutionException e) {
                f.completeExceptionally(e);
            }
            return f;
        });
        if (png == null) {
            return done(text(404, "not found"));
        }
        CompletableFuture<HttpResponse> out = new CompletableFuture<>();
        png.whenComplete((b, err) -> {
            if (err != null) {
                // 画不出来（排队满了等）：下次再要时重画
                store.forgetImage(id, png);
                out.complete(text(503, "busy"));
            } else {
                out.complete(HttpResponse.of(200, "image/png", b).withHeader("Cache-Control", "no-store"));
            }
        });
        return out;
    }

    static HttpResponse text(int status, String body) {
        return HttpResponse.of(status, "text/plain; charset=utf-8", body.getBytes(StandardCharsets.UTF_8))
                .withHeader("Cache-Control", "no-store");
    }

    private static HttpResponse notAllowed(String allow) {
        return text(405, "method not allowed").withHeader("Allow", allow);
    }

    private static CompletableFuture<HttpResponse> done(HttpResponse r) {
        return CompletableFuture.completedFuture(r);
    }

    /** 关服 */
    public void stop() {
        drawer.shutdownNow();
    }
}
