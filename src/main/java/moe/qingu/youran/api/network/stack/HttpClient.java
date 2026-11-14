package moe.qingu.youran.api.network.stack;

import moe.qingu.youran.api.network.Ipv6Address;

import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.CompletableFuture;

/**
 * 最简单的 HTTP/1.1 客户端（RFC 9112）：一条 TCP 连接取一个页面（{@code Connection: close}），
 * 认 Content-Length、分块传输（chunked）和“读到连接关闭为止”三种正文。不跟随重定向，不支持 HTTPS。
 */
public final class HttpClient {
    public static final int DEFAULT_PORT = 80;
    /** 首部最多这么多字节 */
    static final int MAX_HEADER = 16 * 1024;
    /** 正文最多这么多字节 */
    public static final int MAX_BODY = 1024 * 1024;

    private HttpClient() {
    }

    public static final class Response {
        public final int status;
        public final String reason;
        /** 首部（名字小写；同名的用逗号连起来） */
        public final Map<String, String> headers;
        public final byte[] body;

        Response(int status, String reason, Map<String, String> headers, byte[] body) {
            this.status = status;
            this.reason = reason;
            this.headers = Collections.unmodifiableMap(headers);
            this.body = body;
        }

        public String header(String name) {
            return headers.get(name.toLowerCase(Locale.ROOT));
        }

        /** 正文按 UTF-8 解码 */
        public String bodyText() {
            return new String(body, StandardCharsets.UTF_8);
        }
    }

    /** 分开的网址：只认 {@code http://主机[:端口][/路径]}，主机可以是 {@code [IPv6 地址]} */
    public static final class Url {
        public final String host;
        public final int port;
        public final String path;

        Url(String host, int port, String path) {
            this.host = host;
            this.port = port;
            this.path = path;
        }

        /** 不认识时返回 null */
        public static Url parse(String url) {
            String u = url.trim();
            if (u.regionMatches(true, 0, "http://", 0, 7)) {
                u = u.substring(7);
            } else if (u.contains("://")) {
                return null;
            }
            int slash = u.indexOf('/');
            String authority = slash < 0 ? u : u.substring(0, slash);
            String path = slash < 0 ? "/" : u.substring(slash);
            int port = DEFAULT_PORT;
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
            return new Url(host, port, path);
        }

        private static int parsePort(String s) {
            try {
                return Integer.parseInt(s);
            } catch (NumberFormatException e) {
                return -1;
            }
        }

        /** Host 首部的值 */
        public String hostHeader() {
            String h = host.indexOf(':') >= 0 ? "[" + host + "]" : host;
            return port == DEFAULT_PORT ? h : h + ":" + port;
        }
    }

    /** 先用 {@code resolver} 查主机名，再取页面 */
    public static CompletableFuture<Response> fetch(NetStack stack, DnsResolver resolver, String url, long timeoutMillis) {
        Url u = Url.parse(url);
        if (u == null) {
            CompletableFuture<Response> f = new CompletableFuture<>();
            f.completeExceptionally(new NetworkException(NetworkException.Reason.LIMIT, "不认识的网址：" + url));
            return f;
        }
        return resolver.resolveAaaa(u.host).thenCompose(r -> {
            if (r.addresses.isEmpty()) {
                CompletableFuture<Response> f = new CompletableFuture<>();
                f.completeExceptionally(new NetworkException(NetworkException.Reason.UNREACHABLE,
                        "找不到 " + u.host + " 的地址（DNS 响应码 " + r.rcode + "）"));
                return f;
            }
            return get(stack, r.addresses.get(0), u.port, u.hostHeader(), u.path, timeoutMillis);
        });
    }

    /** 连到 {@code address}:{@code port}，GET {@code path} */
    public static CompletableFuture<Response> get(NetStack stack, Ipv6Address address, int port, String host, String path, long timeoutMillis) {
        CompletableFuture<Response> f = new CompletableFuture<>();
        String request = "GET " + path + " HTTP/1.1\r\n"
                + "Host: " + host + "\r\n"
                + "User-Agent: YouranMod\r\n"
                + "Accept: application/xhtml+xml, text/html;q=0.9, */*;q=0.8\r\n"
                + "Connection: close\r\n\r\n";
        ResponseReader reader = new ResponseReader(f);
        EventLoop.Timer timer = stack.loop.schedule(() -> f.completeExceptionally(
                new NetworkException(NetworkException.Reason.TIMEOUT, "取页面超时")), timeoutMillis);
        f.whenComplete((r, e) -> timer.cancel());
        stack.connect(null, address, port, reader).whenComplete((c, err) -> {
            if (err != null) {
                f.completeExceptionally(err);
                return;
            }
            if (f.isDone()) {
                c.abort();
                return;
            }
            f.whenComplete((r, e) -> c.abort());
            c.write(request.getBytes(StandardCharsets.ISO_8859_1));
        });
        return f;
    }

    /** 边收边看响应是否完整 */
    static final class ResponseReader implements TcpConnection.Listener {
        private final CompletableFuture<Response> future;
        private final ByteArrayOutputStream buf = new ByteArrayOutputStream();
        /** 首部里有 Content-Length 时整个响应的字节数；还不知道为 -1 */
        private long expectedTotal = -1;

        ResponseReader(CompletableFuture<Response> future) {
            this.future = future;
        }

        @Override
        public void onData(TcpConnection c, byte[] data) {
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
                    future.complete(r);
                } else if (expectedTotal < 0) {
                    expectedTotal = expectedLength(all);
                }
            } catch (NetworkException e) {
                future.completeExceptionally(e);
            }
        }

        @Override
        public void onEnd(TcpConnection c) {
            finish();
        }

        @Override
        public void onClosed(TcpConnection c, NetworkException error) {
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
                    future.complete(r);
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
        Map<String, String> headers = new LinkedHashMap<>();
        for (int i = 1; i < lines.length; i++) {
            int colon = lines[i].indexOf(':');
            if (colon <= 0) {
                throw new NetworkException(NetworkException.Reason.BAD_RESPONSE, "首部行不对");
            }
            String name = lines[i].substring(0, colon).trim().toLowerCase(Locale.ROOT);
            String value = lines[i].substring(colon + 1).trim();
            headers.merge(name, value, (a, v) -> a + ", " + v);
        }
        int bodyStart = headerEnd + 4;
        String reason = status.length > 2 ? status[2] : "";
        byte[] body;
        String te = headers.get("transfer-encoding");
        String cl = headers.get("content-length");
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
