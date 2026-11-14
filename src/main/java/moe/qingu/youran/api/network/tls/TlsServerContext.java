package moe.qingu.youran.api.network.tls;

import javax.net.ssl.SSLEngine;

/**
 * 服务端的 TLS（网站的 443 口）：证书由服务器配置的中间 CA 现场签发，按客户端在握手里给的主机名（SNI）选证书，
 * 没签过或快到期的当场签（叶子证书的 SAN 就是这个名字）。只给这个网站当前拥有的名字签；
 * 客户端没给名字、或给的名字不归它时用默认名字的证书（浏览器会报“证书与主机名不符”）。
 * 由 {@code HostContext#tls()} 或 {@code YouranNetworkApi#serverTls} 得到；服务器没配中间 CA 时没有。
 */
public interface TlsServerContext {
    /** 一条新连接用的引擎（服务端模式，已设好协议版本） */
    SSLEngine newEngine();
}
