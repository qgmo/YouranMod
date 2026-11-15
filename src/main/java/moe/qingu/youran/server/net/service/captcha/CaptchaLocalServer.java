package moe.qingu.youran.server.net.service.captcha;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import moe.qingu.youran.api.network.http.FormData;
import moe.qingu.youran.api.network.http.HttpResponse;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.function.LongSupplier;

/**
 * 验证码的本机端口（服主 11-15）：游戏外的网站（网页面板的后台）进不了游戏内网络，由这里提供同样的 {@code POST /new}、{@code POST /verify}
 * （与 {@code captcha.yr} 共用题库；图片仍由游戏内浏览器从 {@code captcha.yr} 取）。只绑 127.0.0.1，只认 Host 是本机的请求
 * （防浏览器经 DNS 重绑定打进来）。用 JDK 自带的 HTTP 服务器，在它自己的两条线程上跑，不碰网络线程与主线程。
 */
public final class CaptchaLocalServer {
    /** 本机端口这边出题、核对的来源名（游戏内的来源是 IPv6 地址，不会和它重名） */
    static final String SOURCE = "local";
    static final int MAX_BODY = 4096;

    private final CaptchaService service;
    private final LongSupplier clock;
    /** 面板后台按自己的规则限流，这里只挡失控的调用 */
    private final SourceLimiter limit = new SourceLimiter(50, 200, 4);
    private HttpServer server;
    private ThreadPoolExecutor executor;
    private int port;

    public CaptchaLocalServer(CaptchaService service, LongSupplier clock) {
        this.service = service;
        this.clock = clock;
    }

    /** 开在 127.0.0.1:{@code port}（0 表示随便挑一个空闲端口，测试用）；开不了抛 {@link IOException} */
    public synchronized void start(int port) throws IOException {
        HttpServer s = HttpServer.create(new InetSocketAddress(InetAddress.getByName("127.0.0.1"), port), 16);
        executor = new ThreadPoolExecutor(2, 2, 30, TimeUnit.SECONDS, new ArrayBlockingQueue<>(64), r -> {
            Thread t = new Thread(r, "YouranMod 验证码本机端口");
            t.setDaemon(true);
            return t;
        }, new ThreadPoolExecutor.CallerRunsPolicy());
        s.setExecutor(executor);
        s.createContext("/", this::handle);
        s.start();
        this.server = s;
        this.port = s.getAddress().getPort();
    }

    public synchronized int port() {
        return port;
    }

    public synchronized void stop() {
        if (server != null) {
            server.stop(0);
            server = null;
        }
        if (executor != null) {
            executor.shutdownNow();
            executor = null;
        }
    }

    private void handle(HttpExchange ex) throws IOException {
        try {
            HttpResponse r = respond(ex);
            byte[] body = r.body;
            for (moe.qingu.youran.api.network.http.HttpHeaders.Field f : r.headers) {
                ex.getResponseHeaders().add(f.name, f.value);
            }
            ex.getResponseHeaders().set("Connection", "close");
            ex.sendResponseHeaders(r.status, body.length == 0 ? -1 : body.length);
            if (body.length > 0) {
                try (OutputStream o = ex.getResponseBody()) {
                    o.write(body);
                }
            }
        } finally {
            ex.close();
        }
    }

    HttpResponse respond(HttpExchange ex) throws IOException {
        if (!localHost(ex.getRequestHeaders().getFirst("Host"))) {
            return CaptchaService.text(403, "forbidden");
        }
        String path = ex.getRequestURI().getRawPath();
        if (!path.equals("/new") && !path.equals("/verify")) {
            return CaptchaService.text(404, "not found");
        }
        if (!ex.getRequestMethod().equals("POST")) {
            return CaptchaService.text(405, "method not allowed").withHeader("Allow", "POST");
        }
        long now = clock.getAsLong();
        if (!limit.tryAcquire(SOURCE, now)) {
            return CaptchaService.text(429, "too many requests");
        }
        byte[] body = readBody(ex.getRequestBody());
        if (body == null) {
            return CaptchaService.text(413, "too large");
        }
        if (path.equals("/new")) {
            return service.create(SOURCE, CaptchaStore.Pool.LOCAL, now);
        }
        Map<String, String> form = FormData.parse(new String(body, StandardCharsets.ISO_8859_1));
        return service.verify(form.get("id"), form.get("answer"), now);
    }

    private static byte[] readBody(InputStream in) throws IOException {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        byte[] buf = new byte[1024];
        int n;
        while ((n = in.read(buf)) > 0) {
            out.write(buf, 0, n);
            if (out.size() > MAX_BODY) {
                return null;
            }
        }
        return out.toByteArray();
    }

    /** Host 是 127.0.0.1、localhost 或 [::1]（可以带端口） */
    static boolean localHost(String host) {
        if (host == null) {
            return false;
        }
        String h = host.trim().toLowerCase(Locale.ROOT);
        if (h.startsWith("[::1]")) {
            h = "::1" + h.substring(5);
            return h.equals("::1") || h.matches("::1:[0-9]{1,5}");
        }
        int colon = h.indexOf(':');
        String name = colon < 0 ? h : h.substring(0, colon);
        if (colon >= 0 && !h.substring(colon + 1).matches("[0-9]{1,5}")) {
            return false;
        }
        return name.equals("127.0.0.1") || name.equals("localhost");
    }
}
