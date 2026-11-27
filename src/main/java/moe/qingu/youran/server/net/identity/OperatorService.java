package moe.qingu.youran.server.net.identity;

import moe.qingu.youran.api.network.Ipv6Address;
import moe.qingu.youran.api.network.http.HttpRequest;
import moe.qingu.youran.api.network.http.HttpResponse;
import moe.qingu.youran.api.network.http.HttpServer;
import moe.qingu.youran.api.network.identity.OperatorVerifier;
import moe.qingu.youran.api.network.stack.NetStack;
import moe.qingu.youran.api.network.tls.TlsServerContext;
import moe.qingu.youran.server.net.service.captcha.SourceLimiter;

import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.function.LongSupplier;

/**
 * 身份模块的公钥与核对服务 {@code operator.yr}（YouranMod 机房里的一台主机；同 {@code location.yr} 的做法），纯文本接口：
 * <pre>
 *   GET  /key（或 /.well-known/operator-key） → 200 text/plain，签名公钥（PEM）
 *   POST /verify（表单 proof=证明全文，可选 site=、address=） → 200 text/plain，正文 ok 或 fail &lt;原因&gt;
 *   GET  /                                   → 用法说明（XHTML）
 * </pre>
 * {@code /verify} 核对签名、证明不超过 {@value #MAX_AGE_SECONDS} 秒、给了 {@code site} / {@code address} 时与它们一致；
 * <b>随机数是不是你发的、有没有用过要网站自己核对</b>。原因：format、signature、site、address、expired。
 */
public final class OperatorService {
    public static final int PORT = 80;
    public static final int TLS_PORT = 443;
    public static final long MAX_AGE_SECONDS = 300;

    private final OperatorSigner signer;
    private final LongSupplier clock;
    private final SourceLimiter limit = new SourceLimiter(5, 20, 4096);

    public OperatorService(OperatorSigner signer, LongSupplier clockMillis) {
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
        if (path.equals("/key") || path.equals("/.well-known/operator-key")) {
            return get ? text(200, signer.publicKeyPem()) : notAllowed("GET, HEAD");
        }
        if (path.equals("/verify")) {
            if (!req.method.equals("POST")) {
                return notAllowed("POST");
            }
            Map<String, String> form = req.form();
            return text(200, verify(form.get("proof"), blankToNull(form.get("site")), blankToNull(form.get("address")), now / 1000).toString());
        }
        if (path.equals("/")) {
            return get ? HttpResponse.xhtml(200, OperatorPages.index(signer.keyId())) : notAllowed("GET, HEAD");
        }
        return text(404, "not found");
    }

    OperatorVerifier.Result verify(String proof, String site, String address, long nowSeconds) {
        OperatorVerifier v = new OperatorVerifier(signer.publicKey(), MAX_AGE_SECONDS);
        Ipv6Address source = null;
        if (address != null) {
            try {
                source = Ipv6Address.parse(address);
            } catch (RuntimeException e) {
                return v.verify("", null, null, null, nowSeconds);
            }
        }
        return v.verify(proof, site, source, null, nowSeconds);
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
