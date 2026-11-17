package moe.qingu.youran.api.network.ca;

import moe.qingu.youran.api.network.Ipv6Address;

import java.security.PublicKey;
import java.util.Collection;
import java.util.concurrent.CompletableFuture;

/**
 * 证书签发服务（{@value #DOMAIN}，类似 Let's Encrypt）的 Java 接口，给模组直接调用；游戏内网络上的 HTTP 接口见包说明。
 * 由 {@code YouranNetworkApi.certificateAuthority()} 得到。一般不用直接调它，用 {@link CertificateClient}。
 * <p>
 * 流程：{@link #newOrder 申请}（给名字和公钥）→ 按订单里的认证要求布置好（HTTP 或 DNS，见 {@link Challenge}）→
 * {@link #ready 通知已就绪} → 证书服务从 {@code ca.yr} 那台主机经网络核对每个名字 → 订单变成已签发（带证书）或失败（带原因）。
 * <p>
 * 和 DNS 注册无关：注册了域名、跑着 DNS 都不会自动有证书，要证书必须自己申请并通过认证。
 * 签发用服务器配的中间 CA（{@code network.tlsCaCertificate} / {@code tlsCaKey}），没配时服务不开，申请都失败（{@link CaException.Code#UNAVAILABLE}）。
 * 名字要在中间 CA 的域名约束之内（现在只许 {@code .yr}）；约束外的直接拒绝。证书有效 90 天，剩 30 天时续（{@link CertificateClient} 自动做）。
 * <p>
 * 任意线程都可以调用；future 在网络线程上完成，不要在回调里阻塞。
 */
public interface CertificateAuthority {
    /** HTTP 接口所在的域名 */
    String DOMAIN = "ca.yr";
    /** 一张订单最多几个名字 */
    int MAX_NAMES = 10;

    /** 服务开着为 null；没开时为原因（如服务器没配中间 CA） */
    String unavailableReason();

    /**
     * 申请：给 {@code names} 签一张证书，公钥是 {@code key}（EC P-256 / P-384，或 RSA 2048–4096 位）。按 {@code requester}（申请来源，
     * 一般是自己主机的地址）限频。名字可以写 Unicode，转成 A-label；不能带通配符。
     * 成功时 future 给出新订单（{@link CertificateOrder.Status#PENDING}，带认证要求）；不收时以 {@link CaException} 结束。
     */
    CompletableFuture<CertificateOrder> newOrder(Ipv6Address requester, Collection<String> names, PublicKey key);

    /** 查订单现在的样子；没有时以 {@link CaException}（{@link CaException.Code#NOT_FOUND}）结束 */
    CompletableFuture<CertificateOrder> order(String id);

    /**
     * 通知已就绪：认证要求布置好了，用 {@code method} 这种方式核对全部名字。只能通知一次。
     * future 在订单有结果时完成（{@link CertificateOrder.Status#VALID} 或 {@link CertificateOrder.Status#INVALID}）；
     * 订单不在等认证时以 {@link CaException} 结束。
     */
    CompletableFuture<CertificateOrder> ready(String id, Challenge.Method method);
}
