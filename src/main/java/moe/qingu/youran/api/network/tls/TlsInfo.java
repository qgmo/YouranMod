package moe.qingu.youran.api.network.tls;

import moe.qingu.youran.api.network.Ipv6Address;

import java.security.cert.X509Certificate;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/** 一条 TLS 连接的信息（握手完成后）：浏览器“连接信息”用 */
public final class TlsInfo {
    /** 协议版本，如 {@code TLSv1.3} */
    public final String protocol;
    /** 密码套件，如 {@code TLS_AES_128_GCM_SHA256} */
    public final String cipherSuite;
    public final Ipv6Address remoteAddress;
    public final int remotePort;
    /** 访问时用的主机名（SNI）；按 IP 访问时是地址本身 */
    public final String host;
    /** 对方给的证书链（叶子在前，不含信任的根） */
    public final List<X509Certificate> peerChain;

    public TlsInfo(String protocol, String cipherSuite, Ipv6Address remoteAddress, int remotePort, String host, List<X509Certificate> peerChain) {
        this.protocol = protocol;
        this.cipherSuite = cipherSuite;
        this.remoteAddress = remoteAddress;
        this.remotePort = remotePort;
        this.host = host;
        this.peerChain = Collections.unmodifiableList(new ArrayList<>(peerChain));
    }
}
