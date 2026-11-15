package moe.qingu.youran.server.net.location;

import moe.qingu.youran.api.network.Ipv6Address;
import moe.qingu.youran.api.network.http.HttpRequest;
import moe.qingu.youran.api.network.http.HttpResponse;
import moe.qingu.youran.api.network.http.HttpServer;
import moe.qingu.youran.api.network.location.LocationVerifier;
import moe.qingu.youran.api.network.stack.NetStack;
import moe.qingu.youran.api.network.tls.TlsServerContext;
import moe.qingu.youran.server.net.service.captcha.SourceLimiter;

import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.function.LongSupplier;

/**
 * 定位模块的公钥与核对服务 {@code location.yr}（YouranMod 机房里的一台主机），纯文本接口：
 * <pre>
 *   GET  /key（或 /.well-known/location-key） → 200 text/plain，签名公钥（PEM）
 *   POST /verify（表单 proof=证明全文，可选 site=、device=） → 200 text/plain，正文 ok 或 fail &lt;原因&gt;
 *   GET  /                                   → 用法说明（XHTML）
 * </pre>
 * {@code /verify} 核对签名、证明不超过 {@value #MAX_AGE_SECONDS} 秒、给了 {@code site} / {@code device} 时与它们一致；
 * <b>随机数是不是你发的、有没有用过要网站自己核对</b>（这里不知道）。原因：format、signature、site、device、expired。
 */
public final class LocationService {
    public static final int PORT = 80;
    public static final int TLS_PORT = 443;
    public static final long MAX_AGE_SECONDS = 300;

    private final LocationSigner signer;
    private final LongSupplier clock;
    private final SourceLimiter limit = new SourceLimiter(5, 20, 4096);

    public LocationService(LocationSigner signer, LongSupplier clockMillis) {
        this.signer = signer;
        this.clock = clockMillis;
    }

    public void start(NetStack stack, TlsServerContext tls) {
        HttpServer.Options o = new HttpServer.Options().maxBodyBytes(4096).maxHeaderBytes(8 * 1024).handlerTimeoutMillis(5_000);
        HttpServer.listen(stack, PORT, null, o, this::handle);
        if (tls != null) {
            HttpServer.listen(stack, TLS_PORT, tls, o, this::handle);
        }
    }

    CompletableFuture<HttpResponse> handle(HttpRequest req) {
        return CompletableFuture.completedFuture(respond(req));
    }

    HttpResponse respond(HttpRequest req) {
        long now = clock.getAsLong();
        if (!limit.tryAcquire(String.valueOf(req.remoteAddress), now)) {
            return text(429, "too many requests");
        }
        String path = req.path;
        boolean get = req.method.equals("GET") || req.method.equals("HEAD");
        if (path.equals("/key") || path.equals("/.well-known/location-key")) {
            return get ? text(200, signer.publicKeyPem()) : notAllowed("GET, HEAD");
        }
        if (path.equals("/verify")) {
            if (!req.method.equals("POST")) {
                return notAllowed("POST");
            }
            Map<String, String> form = req.form();
            return text(200, verify(form.get("proof"), blankToNull(form.get("site")), blankToNull(form.get("device")), now / 1000).toString());
        }
        if (path.equals("/")) {
            return get ? HttpResponse.xhtml(200, LocationPages.index(signer.keyId())) : notAllowed("GET, HEAD");
        }
        return text(404, "not found");
    }

    LocationVerifier.Result verify(String proof, String site, String device, long nowSeconds) {
        Ipv6Address source = null;
        if (device != null) {
            try {
                source = Ipv6Address.parse(device);
            } catch (RuntimeException e) {
                return new LocationVerifier(signer.publicKey(), MAX_AGE_SECONDS).verify("", null, null, null, nowSeconds);
            }
        }
        return new LocationVerifier(signer.publicKey(), MAX_AGE_SECONDS).verify(proof, site, source, null, nowSeconds);
    }

    private static String blankToNull(String s) {
        return s == null || s.trim().isEmpty() ? null : s.trim();
    }

    static HttpResponse text(int status, String body) {
        return HttpResponse.of(status, "text/plain; charset=utf-8", body.getBytes(StandardCharsets.UTF_8));
    }

    private static HttpResponse notAllowed(String allow) {
        return text(405, "method not allowed").withHeader("Allow", allow);
    }
}
