package moe.qingu.youran.api.network.tls;

import javax.net.ssl.SSLEngine;

/**
 * 服务端的 TLS（网站的 443 口）：每条新连接要一个服务端模式的 {@link SSLEngine}。
 * 网站的证书要自己向证书签发服务（{@code ca.yr}）申请：一般用 {@code api.network.ca.CertificateClient}，
 * 它给出的就是这个（{@link CertificateTlsContext}）。
 */
public interface TlsServerContext {
    /** 一条新连接用的引擎（服务端模式，已设好协议版本） */
    SSLEngine newEngine();
}
