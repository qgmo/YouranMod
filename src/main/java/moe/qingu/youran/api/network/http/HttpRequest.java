package moe.qingu.youran.api.network.http;

import moe.qingu.youran.api.network.Ipv6Address;

import java.util.Map;

/** {@link HttpServer} 收到的一个请求（已读完正文）。不可变（{@link #headers} 是拷贝，改它不影响别人）。 */
public final class HttpRequest {
    /** 方法（大写，如 {@code GET}） */
    public final String method;
    /** 请求行里的目标原样（origin-form，如 {@code /img/x.png?a=1}） */
    public final String target;
    /** 目标里 {@code ?} 之前的部分（不解码） */
    public final String path;
    /** {@code ?} 之后的部分（不解码）；没有为空串 */
    public final String query;
    /** {@code HTTP/1.1} 或 {@code HTTP/1.0} */
    public final String version;
    public final HttpHeaders headers;
    /** 正文；没有为空数组 */
    public final byte[] body;
    /** 对方（TCP 来源）地址与端口 */
    public final Ipv6Address remoteAddress;
    public final int remotePort;
    /** 本机地址与端口 */
    public final Ipv6Address localAddress;
    public final int localPort;
    /** 是不是经 TLS（https）来的 */
    public final boolean secure;

    public HttpRequest(String method, String target, String version, HttpHeaders headers, byte[] body,
                       Ipv6Address remoteAddress, int remotePort, Ipv6Address localAddress, int localPort, boolean secure) {
        this.method = method;
        this.target = target;
        int q = target.indexOf('?');
        this.path = q < 0 ? target : target.substring(0, q);
        this.query = q < 0 ? "" : target.substring(q + 1);
        this.version = version;
        this.headers = headers.copy();
        this.body = body;
        this.remoteAddress = remoteAddress;
        this.remotePort = remotePort;
        this.localAddress = localAddress;
        this.localPort = localPort;
        this.secure = secure;
    }

    /** 第一条同名首部；没有为 null */
    public String header(String name) {
        return headers.get(name);
    }

    /** 查询参数（UTF-8 解码；同名取第一个） */
    public Map<String, String> queryParameters() {
        return FormData.parse(query);
    }

    /**
     * 表单（{@code application/x-www-form-urlencoded}，UTF-8）；Content-Type 不是表单时为空表。
     */
    public Map<String, String> form() {
        String type = header("Content-Type");
        if (type == null || !type.trim().toLowerCase(java.util.Locale.ROOT).startsWith("application/x-www-form-urlencoded")) {
            return java.util.Collections.emptyMap();
        }
        return FormData.parse(new String(body, java.nio.charset.StandardCharsets.ISO_8859_1));
    }
}
