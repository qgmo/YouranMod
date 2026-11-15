package moe.qingu.youran.server.net.nav;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import moe.qingu.youran.api.network.stack.DnsResolver;
import moe.qingu.youran.api.network.stack.HttpClient;
import moe.qingu.youran.api.network.stack.NetStack;

import java.io.UnsupportedEncodingException;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;

/**
 * platform.180.yr 当 {@code id.yr}（游戏角色一键登录，YouranAuth 提供）的普通 OAuth 网站：授权码 + PKCE（S256），不预先注册，
 * {@code client_id} 就是 {@code http(s)://platform.180.yr/}。兑换走游戏内网络（这台主机的协议栈），不在代码上依赖 YouranAuth。
 * 做法同 pay.yr 的 {@code IdClient}。
 */
final class NavIdClient {
    static final long TOKEN_TIMEOUT_MS = 10_000;

    /** 兑换出来的人 */
    static final class Identity {
        final UUID uuid;
        final String name;

        Identity(UUID uuid, String name) {
            this.uuid = uuid;
            this.name = name;
        }
    }

    private final NetStack stack;
    private final DnsResolver resolver;
    private final String domain;
    /** 后台兑换用 https（服务器配了中间 CA） */
    private final boolean tls;

    /** 自己的域名（client_id 的主机） */
    private final String self;

    NavIdClient(NetStack stack, DnsResolver resolver, String domain, boolean tls, String self) {
        this.self = self;
        this.stack = stack;
        this.resolver = resolver;
        this.domain = domain;
        this.tls = tls;
    }

    String domain() {
        return domain;
    }

    String clientId(String scheme) {
        return scheme + "://" + self + "/";
    }

    String redirectUri(String scheme) {
        return scheme + "://" + self + "/login/callback";
    }

    /** 把浏览器跳到哪里（{@code scheme} 是玩家打开 pay.yr 用的协议，id.yr 也用同样的） */
    String authorizeUrl(String scheme, String state, String verifier) {
        return scheme + "://" + domain + "/authorize?response_type=code&client_id=" + enc(clientId(scheme))
                + "&redirect_uri=" + enc(redirectUri(scheme)) + "&state=" + enc(state)
                + "&code_challenge=" + challenge(verifier) + "&code_challenge_method=S256";
    }

    /** 跳回来的 {@code iss} 对不对（RFC 9207；没带时不查） */
    boolean issuerOk(String iss) {
        return iss == null || iss.equals("https://" + domain) || iss.equals("http://" + domain);
    }

    /** 后台兑换授权码（在网络线程上发请求，不阻塞） */
    CompletableFuture<Identity> exchange(String scheme, String code, String verifier) {
        String body = "grant_type=authorization_code&code=" + enc(code) + "&client_id=" + enc(clientId(scheme))
                + "&redirect_uri=" + enc(redirectUri(scheme)) + "&code_verifier=" + enc(verifier);
        HttpClient.Url url = HttpClient.Url.parse((tls ? "https://" : "http://") + domain + "/token");
        Map<String, String> h = new LinkedHashMap<>();
        h.put("Content-Type", "application/x-www-form-urlencoded");
        h.put("Accept", "application/json");
        return HttpClient.send(stack, resolver, url, "POST", h, body.getBytes(StandardCharsets.US_ASCII), TOKEN_TIMEOUT_MS, null)
                .thenApply(r -> {
                    JsonObject o;
                    try {
                        JsonElement e = new JsonParser().parse(r.bodyText());
                        o = e.getAsJsonObject();
                    } catch (RuntimeException ex) {
                        throw new IllegalStateException("id.yr 回的不是 JSON（HTTP " + r.status + "）");
                    }
                    if (r.status != 200 || !o.has("uuid") || !o.has("name")) {
                        throw new IllegalStateException("id.yr 拒绝兑换：" + (o.has("error") ? o.get("error").getAsString() : "HTTP " + r.status));
                    }
                    return new Identity(UUID.fromString(o.get("uuid").getAsString()), o.get("name").getAsString());
                });
    }

    static String challenge(String verifier) {
        try {
            byte[] d = MessageDigest.getInstance("SHA-256").digest(verifier.getBytes(StandardCharsets.US_ASCII));
            return Base64.getUrlEncoder().withoutPadding().encodeToString(d);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }

    static String enc(String s) {
        try {
            return URLEncoder.encode(s, "UTF-8");
        } catch (UnsupportedEncodingException e) {
            throw new IllegalStateException(e);
        }
    }
}
