package moe.qingu.youran.api.network.ca;

import moe.qingu.youran.api.network.DomainNames;
import moe.qingu.youran.api.network.stack.EventLoop;
import moe.qingu.youran.api.network.stack.NetStack;
import moe.qingu.youran.api.network.tls.CertificateTlsContext;
import moe.qingu.youran.api.network.tls.TlsServerContext;
import moe.qingu.youran.network.NetworkBackend;
import moe.qingu.youran.network.NetworkHooks;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.PrivateKey;
import java.security.PublicKey;
import java.security.Signature;
import java.security.cert.CertificateParsingException;
import java.security.cert.X509Certificate;
import java.security.spec.ECGenParameterSpec;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.concurrent.CompletionException;
import java.util.function.LongSupplier;

/**
 * 网站自己申请证书的助手：给一组名字，向证书签发服务（{@link CertificateAuthority}）申请、通过认证、拿到证书，
 * 到期前（剩 {@value #RENEW_BEFORE_DAYS} 天）自动续，给出一个能直接交给 HTTPS 服务器（{@code HttpServer.listen}、家具模组的
 * {@code WebServer.serve} 等）的 {@link #tls() TLS}。
 * <p>
 * 认证方式自动选：给了 {@link TxtRecords}（能加删 TXT 记录的办法，如自己的默认 DNS 服务 {@code TxtRecords.of(dns)}）就用 DNS 认证；
 * 没给就用 HTTP 认证，这时这台主机的 80 口要有网站：YouranMod 的 {@code HttpServer} 自动回认证请求，
 * 自己写的 HTTP 服务器要在处理请求前问 {@link HttpChallenges#answer}。
 * <p>
 * 用法（在主机的 {@code HostService.start} 里，网络线程上，先开 80 口）：
 * <pre>{@code
 * HttpServer.listen(ctx.stack(), 80, null, options, handler);
 * TlsServerContext tls = CertificateClient.start(ctx.stack(), Collections.singletonList("shop.yr"), null).tls();
 * if (tls != null) {
 *     HttpServer.listen(ctx.stack(), 443, tls, options, handler);
 * }
 * }</pre>
 * 证书还没拿到时 443 口的握手失败（一般开服后几秒内就拿到）；申请失败时记日志，过一会儿再试（6 分钟起，每次加倍，最多 6 小时），
 * 手里有旧证书时继续用旧的。私钥每次申请都新生成（EC P-256），用它签一份证书请求（CSR）交上去。
 *
 * <h2>存起来</h2>
 * 给了 {@link CertificateStore}（存在哪由调用方定，如 {@code CertificateStore.directory(目录)}）时，拿到证书（含续期）就存下；
 * 开服时先读存着的，下面几条全满足就直接用、不再申请：
 * <ul>
 * <li>没过期（也已经生效）；</li>
 * <li>证书里的名字（subjectAltName 的 DNS 名字）正好是这次要的这组名字；</li>
 * <li>链是现在的中间 CA（{@link CertificateAuthority#issuerChain()}）签的：叶子后面正好是它，叶子的签名对得上（中间 CA 换了就重新申请）；</li>
 * <li>私钥和证书里的公钥对得上。</li>
 * </ul>
 * 用存着的证书时照常到剩 {@value #RENEW_BEFORE_DAYS} 天续（已经不到 30 天的开服后马上续），续好了覆盖存下。
 * 不满足的记一行日志、重新申请，申请到了覆盖存下。存不下时记日志，照用内存里的证书。
 * 不给 {@link CertificateStore}（{@code null}）就只在内存里，每次开服重新申请。证书服务没开（没配中间 CA）时存着的也不用，只开 HTTP。
 */
public final class CertificateClient {
    static final Logger LOG = LogManager.getLogger("YouranMod");
    public static final int RENEW_BEFORE_DAYS = 30;
    static final long DAY_MS = 24L * 3600 * 1000;
    static final long RETRY_FIRST_MS = 6 * 60_000L;
    static final long RETRY_MAX_MS = 6 * 3600_000L;
    static final int HTTP_PORT = 80;

    private final NetStack stack;
    private final EventLoop loop;
    private final List<String> names;
    private final TxtRecords dns;
    private final CertificateAuthority ca;
    private final LongSupplier clock;
    private final CertificateStore store;
    private final CertificateTlsContext tls;
    private final String unavailable;
    private EventLoop.Timer timer;
    private long retryDelay = RETRY_FIRST_MS;
    private boolean busy;
    private volatile boolean stopped;
    private volatile String lastError;

    private CertificateClient(NetStack stack, List<String> names, TxtRecords dns, CertificateAuthority ca, LongSupplier clock, CertificateStore store) {
        this.stack = stack;
        this.store = store;
        this.loop = stack.loop();
        this.names = names;
        this.dns = dns;
        this.ca = ca;
        this.clock = clock;
        String reason = ca == null ? "游戏内网络没在运行" : ca.unavailableReason();
        this.unavailable = reason;
        this.tls = reason == null ? new CertificateTlsContext() : null;
    }

    /**
     * 开始给 {@code names} 申请证书（用 YouranMod 的证书服务、真实时间）。在主机的协议栈 {@code stack} 的事件循环（网络线程）上调用；
     * 第一次申请排在之后，所以可以先建好它、拿 {@link #tls()} 去开 443，再开 80。
     *
     * @param names 要证明的名字（可以写 Unicode，转成 A-label；转不了时抛出 {@link IllegalArgumentException}），至少一个
     * @param dns   能加删 TXT 记录的办法：给了就用 DNS 认证；null 用 HTTP 认证
     */
    public static CertificateClient start(NetStack stack, Collection<String> names, TxtRecords dns) {
        return start(stack, names, dns, (CertificateStore) null);
    }

    /**
     * 同上，证书和私钥存进 {@code store}，开服时先用存着的（见类说明的“存起来”）。
     *
     * @param store 存在哪（如 {@code CertificateStore.directory(目录)}）；null 不存，每次开服重新申请
     */
    public static CertificateClient start(NetStack stack, Collection<String> names, TxtRecords dns, CertificateStore store) {
        NetworkBackend b = NetworkHooks.backend();
        return start(stack, names, dns, b == null ? null : b.certificateAuthority(), System::currentTimeMillis, store);
    }

    /** 指定证书服务和时钟，不存（测试、或者用别的证书服务时用） */
    public static CertificateClient start(NetStack stack, Collection<String> names, TxtRecords dns, CertificateAuthority ca, LongSupplier clock) {
        return start(stack, names, dns, ca, clock, null);
    }

    /** 指定证书服务、时钟和存储（{@code store} 为 null 时不存） */
    public static CertificateClient start(NetStack stack, Collection<String> names, TxtRecords dns, CertificateAuthority ca, LongSupplier clock,
                                          CertificateStore store) {
        if (stack == null || names == null || clock == null) {
            throw new IllegalArgumentException("stack、names、clock 不能为 null");
        }
        LinkedHashSet<String> set = new LinkedHashSet<>();
        for (String n : names) {
            set.add(DomainNames.toAscii(n));
        }
        if (set.isEmpty()) {
            throw new IllegalArgumentException("至少要一个名字");
        }
        CertificateClient c = new CertificateClient(stack, Collections.unmodifiableList(new ArrayList<>(set)), dns, ca, clock, store);
        if (c.unavailable != null) {
            LOG.info("游戏内网络：{} 拿不到证书，只开 HTTP（证书服务没开：{}）", c.names, c.unavailable);
        } else if (!c.useSaved()) {
            c.loop.execute(c::attempt);
        }
        return c;
    }

    /** 有存着的、能用的证书时放进 TLS、排好续期，返回 true；没有或不能用时返回 false（记日志） */
    private boolean useSaved() {
        if (store == null) {
            return false;
        }
        CertificateStore.Saved saved;
        try {
            saved = store.load();
        } catch (Exception e) {
            LOG.warn("游戏内网络：{} 读不了存着的证书，重新申请：{}", names, e.toString());
            return false;
        }
        if (saved == null) {
            return false;
        }
        String why = problem(saved, names, ca.issuerChain(), clock.getAsLong());
        if (why != null) {
            LOG.info("游戏内网络：{} 存着的证书不能用（{}），重新申请", names, why);
            return false;
        }
        tls.set(saved.key, saved.chain);
        loop.execute(() -> scheduleRenewal(saved.chain.get(0), "用存着的证书"));
        return true;
    }

    /** 存着的证书哪里不能用；能用返回 null */
    static String problem(CertificateStore.Saved saved, List<String> names, List<X509Certificate> issuer, long now) {
        if (saved.chain.isEmpty() || saved.key == null) {
            return "没有证书或私钥";
        }
        X509Certificate leaf = saved.chain.get(0);
        if (now >= leaf.getNotAfter().getTime()) {
            return "已经过期（" + leaf.getNotAfter().toInstant() + "）";
        }
        if (now < leaf.getNotBefore().getTime()) {
            return "还没生效（" + leaf.getNotBefore().toInstant() + "）";
        }
        Set<String> want = new HashSet<>();
        for (String n : names) {
            want.add(n.toLowerCase(Locale.ROOT));
        }
        Set<String> have = dnsNames(leaf);
        if (!have.equals(want)) {
            return "名字是 " + have + "，现在要 " + want;
        }
        if (issuer == null || issuer.isEmpty()) {
            return "证书服务没给出现在的中间 CA，核对不了";
        }
        if (!saved.chain.subList(1, saved.chain.size()).equals(issuer)) {
            return "链上不是现在的中间 CA";
        }
        try {
            leaf.verify(issuer.get(0).getPublicKey());
        } catch (GeneralSecurityException | RuntimeException e) {
            return "不是现在的中间 CA 签的";
        }
        if (!keyMatches(saved.key, leaf.getPublicKey())) {
            return "私钥和证书的公钥对不上";
        }
        return null;
    }

    private static Set<String> dnsNames(X509Certificate leaf) {
        Set<String> out = new HashSet<>();
        try {
            Collection<List<?>> sans = leaf.getSubjectAlternativeNames();
            if (sans != null) {
                for (List<?> e : sans) {
                    if (e.size() >= 2 && Integer.valueOf(2).equals(e.get(0)) && e.get(1) instanceof String) {
                        out.add(((String) e.get(1)).toLowerCase(Locale.ROOT));
                    }
                }
            }
        } catch (CertificateParsingException e) {
            // 读不了当作没有名字
        }
        return out;
    }

    /** 用私钥签一段固定的字、拿证书的公钥验 */
    static boolean keyMatches(PrivateKey key, PublicKey pub) {
        String alg = key.getAlgorithm();
        if (!alg.equals(pub.getAlgorithm())) {
            return false;
        }
        String sigAlg = alg.equals("EC") ? "SHA256withECDSA" : alg.equals("RSA") ? "SHA256withRSA" : null;
        if (sigAlg == null) {
            return false;
        }
        try {
            byte[] data = "YouranMod certificate key check".getBytes(StandardCharsets.US_ASCII);
            Signature s = Signature.getInstance(sigAlg);
            s.initSign(key);
            s.update(data);
            byte[] sig = s.sign();
            Signature v = Signature.getInstance(sigAlg);
            v.initVerify(pub);
            v.update(data);
            return v.verify(sig);
        } catch (GeneralSecurityException | RuntimeException e) {
            return false;
        }
    }

    /** 给 HTTPS 服务器用的 TLS；证书服务没开（服务器没配中间 CA 等）时为 null，这时只开 HTTP */
    public TlsServerContext tls() {
        return tls;
    }

    /** 申请的名字（A-label） */
    public List<String> names() {
        return names;
    }

    /** 现在用的证书链（叶子在前）；还没拿到时为空表 */
    public List<X509Certificate> certificate() {
        return tls == null ? Collections.<X509Certificate>emptyList() : tls.chain();
    }

    /** 上一次申请失败的原因；最近一次成功了、或还没申请过为 null */
    public String lastError() {
        return lastError;
    }

    /** 不再申请、续期（已经拿到的证书照用到过期） */
    public void stop() {
        stopped = true;
        loop.execute(() -> {
            if (timer != null) {
                timer.cancel();
                timer = null;
            }
        });
    }

    // ---- 网络线程上 ----

    private void attempt() {
        timer = null;
        if (stopped || busy) {
            return;
        }
        Challenge.Method method = dns != null ? Challenge.Method.DNS : stack.isListening(HTTP_PORT) ? Challenge.Method.HTTP : null;
        if (method == null) {
            fail("这台主机的 80 口没有网站，又没给加 TXT 记录的办法，没法认证");
            return;
        }
        KeyPair kp;
        byte[] csr;
        try {
            KeyPairGenerator g = KeyPairGenerator.getInstance("EC");
            g.initialize(new ECGenParameterSpec("secp256r1"));
            kp = g.generateKeyPair();
            csr = CertificateRequest.create(kp, names);
        } catch (GeneralSecurityException e) {
            fail("生成密钥或证书请求失败：" + e);
            return;
        }
        busy = true;
        ca.newOrder(stack.primaryAddress(), names, csr).whenComplete((order, err) -> loop.execute(() -> {
            if (err != null) {
                busy = false;
                fail(message(err));
                return;
            }
            place(order, method);
            // 下一个任务再通知：DNS 服务的改动排在网络线程上生效
            loop.execute(() -> ca.ready(order.id, method).whenComplete((done, err2) -> loop.execute(() -> {
                unplace(order, method);
                busy = false;
                if (err2 != null) {
                    fail(message(err2));
                } else if (done.status == CertificateOrder.Status.VALID && !done.certificate.isEmpty()) {
                    install(kp, done.certificate);
                } else {
                    fail(done.error == null ? "订单状态 " + done.status.id : done.error);
                }
            })));
        }));
    }

    private void place(CertificateOrder order, Challenge.Method method) {
        for (Challenge c : order.challenges) {
            if (method == Challenge.Method.HTTP) {
                HttpChallenges.put(stack, c.token, c.httpContent);
            } else {
                dns.add(c.dnsName, c.dnsValue);
            }
        }
    }

    private void unplace(CertificateOrder order, Challenge.Method method) {
        for (Challenge c : order.challenges) {
            try {
                if (method == Challenge.Method.HTTP) {
                    HttpChallenges.remove(stack, c.token);
                } else {
                    dns.remove(c.dnsName, c.dnsValue);
                }
            } catch (RuntimeException e) {
                LOG.warn("游戏内网络：{} 的认证收尾出错：{}", c.name, e.toString());
            }
        }
    }

    private void install(KeyPair kp, List<X509Certificate> chain) {
        tls.set(kp.getPrivate(), chain);
        lastError = null;
        retryDelay = RETRY_FIRST_MS;
        scheduleRenewal(chain.get(0), "拿到了证书");
        if (store != null) {
            try {
                store.save(chain, kp.getPrivate());
            } catch (Exception e) {
                LOG.warn("游戏内网络：{} 的证书存不下，照用内存里的（下次开服重新申请）：{}", names, e.toString());
            }
        }
    }

    private void scheduleRenewal(X509Certificate leaf, String what) {
        long notBefore = leaf.getNotBefore().getTime();
        long notAfter = leaf.getNotAfter().getTime();
        long renewAt = notAfter - Math.min(RENEW_BEFORE_DAYS * DAY_MS, (notAfter - notBefore) / 3);
        LOG.info("游戏内网络：{} {}（序列号 {}，有效到 {}），{} 天后续", names, what, leaf.getSerialNumber().toString(16), leaf.getNotAfter(),
                Math.max(0, Math.round((renewAt - clock.getAsLong()) / (double) DAY_MS)));
        schedule(renewAt - clock.getAsLong());
    }

    private void fail(String reason) {
        lastError = reason;
        long delay = retryDelay;
        retryDelay = Math.min(RETRY_MAX_MS, retryDelay * 2);
        LOG.warn("游戏内网络：{} 申请证书失败：{}；{} 分钟后再试", names, reason, delay / 60_000);
        schedule(delay);
    }

    private void schedule(long delay) {
        if (stopped) {
            return;
        }
        if (timer != null) {
            timer.cancel();
        }
        timer = loop.schedule(this::attempt, Math.max(0, delay));
    }

    static String message(Throwable t) {
        Throwable c = t instanceof CompletionException && t.getCause() != null ? t.getCause() : t;
        return c.getMessage() != null ? c.getMessage() : c.toString();
    }
}
