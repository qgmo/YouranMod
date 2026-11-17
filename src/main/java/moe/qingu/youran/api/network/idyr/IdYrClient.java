package moe.qingu.youran.api.network.idyr;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import moe.qingu.youran.api.network.ConfirmRequest;
import moe.qingu.youran.api.network.Ipv6Address;
import moe.qingu.youran.api.network.YouranNetworkApi;
import moe.qingu.youran.api.network.stack.DnsResolver;
import moe.qingu.youran.api.network.stack.HttpClient;
import moe.qingu.youran.api.network.stack.NetStack;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.io.UnsupportedEncodingException;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Base64;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;

/**
 * 网站当 {@code id.yr}（游戏角色一键登录，YouranAuth 提供）的客户端：登录（OAuth 授权码 + PKCE S256，不预先注册，{@code client_id} 是网站自己的网址）、
 * 凭访问令牌现查信息、<b>弹窗确认</b>与<b>验证身份</b>（授权范围 {@code confirm}、{@code verify}，接口见 {@code id.yr/docs/confirm}）、
 * <b>追加授权</b>（已登录后再要范围，例如玩家打开“操作前必须验证身份”时才要 {@code verify}：{@link Ask#scope}，同意后 {@link Outcome#token} 是新令牌）。
 * 请求都走游戏内网络（网站这台主机的协议栈），在代码上不依赖 YouranAuth。pay.yr、platform.180.yr、MineBay 都用它，玩家自己的 Java 网站也可以用。
 * <p>
 * 弹窗确认 / 验证身份的用法：后台 {@link #ask} 建请求 → 把浏览器 303 到 {@link Ticket#url} → id.yr 弹框、跳回 {@link Ask#redirectUri}
 * → 后台 {@link #result} 核对（结果只能取一次；跳回网址里的结果谁都能伪造，动钱前一定要核对）。
 * <p>
 * 方法都在网络线程上发请求，不阻塞。测试里可以继承它、换掉 {@link #ask}、{@link #result}。
 */
public class IdYrClient {
    public static final String DEFAULT_DOMAIN = "id.yr";
    public static final long TIMEOUT_MS = 10_000;
    /** 授权范围：弹窗确认、验证身份 */
    public static final String CONFIRM = "confirm";
    public static final String VERIFY = "verify";

    /** 兑换出来的：角色与访问令牌 */
    public static final class Token {
        public final UUID uuid;
        public final String name;
        public final String accessToken;
        /** 令牌什么时候过期（毫秒） */
        public final long expiresAt;
        public final Set<String> scopes;

        public Token(UUID uuid, String name, String accessToken, long expiresAt, Set<String> scopes) {
            this.uuid = uuid;
            this.name = name;
            this.accessToken = accessToken;
            this.expiresAt = expiresAt;
            this.scopes = Collections.unmodifiableSet(new LinkedHashSet<>(scopes));
        }

        public boolean has(String scope) {
            return scopes.contains(scope);
        }
    }

    /** 要弹的框 */
    public static final class Ask {
        /** 验证身份（只有令牌对应的角色本人同意才通过）；false 为弹窗确认（谁坐在电脑前就问谁）或追加授权 */
        public final boolean verify;
        /** 追加授权：要加的范围（空格分隔；框里的字由 id.yr 定，坐在电脑前的人同意就算）；其他时候为 null */
        public final String scope;
        public final String title;
        public final List<String> items;
        public final ConfirmRequest.Purpose purpose;
        /** 这次浏览器请求的来源地址：只有这台电脑能打开 */
        public final Ipv6Address source;
        public final String redirectUri;
        public final String state;

        private Ask(boolean verify, String title, List<String> items, ConfirmRequest.Purpose purpose, Ipv6Address source, String redirectUri, String state) {
            this(verify, null, title, items, purpose, source, redirectUri, state);
        }

        private Ask(boolean verify, String scope, String title, List<String> items, ConfirmRequest.Purpose purpose, Ipv6Address source, String redirectUri,
                    String state) {
            this.verify = verify;
            this.scope = scope;
            this.title = title;
            this.items = Collections.unmodifiableList(new ArrayList<>(items));
            this.purpose = purpose;
            this.source = source;
            this.redirectUri = redirectUri;
            this.state = state;
        }

        /** 弹窗确认 */
        public static Ask confirm(String title, List<String> items, ConfirmRequest.Purpose purpose, Ipv6Address source, String redirectUri, String state) {
            return new Ask(false, title, items, purpose, source, redirectUri, state);
        }

        /** 验证身份（标题可以为 null：用 id.yr 的“确认你是 X”） */
        public static Ask verify(String title, List<String> items, ConfirmRequest.Purpose purpose, Ipv6Address source, String redirectUri, String state) {
            return new Ask(true, title, items, purpose, source, redirectUri, state);
        }

        /** 追加授权：请坐在电脑前的人同意这次登录再加 {@code scope}（如 {@link #VERIFY}） */
        public static Ask scope(String scope, Ipv6Address source, String redirectUri, String state) {
            return new Ask(false, scope, null, Collections.<String>emptyList(), ConfirmRequest.Purpose.SHARE, source, redirectUri, state);
        }

        /** {@code /confirm/new} 的 {@code type} */
        public String type() {
            return scope != null ? "scope" : verify ? "verify" : "confirm";
        }

        /** 同样的内容，按 {@code verify} 选弹窗确认还是验证身份 */
        public static Ask of(boolean verify, String title, List<String> items, ConfirmRequest.Purpose purpose, Ipv6Address source, String redirectUri,
                             String state) {
            return new Ask(verify, title, items, purpose, source, redirectUri, state);
        }
    }

    /** 建好的确认请求 */
    public static final class Ticket {
        public final String id;
        /** 把浏览器跳到这里 */
        public final String url;
        /** 签名密钥（留在后台） */
        public final String secret;

        public Ticket(String id, String url, String secret) {
            this.id = id;
            this.url = url;
            this.secret = secret;
        }

        /** 跳回来的网址里的签名对不对（只是提示；动钱前用 {@link #result} 核对） */
        public boolean signatureOk(String request, String result, String reason, String state, String sig) {
            return sig != null && id.equals(request) && MessageDigest.isEqual(sign(secret, request, result, reason, state).getBytes(StandardCharsets.US_ASCII),
                    sig.getBytes(StandardCharsets.US_ASCII));
        }
    }

    /** 核对出来的结果 */
    public static final class Outcome {
        public enum Status { PENDING, APPROVED, DENIED }

        public final Status status;
        /** 没通过的原因代号（denied、timeout、operator_changed、no_operator、no_capability、busy、expired、unavailable）；其他时候为 null */
        public final String reason;
        /** 打开它的电脑；还没结果时为 null */
        public final String source;
        /** 追加授权同意了：加了范围的新令牌（旧令牌已作废）；其他时候为 null */
        public final Token token;

        public Outcome(Status status, String reason, String source) {
            this(status, reason, source, null);
        }

        public Outcome(Status status, String reason, String source, Token token) {
            this.status = status;
            this.reason = reason;
            this.source = source;
            this.token = token;
        }

        public boolean approved() {
            return status == Status.APPROVED;
        }

        /** 给玩家看的一句（没通过时） */
        public String text(boolean verify) {
            return reasonText(reason, verify);
        }
    }

    private final NetStack stack;
    private final DnsResolver resolver;
    private final String domain;
    private final boolean tls;
    private final String self;

    /**
     * @param idDomain id.yr 的域名
     * @param tls      后台请求用 https（服务器配了中间 CA）
     * @param self     网站自己的域名（{@code client_id} 的主机）
     */
    public IdYrClient(NetStack stack, DnsResolver resolver, String idDomain, boolean tls, String self) {
        this.stack = stack;
        this.resolver = resolver;
        this.domain = idDomain == null ? DEFAULT_DOMAIN : idDomain.trim().toLowerCase(Locale.ROOT);
        this.tls = tls;
        this.self = self;
        this.names = stack == null || resolver == null ? null : new moe.qingu.youran.api.network.stack.NameCache(resolver, stack.loop());
    }

    /** id.yr 解析得到没有（{@link #available}） */
    private final moe.qingu.youran.api.network.stack.NameCache names;

    /** 用游戏内网络的递归解析器 */
    public static IdYrClient of(NetStack stack, String idDomain, boolean tls, String self) {
        return new IdYrClient(stack, stack == null ? null : new DnsResolver(stack, YouranNetworkApi.resolverAddress()), idDomain, tls, self);
    }

    /**
     * 这个服务器开着 id.yr（装了 YouranAuth 并开了 ingame-id）：id.yr 的名字经递归解析器解析得到地址。按 TTL 缓存上一次的回答
     * （{@link moe.qingu.youran.api.network.stack.NameCache}），到期在后台再问；第一次调用时还没有回答，给 false 并开始问，
     * 所以网站开起来时先调用一次。没有协议栈时为 false。任意线程
     */
    public boolean available() {
        moe.qingu.youran.api.network.stack.NameCache n = names;
        return n != null && n.resolves(domain);
    }

    public String domain() {
        return domain;
    }

    public String clientId(String scheme) {
        return scheme + "://" + self + "/";
    }

    public String redirectUri(String scheme) {
        return scheme + "://" + self + "/login/callback";
    }

    /** 登录：把浏览器跳到哪里（{@code scheme} 是玩家打开网站用的协议，id.yr 也用同样的）；{@code scope} 空格分隔，可以为 null */
    public String authorizeUrl(String scheme, String state, String verifier, String scope) {
        return scheme + "://" + domain + "/authorize?response_type=code&client_id=" + enc(clientId(scheme)) + "&redirect_uri=" + enc(redirectUri(scheme))
                + "&state=" + enc(state) + "&code_challenge=" + challenge(verifier) + "&code_challenge_method=S256"
                + (scope == null || scope.isEmpty() ? "" : "&scope=" + enc(scope));
    }

    /** 跳回来的 {@code iss} 对不对（RFC 9207；没带时不查） */
    public boolean issuerOk(String iss) {
        return iss == null || iss.equals("https://" + domain) || iss.equals("http://" + domain);
    }

    private HttpClient.Url url(String path) {
        return HttpClient.Url.parse((tls ? "https://" : "http://") + domain + path);
    }

    private CompletableFuture<HttpClient.Response> post(String path, String accessToken, String form) {
        Map<String, String> h = new LinkedHashMap<>();
        h.put("Content-Type", "application/x-www-form-urlencoded");
        h.put("Accept", "application/json");
        if (accessToken != null) {
            h.put("Authorization", "Bearer " + accessToken);
        }
        return HttpClient.send(stack, resolver, url(path), "POST", h, form.getBytes(StandardCharsets.UTF_8), TIMEOUT_MS, null);
    }

    /** 后台兑换授权码 */
    public CompletableFuture<Token> exchange(String scheme, String code, String verifier) {
        String body = "grant_type=authorization_code&code=" + enc(code) + "&client_id=" + enc(clientId(scheme)) + "&redirect_uri=" + enc(redirectUri(scheme))
                + "&code_verifier=" + enc(verifier);
        long now = System.currentTimeMillis();
        return post("/token", null, body).thenApply(r -> {
            JsonObject o = json(r);
            if (r.status != 200 || !o.has("uuid") || !o.has("name") || !o.has("access_token")) {
                throw new IdYrException(r.status, err(o, r.status), "id.yr 拒绝兑换：" + err(o, r.status));
            }
            return token(o, now);
        });
    }

    /** 凭访问令牌现查信息（JSON）；令牌失效时以 {@link IdYrException#tokenExpired()} 为真的异常结束 */
    public CompletableFuture<JsonObject> userinfo(String accessToken) {
        Map<String, String> h = new LinkedHashMap<>();
        h.put("Authorization", "Bearer " + accessToken);
        h.put("Accept", "application/json");
        return HttpClient.send(stack, resolver, url("/userinfo"), "GET", h, null, TIMEOUT_MS, null).thenApply(r -> {
            JsonObject o = json(r);
            if (r.status != 200) {
                throw new IdYrException(r.status, err(o, r.status), "id.yr 查不了信息：" + err(o, r.status));
            }
            return o;
        });
    }

    /** 建一个确认请求（令牌要有 {@code confirm} / {@code verify} 范围） */
    public CompletableFuture<Ticket> ask(String accessToken, Ask ask) {
        StringBuilder b = new StringBuilder();
        b.append("type=").append(ask.type());
        if (ask.scope != null) {
            b.append("&scope=").append(enc(ask.scope));
        }
        if (ask.title != null) {
            b.append("&title=").append(enc(ask.title));
        }
        for (int i = 0; i < ask.items.size(); i++) {
            b.append("&item").append(i + 1).append('=').append(enc(ask.items.get(i)));
        }
        b.append(ask.scope != null ? "" : "&purpose=" + purposeCode(ask.purpose)).append("&source=").append(enc(String.valueOf(ask.source)))
                .append("&redirect_uri=").append(enc(ask.redirectUri));
        if (ask.state != null) {
            b.append("&state=").append(enc(ask.state));
        }
        return post("/confirm/new", accessToken, b.toString()).thenApply(r -> {
            JsonObject o = json(r);
            if (r.status != 201 || !o.has("id") || !o.has("url") || !o.has("secret")) {
                throw new IdYrException(r.status, err(o, r.status), "id.yr 不肯弹确认框：" + err(o, r.status));
            }
            return new Ticket(o.get("id").getAsString(), o.get("url").getAsString(), o.get("secret").getAsString());
        });
    }

    /** 核对结果（有结果后只能取一次；追加授权同意时带新令牌） */
    public CompletableFuture<Outcome> result(String accessToken, String requestId) {
        long now = System.currentTimeMillis();
        return post("/confirm/result", accessToken, "request=" + enc(requestId)).thenApply(r -> {
            JsonObject o = json(r);
            if (r.status != 200 || !o.has("status")) {
                throw new IdYrException(r.status, err(o, r.status), "id.yr 核对不了确认结果：" + err(o, r.status));
            }
            return parseOutcome(o, now);
        });
    }

    /** {@code /confirm/result} 的 JSON → 结果 */
    public static Outcome parseOutcome(JsonObject o) {
        return parseOutcome(o, System.currentTimeMillis());
    }

    static Outcome parseOutcome(JsonObject o, long now) {
        String st = o.get("status").getAsString();
        Outcome.Status s = "approved".equals(st) ? Outcome.Status.APPROVED : "pending".equals(st) ? Outcome.Status.PENDING : Outcome.Status.DENIED;
        Token t = s == Outcome.Status.APPROVED && o.has("access_token") ? token(o, now) : null;
        return new Outcome(s, s == Outcome.Status.DENIED ? (o.has("reason") ? o.get("reason").getAsString() : "denied") : null,
                o.has("source") ? o.get("source").getAsString() : null, t);
    }

    /** {@code /token} 或追加授权结果里的令牌 */
    static Token token(JsonObject o, long now) {
        if (!o.has("uuid") || !o.has("name") || !o.has("access_token")) {
            throw new IdYrException(200, "bad_response", "id.yr 给的令牌不完整");
        }
        long ttl = o.has("expires_in") ? o.get("expires_in").getAsLong() : 3600;
        Set<String> scopes = new LinkedHashSet<>();
        if (o.has("scope") && !o.get("scope").getAsString().trim().isEmpty()) {
            scopes.addAll(Arrays.asList(o.get("scope").getAsString().trim().split(" +")));
        }
        return new Token(UUID.fromString(o.get("uuid").getAsString()), o.get("name").getAsString(), o.get("access_token").getAsString(),
                now + Math.max(1, Math.min(86400, ttl)) * 1000L, scopes);
    }

    static String purposeCode(ConfirmRequest.Purpose p) {
        if (p == null) {
            return "action";
        }
        switch (p) {
            case PAY:
                return "pay";
            case RECEIVE:
                return "receive";
            case SHARE:
                return "share";
            default:
                return "action";
        }
    }

    private static JsonObject json(HttpClient.Response r) {
        try {
            JsonElement e = new JsonParser().parse(r.bodyText());
            return e.getAsJsonObject();
        } catch (RuntimeException ex) {
            throw new IdYrException(r.status, "bad_response", "id.yr 回的不是 JSON（HTTP " + r.status + "）");
        }
    }

    private static String err(JsonObject o, int status) {
        return o.has("error") ? o.get("error").getAsString() : "http_" + status;
    }

    /** 签名（同 id.yr）：BASE64URL(HMAC-SHA256(secret, "id.yr-confirm-v1\n" + request + "\n" + result + "\n" + reason + "\n" + state)) */
    public static String sign(String secret, String request, String result, String reason, String state) {
        String text = "id.yr-confirm-v1\n" + request + "\n" + result + "\n" + (reason == null ? "" : reason) + "\n" + (state == null ? "" : state);
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(secret.getBytes(StandardCharsets.US_ASCII), "HmacSHA256"));
            return Base64.getUrlEncoder().withoutPadding().encodeToString(mac.doFinal(text.getBytes(StandardCharsets.UTF_8)));
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException(e);
        }
    }

    /** id.yr 登录跳回来时带的错误 → 给玩家看的一句 */
    public static String loginErrorText(String error, String description) {
        switch (error) {
            case "login_unavailable":
                return "no_operator".equals(description) ? "这台电脑现在没人操作，不能登录。" : "这台电脑不能用游戏角色一键登录。";
            case "access_denied":
                if ("timeout".equals(description)) {
                    return "确认框超时了，没有登录。";
                }
                if ("operator_changed".equals(description)) {
                    return "确认时离开了电脑或换了人，没有登录。";
                }
                return "你在确认框里拒绝了登录。";
            case "temporarily_unavailable":
                return "busy".equals(description) ? "已经有一个确认框开着，先处理那个。" : "登录服务暂时不可用，请稍后再试。";
            default:
                return "登录失败（" + error + "）。";
        }
    }

    /** 确认没通过的原因 → 给玩家看的一句；{@code verify} 时是验证身份（拒绝与不是本人一样说） */
    public static String reasonText(String reason, boolean verify) {
        String r = reason == null ? "denied" : reason;
        switch (r) {
            case "denied":
                return verify ? "身份验证没有通过（拒绝了，或者不是帐号本人）。" : "你在确认框里点了拒绝。";
            case "timeout":
                return "确认框超时了。";
            case "operator_changed":
                return "确认时离开了电脑或换了人。";
            case "no_operator":
                return "这台电脑现在没人操作。";
            case "no_capability":
                return "这台电脑不能弹 id.yr 的确认框。";
            case "busy":
                return "已经有一个确认框开着，先处理那个。";
            case "expired":
                return "确认请求过期了，请重试。";
            default:
                return "确认服务暂时不可用，请稍后再试。";
        }
    }

    public static String challenge(String verifier) {
        try {
            byte[] d = MessageDigest.getInstance("SHA-256").digest(verifier.getBytes(StandardCharsets.US_ASCII));
            return Base64.getUrlEncoder().withoutPadding().encodeToString(d);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }

    public static String enc(String s) {
        try {
            return URLEncoder.encode(s, "UTF-8");
        } catch (UnsupportedEncodingException e) {
            throw new IllegalStateException(e);
        }
    }
}
