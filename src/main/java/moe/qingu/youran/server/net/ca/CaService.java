package moe.qingu.youran.server.net.ca;

import moe.qingu.youran.api.network.DomainNames;
import moe.qingu.youran.api.network.Ipv6Address;
import moe.qingu.youran.api.network.ca.CaException;
import moe.qingu.youran.api.network.ca.CertificateAuthority;
import moe.qingu.youran.api.network.ca.CertificateRequest;
import moe.qingu.youran.api.network.ca.CertificateOrder;
import moe.qingu.youran.api.network.ca.Challenge;
import moe.qingu.youran.api.network.stack.EventLoop;
import moe.qingu.youran.api.network.tls.CertificateTlsContext;
import moe.qingu.youran.server.net.service.captcha.SourceLimiter;
import moe.qingu.youran.server.net.tls.IntermediateCa;
import moe.qingu.youran.server.net.tls.NameConstraints;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.MessageDigest;
import java.security.PublicKey;
import java.security.cert.X509Certificate;
import java.security.interfaces.ECPublicKey;
import java.security.interfaces.RSAPublicKey;
import java.security.spec.ECGenParameterSpec;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Base64;
import java.util.Collection;
import java.util.Collections;
import java.util.Date;
import java.util.Deque;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.concurrent.CompletableFuture;
import java.util.function.LongSupplier;
import java.util.regex.Pattern;

/**
 * 证书签发服务（{@code ca.yr}）本体：订单、认证、签发，全在网络线程（{@code loop}）上做，订单只在内存里。
 * Java 接口直接调它；HTTP 接口（{@link CaHttp}）是它外面的一层。签出去的证书写日志，最近的留在内存里给 {@code /ynet ca} 看。
 */
public final class CaService implements CertificateAuthority {
    static final Logger LOG = LogManager.getLogger("YouranMod");
    static final Pattern DNS_NAME = Pattern.compile("[a-z0-9]([a-z0-9-]{0,61}[a-z0-9])?(\\.[a-z0-9]([a-z0-9-]{0,61}[a-z0-9])?)+");
    /** 订单从申请起过这么久就删掉 */
    static final long ORDER_TTL_MS = 3600_000L;
    /** 内存里最多这么多张订单 */
    static final int MAX_ORDERS = 1024;
    /** 通知就绪后最多等这么久，还没核对完就算失败 */
    static final long VALIDATION_TIMEOUT_MS = 30_000L;
    /** 内存里留最近这么多张签出去的证书 */
    static final int ISSUED_KEEP = 200;
    /** 每个来源最多一下申请这么多张，之后每 {@value #NEW_ORDER_EVERY_SECONDS} 秒补一张 */
    static final int NEW_ORDER_BURST = 10;
    static final int NEW_ORDER_EVERY_SECONDS = 360;
    static final long DAY_MS = 24L * 3600 * 1000;
    static final int RENEW_BEFORE_DAYS = 30;

    private final IntermediateCa ca;
    private final NameConstraints constraints;
    private final String unavailable;
    private final EventLoop loop;
    private final LongSupplier clock;
    private final Random random;
    private final SourceLimiter newOrderLimit = new SourceLimiter(1.0 / NEW_ORDER_EVERY_SECONDS, NEW_ORDER_BURST, 4096);
    private volatile Validator validator;
    /** 订单（只在网络线程上动） */
    private final Map<String, Order> orders = new LinkedHashMap<>();
    /** 签出去的证书（只在网络线程上动），新的在前 */
    private final Deque<Issued> issued = new ArrayDeque<>();
    private volatile long issuedTotal;

    /**
     * @param ca                中间 CA；没有时为 null，这时 {@code unavailableReason} 写为什么没有
     * @param unavailableReason 服务不开的原因（{@code ca} 为 null 时用）
     * @param random            订单号、令牌用的随机数（正式用 {@code SecureRandom}，测试用固定种子）
     */
    public CaService(IntermediateCa ca, String unavailableReason, EventLoop loop, LongSupplier clock, Random random) {
        this.ca = ca;
        this.constraints = ca == null ? null : NameConstraints.of(ca.chain());
        this.unavailable = ca == null ? (unavailableReason == null ? "服务器没配中间 CA" : unavailableReason) : null;
        this.loop = loop;
        this.clock = clock;
        this.random = random;
    }

    /** 网络线程上：核对认证用这台主机（{@code ca.yr}）的网络 */
    public void setValidator(Validator v) {
        this.validator = v;
    }

    @Override
    public String unavailableReason() {
        return unavailable;
    }

    /** 中间 CA 的域名约束（给人看）；服务没开为 null */
    public String constraintsText() {
        return constraints == null ? null : constraints.describe();
    }

    public IntermediateCa intermediate() {
        return ca;
    }

    @Override
    public List<X509Certificate> issuerChain() {
        return ca == null ? Collections.<X509Certificate>emptyList() : ca.chain();
    }

    // ---- Java 接口 ----

    @Override
    public CompletableFuture<CertificateOrder> newOrder(Ipv6Address requester, Collection<String> names, byte[] csr) {
        return onLoop(() -> create(requester, names, csr).snapshot());
    }

    @Override
    public CompletableFuture<CertificateOrder> order(String id) {
        return onLoop(() -> find(id).snapshot());
    }

    @Override
    public CompletableFuture<CertificateOrder> ready(String id, Challenge.Method method) {
        CompletableFuture<CertificateOrder> done = new CompletableFuture<>();
        onLoop(() -> {
            Order o = start(id, method);
            if (o.status == CertificateOrder.Status.PROCESSING) {
                o.waiters.add(done);
            } else {
                done.complete(o.snapshot());
            }
            return null;
        }).whenComplete((v, err) -> {
            if (err != null) {
                done.completeExceptionally(err instanceof java.util.concurrent.CompletionException && err.getCause() != null ? err.getCause() : err);
            }
        });
        return done;
    }

    /** 同 {@link #ready}，但不等结果：future 给出通知后的订单（一般是认证中）。HTTP 接口用 */
    public CompletableFuture<CertificateOrder> notifyReady(String id, Challenge.Method method) {
        return onLoop(() -> start(id, method).snapshot());
    }

    // ---- 订单 ----

    private Order create(Ipv6Address requester, Collection<String> rawNames, byte[] csr) throws CaException {
        if (unavailable != null) {
            throw new CaException(CaException.Code.UNAVAILABLE, "证书服务没开：" + unavailable);
        }
        if (rawNames == null || rawNames.isEmpty()) {
            throw new CaException(CaException.Code.BAD_REQUEST, "没有要签的名字");
        }
        LinkedHashSet<String> set = new LinkedHashSet<>();
        for (String raw : rawNames) {
            if (raw == null || raw.contains("*")) {
                throw new CaException(CaException.Code.BAD_REQUEST, "不签通配符名字：" + raw);
            }
            String n;
            try {
                n = DomainNames.toAscii(raw);
            } catch (IllegalArgumentException e) {
                throw new CaException(CaException.Code.BAD_REQUEST, e.getMessage());
            }
            if (!DNS_NAME.matcher(n).matches()) {
                throw new CaException(CaException.Code.BAD_REQUEST, "名字写法不对（要至少两级，只用字母、数字、连字符）：" + raw);
            }
            set.add(n);
        }
        if (set.size() > MAX_NAMES) {
            throw new CaException(CaException.Code.BAD_REQUEST, "一张证书最多 " + MAX_NAMES + " 个名字");
        }
        for (String n : set) {
            if (!constraints.permits(n)) {
                throw new CaException(CaException.Code.REJECTED_NAME, n + " 不在中间 CA 的域名约束之内（" + constraints.describe() + "），签了浏览器也不认");
            }
        }
        CertificateRequest req = CertificateRequest.parse(csr);
        checkNames(req, set);
        PublicKey key = req.publicKey;
        checkKey(key);
        long now = clock.getAsLong();
        String source = String.valueOf(requester);
        if (!newOrderLimit.tryAcquire(source, now)) {
            throw new CaException(CaException.Code.RATE_LIMITED, "这个来源（" + source + "）申请得太频繁，过几分钟再来");
        }
        purge(now);
        if (orders.size() >= MAX_ORDERS) {
            throw new CaException(CaException.Code.RATE_LIMITED, "证书服务手上的订单太多，过一会儿再来");
        }
        String thumb = b64(sha256(req.subjectPublicKeyInfo()));
        List<String> names = new ArrayList<>(set);
        List<Challenge> challenges = new ArrayList<>();
        for (String n : names) {
            String token = b64(randomBytes(16));
            String content = token + "." + thumb;
            challenges.add(new Challenge(n, token, content, b64(sha256(content.getBytes(StandardCharsets.US_ASCII)))));
        }
        String id;
        do {
            id = b64(randomBytes(16));
        } while (orders.containsKey(id));
        Order o = new Order(id, requester, names, challenges, key, now, now + ORDER_TTL_MS);
        orders.put(id, o);
        LOG.info("证书服务：新订单 {}，{}，来源 {}", shortId(id), names, source);
        return o;
    }

    /** 证书请求里的名字（SAN 的 DNS 名字）要和申请的 {@code names} 正好是同一组；有 CN 时 CN 要是其中一个 */
    static void checkNames(CertificateRequest req, Collection<String> names) throws CaException {
        LinkedHashSet<String> inCsr = req.names();
        if (inCsr.isEmpty()) {
            throw new CaException(CaException.Code.BAD_CSR, "证书请求里没有 subjectAltName 的 DNS 名字（要和 names 一致）");
        }
        if (!inCsr.equals(new LinkedHashSet<>(names))) {
            List<String> missing = new ArrayList<>();
            for (String n : names) {
                if (!inCsr.contains(n)) {
                    missing.add(n);
                }
            }
            List<String> extra = new ArrayList<>();
            for (String n : inCsr) {
                if (!names.contains(n)) {
                    extra.add(n);
                }
            }
            throw new CaException(CaException.Code.BAD_CSR, "证书请求里的名字和 names 不一致"
                    + (missing.isEmpty() ? "" : "，请求里少了 " + missing) + (extra.isEmpty() ? "" : "，请求里多了 " + extra));
        }
        String cn = req.commonNameAscii();
        if (cn != null && !inCsr.contains(cn)) {
            throw new CaException(CaException.Code.BAD_CSR, "证书请求的 CN（" + req.commonName + "）不是要签的名字之一");
        }
    }

    static void checkKey(PublicKey key) throws CaException {
        if (key instanceof ECPublicKey) {
            int bits = ((ECPublicKey) key).getParams().getCurve().getField().getFieldSize();
            if (bits == 256 || bits == 384) {
                return;
            }
            throw new CaException(CaException.Code.BAD_KEY, "EC 公钥只收 P-256、P-384（现在是 " + bits + " 位）");
        }
        if (key instanceof RSAPublicKey) {
            int bits = ((RSAPublicKey) key).getModulus().bitLength();
            if (bits >= 2048 && bits <= 4096) {
                return;
            }
            throw new CaException(CaException.Code.BAD_KEY, "RSA 公钥要 2048–4096 位（现在是 " + bits + " 位）");
        }
        throw new CaException(CaException.Code.BAD_KEY, "公钥只收 EC（P-256、P-384）和 RSA");
    }

    private Order find(String id) throws CaException {
        purge(clock.getAsLong());
        Order o = id == null ? null : orders.get(id);
        if (o == null) {
            throw new CaException(CaException.Code.NOT_FOUND, "没有这张订单（订单号不对，或已过期）");
        }
        return o;
    }

    /** 通知就绪：开始核对 */
    private Order start(String id, Challenge.Method method) throws CaException {
        Order o = find(id);
        if (method == null) {
            throw new CaException(CaException.Code.BAD_REQUEST, "要说用哪种认证（http 或 dns）");
        }
        if (o.status != CertificateOrder.Status.PENDING) {
            throw new CaException(CaException.Code.WRONG_STATE, "订单已经通知过就绪了（现在是 " + o.status.id + "）");
        }
        o.status = CertificateOrder.Status.PROCESSING;
        o.method = method;
        Validator v = validator;
        if (v == null) {
            finish(o, "证书服务的网络还没开好");
            return o;
        }
        o.timeout = loop.schedule(() -> finish(o, "认证超时（" + VALIDATION_TIMEOUT_MS / 1000 + " 秒内没核对完）"), VALIDATION_TIMEOUT_MS);
        int[] left = {o.challenges.size()};
        String[] errors = new String[o.challenges.size()];
        for (int i = 0; i < o.challenges.size(); i++) {
            Challenge c = o.challenges.get(i);
            int index = i;
            CompletableFuture<String> f;
            try {
                f = method == Challenge.Method.HTTP ? v.http(c.name, c.token, c.httpContent) : v.dns(c.dnsName, c.dnsValue);
            } catch (RuntimeException e) {
                f = CompletableFuture.completedFuture("核对时出错：" + e);
            }
            f.whenComplete((err, t) -> loop.execute(() -> {
                errors[index] = t != null ? "核对时出错：" + t : err;
                if (--left[0] == 0) {
                    String first = null;
                    for (int k = 0; k < errors.length; k++) {
                        if (errors[k] != null) {
                            first = o.names.get(k) + "：" + errors[k];
                            break;
                        }
                    }
                    if (first != null) {
                        finish(o, first);
                    } else {
                        sign(o);
                    }
                }
            }));
        }
        return o;
    }

    private void sign(Order o) {
        if (o.status != CertificateOrder.Status.PROCESSING) {
            return;
        }
        long now = clock.getAsLong();
        try {
            List<X509Certificate> chain = ca.issue(o.names, o.key, new Date(now));
            o.certificate = chain;
            o.status = CertificateOrder.Status.VALID;
            record(o.names, chain.get(0), String.valueOf(o.requester), o.method.id, now);
        } catch (GeneralSecurityException | RuntimeException e) {
            LOG.error("证书服务：给 {} 签证书失败", o.names, e);
            finish(o, "签证书失败：" + e.getMessage());
            return;
        }
        done(o);
    }

    /** 以失败结束（已经结束的不动） */
    private void finish(Order o, String error) {
        if (o.status != CertificateOrder.Status.PROCESSING) {
            return;
        }
        o.status = CertificateOrder.Status.INVALID;
        o.error = error;
        LOG.info("证书服务：订单 {}（{}）没通过：{}", shortId(o.id), o.names, error);
        done(o);
    }

    private void done(Order o) {
        if (o.timeout != null) {
            o.timeout.cancel();
            o.timeout = null;
        }
        CertificateOrder s = o.snapshot();
        for (CompletableFuture<CertificateOrder> w : o.waiters) {
            w.complete(s);
        }
        o.waiters.clear();
    }

    private void record(List<String> names, X509Certificate leaf, String requester, String method, long now) {
        issuedTotal++;
        issued.addFirst(new Issued(names, leaf.getSerialNumber().toString(16), leaf.getNotBefore().getTime(), leaf.getNotAfter().getTime(),
                requester, method, now));
        while (issued.size() > ISSUED_KEEP) {
            issued.removeLast();
        }
        LOG.info("证书服务：签发了 {}（序列号 {}，有效 {} 到 {}，申请来源 {}，认证 {}）", names, leaf.getSerialNumber().toString(16),
                leaf.getNotBefore().toInstant(), leaf.getNotAfter().toInstant(), requester, method);
    }

    private void purge(long now) {
        Iterator<Order> it = orders.values().iterator();
        while (it.hasNext()) {
            Order o = it.next();
            if (o.expires <= now && o.status != CertificateOrder.Status.PROCESSING) {
                it.remove();
            }
        }
    }

    // ---- ca.yr 自己的证书 ----

    /**
     * 网络线程上：给证书服务自己（{@code names}，即 {@code ca.yr}）签证书并放进 {@code tls}，到剩 30 天时再签。
     * 不走申请与认证：签发者就是它自己，握着中间 CA 的私钥，没有要向谁证明的；也免得开服时它要先经网络认证自己。
     */
    public void selfCertificate(List<String> names, CertificateTlsContext tls) {
        if (ca == null) {
            return;
        }
        long now = clock.getAsLong();
        long next;
        try {
            KeyPairGenerator g = KeyPairGenerator.getInstance("EC");
            g.initialize(new ECGenParameterSpec("secp256r1"));
            KeyPair kp = g.generateKeyPair();
            List<X509Certificate> chain = ca.issue(names, kp.getPublic(), new Date(now));
            tls.set(kp.getPrivate(), chain);
            X509Certificate leaf = chain.get(0);
            record(names, leaf, "证书服务自己", "-", now);
            long nb = leaf.getNotBefore().getTime();
            long na = leaf.getNotAfter().getTime();
            next = na - Math.min(RENEW_BEFORE_DAYS * DAY_MS, (na - nb) / 3) - now;
        } catch (GeneralSecurityException | RuntimeException e) {
            LOG.error("证书服务：给自己（{}）签证书失败，一小时后再试", names, e);
            next = 3600_000L;
        }
        loop.schedule(() -> selfCertificate(names, tls), Math.max(60_000L, next));
    }

    // ---- 给 /ynet ca 看 ----

    /** 一张订单的概况 */
    public static final class OrderInfo {
        public final CertificateOrder order;
        public final String requester;
        public final long created;

        OrderInfo(CertificateOrder order, String requester, long created) {
            this.order = order;
            this.requester = requester;
            this.created = created;
        }
    }

    /** 签出去的一张证书 */
    public static final class Issued {
        public final List<String> names;
        public final String serial;
        public final long notBefore;
        public final long notAfter;
        public final String requester;
        public final String method;
        public final long at;

        Issued(List<String> names, String serial, long notBefore, long notAfter, String requester, String method, long at) {
            this.names = names;
            this.serial = serial;
            this.notBefore = notBefore;
            this.notAfter = notAfter;
            this.requester = requester;
            this.method = method;
            this.at = at;
        }
    }

    /** 现在内存里的订单（新的在后） */
    public CompletableFuture<List<OrderInfo>> orders() {
        return onLoop(() -> {
            purge(clock.getAsLong());
            List<OrderInfo> out = new ArrayList<>();
            for (Order o : orders.values()) {
                out.add(new OrderInfo(o.snapshot(), String.valueOf(o.requester), o.created));
            }
            return out;
        });
    }

    /** 最近签出去的证书（新的在前） */
    public CompletableFuture<List<Issued>> issued() {
        return onLoop(() -> new ArrayList<>(issued));
    }

    /** 开服以来一共签了几张 */
    public long issuedTotal() {
        return issuedTotal;
    }

    // ---- 工具 ----

    private interface Task<T> {
        T run() throws CaException;
    }

    private <T> CompletableFuture<T> onLoop(Task<T> task) {
        CompletableFuture<T> f = new CompletableFuture<>();
        Runnable r = () -> {
            try {
                f.complete(task.run());
            } catch (CaException e) {
                f.completeExceptionally(e);
            } catch (RuntimeException e) {
                LOG.error("证书服务出错", e);
                f.completeExceptionally(new CaException(CaException.Code.UNAVAILABLE, "证书服务出错：" + e));
            }
        };
        if (loop.inEventLoop()) {
            r.run();
        } else {
            loop.execute(r);
        }
        return f;
    }

    private byte[] randomBytes(int n) {
        byte[] b = new byte[n];
        random.nextBytes(b);
        return b;
    }

    static String b64(byte[] b) {
        return Base64.getUrlEncoder().withoutPadding().encodeToString(b);
    }

    static byte[] sha256(byte[] b) {
        try {
            return MessageDigest.getInstance("SHA-256").digest(b);
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException(e);
        }
    }

    /** 订单号的前 8 个字符（日志、命令里显示） */
    public static String shortId(String id) {
        return id.length() <= 8 ? id : id.substring(0, 8);
    }

    /** 一张订单（只在网络线程上动） */
    private static final class Order {
        final String id;
        final Ipv6Address requester;
        final List<String> names;
        final List<Challenge> challenges;
        final PublicKey key;
        final long created;
        final long expires;
        CertificateOrder.Status status = CertificateOrder.Status.PENDING;
        Challenge.Method method;
        String error;
        List<X509Certificate> certificate;
        EventLoop.Timer timeout;
        final List<CompletableFuture<CertificateOrder>> waiters = new ArrayList<>();

        Order(String id, Ipv6Address requester, List<String> names, List<Challenge> challenges, PublicKey key, long created, long expires) {
            this.id = id;
            this.requester = requester;
            this.names = names;
            this.challenges = challenges;
            this.key = key;
            this.created = created;
            this.expires = expires;
        }

        CertificateOrder snapshot() {
            return new CertificateOrder(id, status, names, challenges, method, error, certificate, expires);
        }
    }
}
