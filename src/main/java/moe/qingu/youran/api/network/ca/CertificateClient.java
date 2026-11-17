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

import java.security.GeneralSecurityException;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.cert.X509Certificate;
import java.security.spec.ECGenParameterSpec;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.List;
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
 * 手里有旧证书时继续用旧的。证书、私钥只在内存里，开服时重新申请。私钥每次申请都新生成（EC P-256），用它签一份证书请求（CSR）交上去。
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
    private final CertificateTlsContext tls;
    private final String unavailable;
    private EventLoop.Timer timer;
    private long retryDelay = RETRY_FIRST_MS;
    private boolean busy;
    private volatile boolean stopped;
    private volatile String lastError;

    private CertificateClient(NetStack stack, List<String> names, TxtRecords dns, CertificateAuthority ca, LongSupplier clock) {
        this.stack = stack;
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
        NetworkBackend b = NetworkHooks.backend();
        return start(stack, names, dns, b == null ? null : b.certificateAuthority(), System::currentTimeMillis);
    }

    /** 同上，指定证书服务和时钟（测试、或者用别的证书服务时用） */
    public static CertificateClient start(NetStack stack, Collection<String> names, TxtRecords dns, CertificateAuthority ca, LongSupplier clock) {
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
        CertificateClient c = new CertificateClient(stack, Collections.unmodifiableList(new ArrayList<>(set)), dns, ca, clock);
        if (c.unavailable != null) {
            LOG.info("游戏内网络：{} 拿不到证书，只开 HTTP（证书服务没开：{}）", c.names, c.unavailable);
        } else {
            c.loop.execute(c::attempt);
        }
        return c;
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
        X509Certificate leaf = chain.get(0);
        long notBefore = leaf.getNotBefore().getTime();
        long notAfter = leaf.getNotAfter().getTime();
        long renewAt = notAfter - Math.min(RENEW_BEFORE_DAYS * DAY_MS, (notAfter - notBefore) / 3);
        LOG.info("游戏内网络：{} 拿到了证书（序列号 {}，有效到 {}），{} 天后续", names, leaf.getSerialNumber().toString(16), leaf.getNotAfter(),
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
