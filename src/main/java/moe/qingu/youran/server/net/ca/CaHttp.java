package moe.qingu.youran.server.net.ca;

import com.google.gson.GsonBuilder;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParseException;
import com.google.gson.JsonParser;
import moe.qingu.youran.api.network.ca.CaException;
import moe.qingu.youran.api.network.ca.CertificateOrder;
import moe.qingu.youran.api.network.ca.CertificateRequest;
import moe.qingu.youran.api.network.ca.Challenge;
import moe.qingu.youran.api.network.http.HttpRequest;
import moe.qingu.youran.api.network.http.HttpResponse;
import moe.qingu.youran.api.network.http.HttpServer;
import moe.qingu.youran.api.network.stack.NetStack;
import moe.qingu.youran.api.network.tls.Pem;
import moe.qingu.youran.api.network.tls.TlsServerContext;
import moe.qingu.youran.server.net.service.captcha.SourceLimiter;

import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.cert.X509Certificate;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.function.LongSupplier;

/**
 * 证书服务的 HTTP 接口（{@code ca.yr} 的 80 与 443 口），几个 JSON 接口，格式见 {@code api.network.ca} 的包说明。
 * 按来源地址限频：每个来源每秒 2 个请求（最多攒 30 个）；申请另按 {@link CaService} 的规矩限。
 */
public final class CaHttp {
    static final int MAX_BODY = 16 * 1024;
    static final String JSON = "application/json; charset=utf-8";

    private final CaService service;
    private final LongSupplier clock;
    private final SourceLimiter requestLimit = new SourceLimiter(2, 30, 4096);

    public CaHttp(CaService service, LongSupplier clock) {
        this.service = service;
        this.clock = clock;
    }

    /** 在 {@code stack} 上开 80 口与（{@code tls} 不为 null 时）443 口 */
    public void listen(NetStack stack, TlsServerContext tls) {
        HttpServer.Options o = new HttpServer.Options().maxBodyBytes(MAX_BODY).handlerTimeoutMillis(CaService.VALIDATION_TIMEOUT_MS + 5_000)
                .errorPages(st -> error(st, st == 413 ? "badRequest" : "httpError", "HTTP " + st + " " + HttpResponse.reasonPhrase(st)));
        HttpServer.listen(stack, 80, null, o, this::handle);
        if (tls != null) {
            HttpServer.listen(stack, 443, tls, o, this::handle);
        }
    }

    /** 网络线程上：分发请求 */
    public CompletableFuture<HttpResponse> handle(HttpRequest req) {
        String path = req.path;
        boolean get = req.method.equals("GET") || req.method.equals("HEAD");
        boolean post = req.method.equals("POST");
        if (path.equals("/")) {
            return done(get ? home() : notAllowed("GET, HEAD"));
        }
        if (!requestLimit.tryAcquire(String.valueOf(req.remoteAddress), clock.getAsLong())) {
            return done(error(429, CaException.Code.RATE_LIMITED.id, "请求太频繁，过一会儿再来"));
        }
        if (path.equals("/orders")) {
            if (!post) {
                return done(notAllowed("POST"));
            }
            return newOrder(req);
        }
        if (path.startsWith("/orders/")) {
            String rest = path.substring("/orders/".length());
            int slash = rest.indexOf('/');
            String id = slash < 0 ? rest : rest.substring(0, slash);
            String action = slash < 0 ? "" : rest.substring(slash + 1);
            switch (action) {
                case "":
                    return get ? reply(service.order(id), 200) : done(notAllowed("GET, HEAD"));
                case "ready":
                    return post ? ready(id, req) : done(notAllowed("POST"));
                case "certificate":
                    return get ? certificate(id) : done(notAllowed("GET, HEAD"));
                default:
                    break;
            }
        }
        return done(error(404, CaException.Code.NOT_FOUND.id, "没有这个接口"));
    }

    private CompletableFuture<HttpResponse> newOrder(HttpRequest req) {
        JsonObject body;
        List<String> names = new ArrayList<>();
        byte[] csr;
        try {
            body = parse(req);
            JsonElement n = body.get("names");
            if (n == null || !n.isJsonArray()) {
                throw new CaException(CaException.Code.BAD_REQUEST, "要有 names（名字的数组）");
            }
            for (JsonElement e : n.getAsJsonArray()) {
                if (!e.isJsonPrimitive() || !e.getAsJsonPrimitive().isString()) {
                    throw new CaException(CaException.Code.BAD_REQUEST, "names 里要是字符串");
                }
                names.add(e.getAsString());
            }
            JsonElement k = body.get("csr");
            if (k == null || !k.isJsonPrimitive() || !k.getAsJsonPrimitive().isString()) {
                throw new CaException(CaException.Code.BAD_REQUEST, "要有 csr（证书请求，PEM 或 DER 的 base64）");
            }
            csr = CertificateRequest.decode(k.getAsString());
        } catch (CaException e) {
            return done(error(e));
        }
        return reply(service.newOrder(req.remoteAddress, names, csr), 201);
    }

    private CompletableFuture<HttpResponse> ready(String id, HttpRequest req) {
        Challenge.Method m;
        try {
            JsonElement e = parse(req).get("method");
            m = e != null && e.isJsonPrimitive() ? Challenge.Method.of(e.getAsString()) : null;
            if (m == null) {
                throw new CaException(CaException.Code.BAD_REQUEST, "method 要是 \"http\" 或 \"dns\"");
            }
        } catch (CaException e) {
            return done(error(e));
        }
        return reply(service.notifyReady(id, m), 202);
    }

    private CompletableFuture<HttpResponse> certificate(String id) {
        return service.order(id).handle((o, err) -> {
            if (err != null) {
                return error(unwrap(err));
            }
            if (o.status != CertificateOrder.Status.VALID) {
                return error(409, CaException.Code.WRONG_STATE.id, "证书还没签好（订单现在是 " + o.status.id + "）");
            }
            JsonObject j = new JsonObject();
            StringBuilder pem = new StringBuilder();
            try {
                for (X509Certificate c : o.certificate) {
                    pem.append(Pem.encode("CERTIFICATE", c.getEncoded()));
                }
            } catch (GeneralSecurityException e) {
                return error(500, "httpError", "证书编码失败");
            }
            X509Certificate leaf = o.certificate.get(0);
            j.addProperty("certificate", pem.toString());
            j.addProperty("serial", leaf.getSerialNumber().toString(16));
            j.addProperty("notBefore", leaf.getNotBefore().toInstant().toString());
            j.addProperty("notAfter", leaf.getNotAfter().toInstant().toString());
            return json(200, j);
        });
    }

    private CompletableFuture<HttpResponse> reply(CompletableFuture<CertificateOrder> f, int status) {
        return f.handle((o, err) -> err != null ? error(unwrap(err)) : json(status, orderJson(o)));
    }

    /** 订单的 JSON（接口说明里的格式） */
    static JsonObject orderJson(CertificateOrder o) {
        JsonObject j = new JsonObject();
        j.addProperty("id", o.id);
        j.addProperty("status", o.status.id);
        JsonArray names = new JsonArray();
        for (String n : o.names) {
            names.add(n);
        }
        j.add("names", names);
        j.addProperty("expires", Instant.ofEpochMilli(o.expires).toString());
        JsonArray cs = new JsonArray();
        for (Challenge c : o.challenges) {
            JsonObject x = new JsonObject();
            x.addProperty("name", c.name);
            JsonObject http = new JsonObject();
            http.addProperty("url", c.httpUrl);
            http.addProperty("token", c.token);
            http.addProperty("content", c.httpContent);
            x.add("http", http);
            JsonObject dns = new JsonObject();
            dns.addProperty("name", c.dnsName);
            dns.addProperty("value", c.dnsValue);
            x.add("dns", dns);
            cs.add(x);
        }
        j.add("challenges", cs);
        if (o.method != null) {
            j.addProperty("method", o.method.id);
        }
        if (o.error != null) {
            j.addProperty("error", o.error);
        }
        if (o.status == CertificateOrder.Status.VALID) {
            j.addProperty("certificate", "/orders/" + o.id + "/certificate");
        }
        return j;
    }

    static JsonObject parse(HttpRequest req) throws CaException {
        String text = new String(req.body, StandardCharsets.UTF_8);
        try {
            JsonElement e = new JsonParser().parse(text);
            if (e == null || !e.isJsonObject()) {
                throw new CaException(CaException.Code.BAD_REQUEST, "正文要是一个 JSON 对象");
            }
            return e.getAsJsonObject();
        } catch (JsonParseException | IllegalStateException e) {
            throw new CaException(CaException.Code.BAD_REQUEST, "正文不是 JSON");
        }
    }

    private HttpResponse home() {
        String reason = service.unavailableReason();
        String state = reason == null ? "服务开着。能签的名字：" + esc(service.constraintsText()) + "。" : "服务没开：" + esc(reason) + "。";
        String page = "<?xml version=\"1.0\" encoding=\"UTF-8\"?>\n"
                + "<html xmlns=\"http://www.w3.org/1999/xhtml\" xml:lang=\"zh-CN\"><head><title>ca.yr</title></head><body>\n"
                + "<h1>ca.yr</h1>\n<p>游戏内网站的证书签发服务。" + state + "</p>\n"
                + "<p>申请交证书请求（CSR，PKCS#10）。接口：POST /orders、GET /orders/{订单号}、POST /orders/{订单号}/ready、GET /orders/{订单号}/certificate。</p>\n"
                + "</body></html>\n";
        return HttpResponse.xhtml(200, page);
    }

    static String esc(String s) {
        return s == null ? "" : s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;");
    }

    static Throwable unwrap(Throwable t) {
        return t instanceof CompletionException && t.getCause() != null ? t.getCause() : t;
    }

    static HttpResponse error(Throwable t) {
        if (t instanceof CaException) {
            CaException e = (CaException) t;
            return error(e.code.httpStatus, e.code.id, e.getMessage());
        }
        return error(500, "httpError", "证书服务出错：" + t);
    }

    static HttpResponse error(int status, String code, String message) {
        JsonObject j = new JsonObject();
        j.addProperty("error", code);
        j.addProperty("message", message);
        return json(status, j);
    }

    static HttpResponse notAllowed(String allow) {
        return error(405, "httpError", "只收 " + allow).withHeader("Allow", allow);
    }

    static HttpResponse json(int status, JsonObject j) {
        String s = new GsonBuilder().setPrettyPrinting().disableHtmlEscaping().create().toJson(j) + "\n";
        return HttpResponse.of(status, JSON, s.getBytes(StandardCharsets.UTF_8));
    }

    static CompletableFuture<HttpResponse> done(HttpResponse r) {
        return CompletableFuture.completedFuture(r);
    }
}
