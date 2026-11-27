package moe.qingu.youran.api.network.stack;

import moe.qingu.youran.api.network.Ipv6Address;
import moe.qingu.youran.api.network.packet.DnsMessage;
import moe.qingu.youran.api.network.tls.TlsConnection;
import moe.qingu.youran.api.network.tls.TlsInfo;
import moe.qingu.youran.api.network.tls.TlsTrust;

import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.CompletableFuture;

/**
 * 最简单的 HTTP/1.1 客户端（RFC 9112）：一条 TCP 连接取一个页面（{@code Connection: close}），
 * 认 Content-Length、分块传输（chunked）和“读到连接关闭为止”三种正文。不跟随重定向。
 * {@code https://} 走 {@link TlsConnection}（只信任 QinguMoe CA，见 {@link TlsTrust}），证书不对时失败、不提供“继续访问”。
 */
public final class HttpClient {
    public static final int DEFAULT_PORT = 80;
    public static final int DEFAULT_TLS_PORT = 443;
    /** 首部最多这么多字节 */
    static final int MAX_HEADER = 16 * 1024;
    /** 正文最多这么多字节 */
    public static final int MAX_BODY = 1024 * 1024;

    private HttpClient() {
    }

    public static final class Response {
        public final int status;
        public final String reason;
        /** 首部（名字小写；同名的用逗号连起来）。{@code Set-Cookie} 这类不能用逗号连的看 {@link #headerValues} */
        public final Map<String, String> headers;
        /** 首部（名字小写），同名的每条分开、按顺序 */
        private final Map<String, List<String>> allHeaders;
        public final byte[] body;
        /** 对方的地址与端口（{@link #send} 填；单独解析响应时为 null / 0） */
        public final Ipv6Address remoteAddress;
        public final int remotePort;
        /** https 时的连接信息；http 为 null */
        public final TlsInfo tls;

        Response(int status, String reason, Map<String, List<String>> allHeaders, byte[] body) {
            this(status, reason, allHeaders, body, null, 0, null);
        }

        Response(int status, String reason, Map<String, List<String>> allHeaders, byte[] body, Ipv6Address remoteAddress, int remotePort, TlsInfo tls) {
            this.status = status;
            this.reason = reason;
            Map<String, String> joined = new LinkedHashMap<>();
            Map<String, List<String>> all = new LinkedHashMap<>();
            for (Map.Entry<String, List<String>> e : allHeaders.entrySet()) {
                joined.put(e.getKey(), String.join(", ", e.getValue()));
                all.put(e.getKey(), Collections.unmodifiableList(new ArrayList<>(e.getValue())));
            }
            this.headers = Collections.unmodifiableMap(joined);
            this.allHeaders = Collections.unmodifiableMap(all);
            this.body = body;
            this.remoteAddress = remoteAddress;
            this.remotePort = remotePort;
            this.tls = tls;
        }

        Response with(Ipv6Address address, int port, TlsInfo tls) {
            return new Response(status, reason, allHeaders, body, address, port, tls);
        }

        public String header(String name) {
            return headers.get(name.toLowerCase(Locale.ROOT));
        }

        /** 同名首部的每一条（按收到的顺序）；没有为空表。{@code Set-Cookie} 要用这个取（日期里有逗号，不能按逗号拆） */
        public List<String> headerValues(String name) {
            List<String> v = allHeaders.get(name.toLowerCase(Locale.ROOT));
            return v == null ? Collections.<String>emptyList() : v;
        }

        /** 正文按 UTF-8 解码 */
        public String bodyText() {
            return new String(body, StandardCharsets.UTF_8);
        }
    }

    /** 分开的网址：只认 {@code http(s)://主机[:端口][/路径]}，主机可以是 {@code [IPv6 地址]}；Unicode 域名转成 A-label */
    public static final class Url {
        public final String host;
        public final int port;
        public final String path;
        /** https */
        public final boolean secure;

        Url(String host, int port, String path, boolean secure) {
            this.host = host;
            this.port = port;
            this.path = path;
            this.secure = secure;
        }

        /** 不认识时返回 null */
        public static Url parse(String url) {
            String u = url.trim();
            boolean secure = false;
            if (u.regionMatches(true, 0, "http://", 0, 7)) {
                u = u.substring(7);
            } else if (u.regionMatches(true, 0, "https://", 0, 8)) {
                u = u.substring(8);
                secure = true;
            } else if (u.contains("://")) {
                return null;
            }
            int slash = u.indexOf('/');
            String authority = slash < 0 ? u : u.substring(0, slash);
            String path = slash < 0 ? "/" : u.substring(slash);
            int port = secure ? DEFAULT_TLS_PORT : DEFAULT_PORT;
            String host;
            if (authority.startsWith("[")) {
                int end = authority.indexOf(']');
                if (end < 0) {
                    return null;
                }
                host = authority.substring(1, end);
                String rest = authority.substring(end + 1);
                if (rest.startsWith(":")) {
                    port = parsePort(rest.substring(1));
                } else if (!rest.isEmpty()) {
                    return null;
                }
            } else {
                int colon = authority.lastIndexOf(':');
                if (colon >= 0) {
                    host = authority.substring(0, colon);
                    port = parsePort(authority.substring(colon + 1));
                } else {
                    host = authority;
                }
            }
            if (host.isEmpty() || port < 1 || port > 65535 || path.indexOf(' ') >= 0 || path.indexOf('\r') >= 0 || path.indexOf('\n') >= 0) {
                return null;
            }
            if (!moe.qingu.youran.api.network.DomainNames.isAscii(host)) {
                // 国际化域名：请求、SNI、证书校验都用 A-label
                host = moe.qingu.youran.api.network.DomainNames.tryToAscii(host);
                if (host == null) {
                    return null;
                }
            }
            return new Url(host, port, path, secure);
        }

        private static int parsePort(String s) {
            try {
                return Integer.parseInt(s);
            } catch (NumberFormatException e) {
                return -1;
            }
        }

        public int defaultPort() {
            return secure ? DEFAULT_TLS_PORT : DEFAULT_PORT;
        }

        /** Host 首部的值 */
        public String hostHeader() {
            String h = host.indexOf(':') >= 0 ? "[" + host + "]" : host;
            return port == defaultPort() ? h : h + ":" + port;
        }

        @Override
        public String toString() {
            return (secure ? "https://" : "http://") + hostHeader() + path;
        }
    }

    /** 先用 {@code resolver} 查主机名，再取页面（GET；https 只信任 QinguMoe CA） */
    public static CompletableFuture<Response> fetch(NetStack stack, DnsResolver resolver, String url, long timeoutMillis) {
        return fetch(stack, null, resolver, url, timeoutMillis);
    }

    /** 同上，从网口 {@code via} 连（null 为按源地址选择规则，默认网口）；查名字用的 {@code resolver} 由调用方给（一般也绑这个网口） */
    public static CompletableFuture<Response> fetch(NetStack stack, NetStack.Interface via, DnsResolver resolver, String url, long timeoutMillis) {
        Url u = Url.parse(url);
        if (u == null) {
            CompletableFuture<Response> f = new CompletableFuture<>();
            f.completeExceptionally(new NetworkException(NetworkException.Reason.LIMIT, "不认识的网址：" + url));
            return f;
        }
        return send(stack, via, resolver, u, "GET", null, null, timeoutMillis, null);
    }

    /**
     * 发一个请求：先查主机名（失败时以 {@link NetworkException.Reason#NAME_NOT_FOUND} / {@link NetworkException.Reason#DNS_FAILED} 结束），
     * 再连上去（https 时先握手、校验证书），发请求、收响应。整个过程不超过 {@code timeoutMillis}。
     *
     * @param headers 额外的首部（Host、Connection、Content-Length 由这里写），可以为 null
     * @param body    请求正文（POST 表单等），可以为 null
     * @param trust   https 信任谁；null 为 QinguMoe CA
     */
    public static CompletableFuture<Response> send(NetStack stack, DnsResolver resolver, Url url, String method, Map<String, String> headers,
                                                   byte[] body, long timeoutMillis, TlsTrust trust) {
        return send(stack, null, resolver, url, method, headers, body, timeoutMillis, trust);
    }

    /** 同上，从网口 {@code via} 连（null 为按源地址选择规则，默认网口；网口去掉时以 {@link NetworkException.Reason#CLOSED} 结束） */
    public static CompletableFuture<Response> send(NetStack stack, NetStack.Interface via, DnsResolver resolver, Url url, String method,
                                                   Map<String, String> headers, byte[] body, long timeoutMillis, TlsTrust trust) {
        CompletableFuture<Response> f = new CompletableFuture<>();
        EventLoop.Timer timer = stack.loop.schedule(() -> f.completeExceptionally(
                new NetworkException(NetworkException.Reason.TIMEOUT, "取页面超时")), timeoutMillis);
        f.whenComplete((r, e) -> timer.cancel());
        resolver.resolveAaaa(url.host).whenComplete((r, err) -> {
            if (f.isDone()) {
                return;
            }
            if (err != null) {
                Throwable c = err instanceof java.util.concurrent.CompletionException && err.getCause() != null ? err.getCause() : err;
                f.completeExceptionally(new NetworkException(NetworkException.Reason.DNS_FAILED,
                        "解析 " + url.host + " 失败：" + c.getMessage()));
                return;
            }
            if (r.rcode == DnsMessage.RCODE_NXDOMAIN || (r.rcode == DnsMessage.RCODE_OK && r.addresses.isEmpty())) {
                f.completeExceptionally(new NetworkException(NetworkException.Reason.NAME_NOT_FOUND, "找不到 " + url.host + " 的地址"));
                return;
            }
            if (r.addresses.isEmpty()) {
                f.completeExceptionally(new NetworkException(NetworkException.Reason.DNS_FAILED,
                        "解析 " + url.host + " 失败（DNS 响应码 " + r.rcode + "）"));
                return;
            }
            request(stack, via, r.addresses.get(0), url, method, headers, body, trust == null ? TlsTrust.qinguMoe() : trust, f);
        });
        return f;
    }

    /** 连到 {@code address}:{@code port}，GET {@code path}（http） */
    public static CompletableFuture<Response> get(NetStack stack, Ipv6Address address, int port, String host, String path, long timeoutMillis) {
        CompletableFuture<Response> f = new CompletableFuture<>();
        EventLoop.Timer timer = stack.loop.schedule(() -> f.completeExceptionally(
                new NetworkException(NetworkException.Reason.TIMEOUT, "取页面超时")), timeoutMillis);
        f.whenComplete((r, e) -> timer.cancel());
        Url u = new Url(host, port, path, false);
        String request = requestText("GET", path, host, null, null);
        connectPlain(stack, null, address, u, request.getBytes(StandardCharsets.ISO_8859_1), f);
        return f;
    }

    static String requestText(String method, String path, String hostHeader, Map<String, String> headers, byte[] body) {
        StringBuilder sb = new StringBuilder();
        sb.append(method).append(' ').append(path).append(" HTTP/1.1\r\n");
        sb.append("Host: ").append(hostHeader).append("\r\n");
        sb.append("User-Agent: YouranMod\r\n");
        boolean accept = false;
        if (headers != null) {
            for (Map.Entry<String, String> e : headers.entrySet()) {
                String n = e.getKey();
                String v = e.getValue();
                if (!n.matches("[A-Za-z0-9-]+") || v.indexOf('\r') >= 0 || v.indexOf('\n') >= 0) {
                    throw new IllegalArgumentException("首部不合法：" + n);
                }
                String ln = n.toLowerCase(Locale.ROOT);
                if (ln.equals("host") || ln.equals("connection") || ln.equals("content-length") || ln.equals("transfer-encoding")) {
                    continue;
                }
                accept |= ln.equals("accept");
                sb.append(n).append(": ").append(v).append("\r\n");
            }
        }
        if (!accept) {
            sb.append("Accept: application/xhtml+xml, text/html;q=0.9, */*;q=0.8\r\n");
        }
        if (body != null) {
            sb.append("Content-Length: ").append(body.length).append("\r\n");
        }
        sb.append("Connection: close\r\n\r\n");
        return sb.toString();
    }

    private static void request(NetStack stack, NetStack.Interface via, Ipv6Address address, Url url, String method, Map<String, String> headers,
                                byte[] body, TlsTrust trust, CompletableFuture<Response> f) {
        byte[] head;
        try {
            head = requestText(method, url.path, url.hostHeader(), headers, body).getBytes(StandardCharsets.ISO_8859_1);
        } catch (IllegalArgumentException e) {
            f.completeExceptionally(new NetworkException(NetworkException.Reason.LIMIT, e.getMessage()));
            return;
        }
        byte[] all = head;
        if (body != null && body.length > 0) {
            all = new byte[head.length + body.length];
            System.arraycopy(head, 0, all, 0, head.length);
            System.arraycopy(body, 0, all, head.length, body.length);
        }
        if (!url.secure) {
            connectPlain(stack, via, address, url, all, f);
            return;
        }
        byte[] request = all;
        ResponseReader reader = new ResponseReader(f, address, url.port);
        TlsConnection.connect(stack, via, address, url.port, url.host, trust, new TlsConnection.Listener() {
            @Override
            public void onOpen(TlsConnection connection) {
                reader.tls = connection.info();
            }

            @Override
            public void onData(TlsConnection connection, byte[] data) {
                reader.data(data);
            }

            @Override
            public void onEnd(TlsConnection connection) {
                reader.end();
            }

            @Override
            public void onClosed(TlsConnection connection, NetworkException error) {
                reader.closed(error);
            }
        }).whenComplete((c, err) -> {
            if (err != null) {
                Throwable cause = err instanceof java.util.concurrent.CompletionException && err.getCause() != null ? err.getCause() : err;
                f.completeExceptionally(cause);
                return;
            }
            if (f.isDone()) {
                c.abort();
                return;
            }
            f.whenComplete((r, e) -> c.abort());
            c.write(request);
        });
    }

    private static void connectPlain(NetStack stack, NetStack.Interface via, Ipv6Address address, Url url, byte[] request, CompletableFuture<Response> f) {
        ResponseReader reader = new ResponseReader(f, address, url.port);
        TcpConnection.Listener listener = new TcpConnection.Listener() {
            @Override
            public void onData(TcpConnection connection, byte[] data) {
                reader.data(data);
            }

            @Override
            public void onEnd(TcpConnection connection) {
                reader.end();
            }

            @Override
            public void onClosed(TcpConnection connection, NetworkException error) {
                reader.closed(error);
            }
        };
        (via != null ? stack.connectVia(via, address, url.port, listener) : stack.connect(null, address, url.port, listener)).whenComplete((c, err) -> {
            if (err != null) {
                f.completeExceptionally(err);
                return;
            }
            if (f.isDone()) {
                c.abort();
                return;
            }
            f.whenComplete((r, e) -> c.abort());
            c.write(request);
        });
    }

    /** 边收边看响应是否完整 */
    static final class ResponseReader {
        private final CompletableFuture<Response> future;
        private final ByteArrayOutputStream buf = new ByteArrayOutputStream();
        private final Ipv6Address address;
        private final int port;
        TlsInfo tls;
        /** 首部里有 Content-Length 时整个响应的字节数；还不知道为 -1 */
        private long expectedTotal = -1;

        ResponseReader(CompletableFuture<Response> future, Ipv6Address address, int port) {
            this.future = future;
            this.address = address;
            this.port = port;
        }

        void data(byte[] data) {
            if (future.isDone()) {
                return;
            }
            buf.write(data, 0, data.length);
            if (buf.size() > MAX_HEADER + MAX_BODY + 64 * 1024) {
                future.completeExceptionally(new NetworkException(NetworkException.Reason.LIMIT, "页面太大"));
                return;
            }
            // 知道总长以后只看收够了没有，不每次都重新解析
            if (expectedTotal >= 0 && buf.size() < expectedTotal) {
                return;
            }
            try {
                byte[] all = buf.toByteArray();
                Response r = tryParse(all, false);
                if (r != null) {
                    future.complete(r.with(address, port, tls));
                } else if (expectedTotal < 0) {
                    expectedTotal = expectedLength(all);
                }
            } catch (NetworkException e) {
                future.completeExceptionally(e);
            }
        }

        void end() {
            finish();
        }

        void closed(NetworkException error) {
            if (error != null && !future.isDone()) {
                future.completeExceptionally(error);
                return;
            }
            finish();
        }

        private void finish() {
            if (future.isDone()) {
                return;
            }
            try {
                Response r = tryParse(buf.toByteArray(), true);
                if (r != null) {
                    future.complete(r.with(address, port, tls));
                } else {
                    future.completeExceptionally(new NetworkException(NetworkException.Reason.BAD_RESPONSE, "响应不完整"));
                }
            } catch (NetworkException e) {
                future.completeExceptionally(e);
            }
        }
    }

    /**
     * 解析响应；还不完整时返回 null（{@code ended} 为 true 表示连接已结束，这时没有长度的正文算到结尾）。
     */
    static Response tryParse(byte[] b, boolean ended) throws NetworkException {
        int headerEnd = indexOf(b, 0, new byte[]{'\r', '\n', '\r', '\n'});
        if (headerEnd < 0) {
            if (b.length > MAX_HEADER) {
                throw new NetworkException(NetworkException.Reason.BAD_RESPONSE, "响应首部太长");
            }
            return null;
        }
        String head = new String(b, 0, headerEnd, StandardCharsets.ISO_8859_1);
        String[] lines = head.split("\r\n");
        String[] status = lines[0].split(" ", 3);
        if (status.length < 2 || !status[0].startsWith("HTTP/1.")) {
            throw new NetworkException(NetworkException.Reason.BAD_RESPONSE, "不是 HTTP/1.x 响应");
        }
        int code;
        try {
            code = Integer.parseInt(status[1]);
        } catch (NumberFormatException e) {
            throw new NetworkException(NetworkException.Reason.BAD_RESPONSE, "状态码不对");
        }
        Map<String, List<String>> headers = new LinkedHashMap<>();
        for (int i = 1; i < lines.length; i++) {
            int colon = lines[i].indexOf(':');
            if (colon <= 0) {
                throw new NetworkException(NetworkException.Reason.BAD_RESPONSE, "首部行不对");
            }
            String name = lines[i].substring(0, colon).trim().toLowerCase(Locale.ROOT);
            String value = lines[i].substring(colon + 1).trim();
            headers.computeIfAbsent(name, k -> new ArrayList<>()).add(value);
        }
        int bodyStart = headerEnd + 4;
        String reason = status.length > 2 ? status[2] : "";
        byte[] body;
        String te = headers.containsKey("transfer-encoding") ? String.join(", ", headers.get("transfer-encoding")) : null;
        String cl = headers.containsKey("content-length") ? String.join(", ", headers.get("content-length")) : null;
        if (code / 100 == 1 || code == 204 || code == 304) {
            body = new byte[0];
        } else if (te != null && te.toLowerCase(Locale.ROOT).endsWith("chunked")) {
            body = dechunk(b, bodyStart);
            if (body == null) {
                if (ended) {
                    throw new NetworkException(NetworkException.Reason.BAD_RESPONSE, "分块正文不完整");
                }
                return null;
            }
        } else if (cl != null) {
            long len;
            try {
                len = Long.parseLong(cl.split(",")[0].trim());
            } catch (NumberFormatException e) {
                throw new NetworkException(NetworkException.Reason.BAD_RESPONSE, "Content-Length 不对");
            }
            if (len < 0 || len > MAX_BODY) {
                throw new NetworkException(NetworkException.Reason.LIMIT, "页面太大");
            }
            if (b.length - bodyStart < len) {
                if (ended) {
                    throw new NetworkException(NetworkException.Reason.BAD_RESPONSE, "正文不完整");
                }
                return null;
            }
            body = new byte[(int) len];
            System.arraycopy(b, bodyStart, body, 0, body.length);
        } else {
            if (!ended) {
                return null;
            }
            body = new byte[b.length - bodyStart];
            System.arraycopy(b, bodyStart, body, 0, body.length);
        }
        if (body.length > MAX_BODY) {
            throw new NetworkException(NetworkException.Reason.LIMIT, "页面太大");
        }
        return new Response(code, reason, headers, body);
    }

    /** 首部已完整且有 Content-Length（没有分块）时返回整个响应的长度，否则 -1 */
    static long expectedLength(byte[] b) {
        int headerEnd = indexOf(b, 0, new byte[]{'\r', '\n', '\r', '\n'});
        if (headerEnd < 0) {
            return -1;
        }
        String head = new String(b, 0, headerEnd, StandardCharsets.ISO_8859_1).toLowerCase(Locale.ROOT);
        if (head.contains("\r\ntransfer-encoding:")) {
            return -1;
        }
        int i = head.indexOf("\r\ncontent-length:");
        if (i < 0) {
            return -1;
        }
        int end = head.indexOf("\r\n", i + 2);
        String v = head.substring(i + 17, end < 0 ? head.length() : end).trim();
        try {
            return headerEnd + 4 + Long.parseLong(v.split(",")[0].trim());
        } catch (NumberFormatException e) {
            return -1;
        }
    }

    /** 分块正文解码；不完整时返回 null */
    static byte[] dechunk(byte[] b, int start) throws NetworkException {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        int p = start;
        while (true) {
            int lineEnd = indexOf(b, p, new byte[]{'\r', '\n'});
            if (lineEnd < 0) {
                return null;
            }
            String sizeLine = new String(b, p, lineEnd - p, StandardCharsets.ISO_8859_1);
            int semi = sizeLine.indexOf(';');
            if (semi >= 0) {
                sizeLine = sizeLine.substring(0, semi);
            }
            int size;
            try {
                size = Integer.parseInt(sizeLine.trim(), 16);
            } catch (NumberFormatException e) {
                throw new NetworkException(NetworkException.Reason.BAD_RESPONSE, "分块长度不对");
            }
            if (size < 0 || out.size() + (long) size > MAX_BODY) {
                throw new NetworkException(NetworkException.Reason.LIMIT, "页面太大");
            }
            p = lineEnd + 2;
            if (size == 0) {
                // 跳过尾部首部，到空行为止
                while (true) {
                    int e = indexOf(b, p, new byte[]{'\r', '\n'});
                    if (e < 0) {
                        return null;
                    }
                    if (e == p) {
                        return out.toByteArray();
                    }
                    p = e + 2;
                }
            }
            if (b.length < p + size + 2) {
                return null;
            }
            out.write(b, p, size);
            p += size + 2;
        }
    }

    static int indexOf(byte[] b, int from, byte[] pattern) {
        outer:
        for (int i = from; i <= b.length - pattern.length; i++) {
            for (int j = 0; j < pattern.length; j++) {
                if (b[i + j] != pattern[j]) {
                    continue outer;
                }
            }
            return i;
        }
        return -1;
    }
}
