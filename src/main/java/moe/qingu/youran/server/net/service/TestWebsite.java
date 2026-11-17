package moe.qingu.youran.server.net.service;

import moe.qingu.youran.api.network.ca.HttpChallenges;
import moe.qingu.youran.api.network.stack.EventLoop;
import moe.qingu.youran.api.network.stack.NetStack;
import moe.qingu.youran.api.network.stack.NetworkException;
import moe.qingu.youran.api.network.stack.TcpConnection;
import moe.qingu.youran.api.network.tls.TlsConnection;
import moe.qingu.youran.api.network.tls.TlsServerContext;

import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.time.ZoneOffset;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.util.Locale;

/**
 * 测试网站（{@code test.yr}，TCP 80；服务器配了中间 CA 时 443 口也开 HTTPS）：最简单的 HTTP/1.1 服务，GET / 或 /index.xhtml 给一页固定的 XHTML，HEAD 只给首部。
 * 每条连接只处理一个请求，回完就关（{@code Connection: close}）。请求首部超过 8 KiB 回 431，
 * 15 秒内没收齐请求就断开。
 */
public final class TestWebsite {
    public static final int PORT = 80;
    public static final int TLS_PORT = 443;
    static final int MAX_REQUEST = 8 * 1024;
    static final long IDLE_TIMEOUT_MS = 15_000;

    public static final String PAGE = "<?xml version=\"1.0\" encoding=\"UTF-8\"?>\n"
            + "<!DOCTYPE html PUBLIC \"-//W3C//DTD XHTML 1.0 Strict//EN\" \"http://www.w3.org/TR/xhtml1/DTD/xhtml1-strict.dtd\">\n"
            + "<html xmlns=\"http://www.w3.org/1999/xhtml\" xml:lang=\"zh-CN\" lang=\"zh-CN\">\n"
            + "<head>\n"
            + "<meta http-equiv=\"Content-Type\" content=\"application/xhtml+xml; charset=UTF-8\" />\n"
            + "<title>test.yr</title>\n"
            + "</head>\n"
            + "<body>\n"
            + "<h1>test.yr</h1>\n"
            + "<p>这是悠然服务器内网的测试网站。能看到这一页，说明 DNS 和 TCP 都通了。</p>\n"
            + "</body>\n"
            + "</html>\n";

    private static final DateTimeFormatter HTTP_DATE = DateTimeFormatter.ofPattern("EEE, dd MMM yyyy HH:mm:ss 'GMT'", Locale.ROOT);

    private TestWebsite() {
    }

    public static void start(NetStack stack) {
        start(stack, null);
    }

    /** 80 口 HTTP；{@code tls} 不为 null 时 443 口再开 HTTPS（同一页） */
    public static void start(NetStack stack, TlsServerContext tls) {
        stack.listen(null, PORT, c -> {
            Session s = new Session(stack.loop(), stack, new Out() {
                @Override
                public void write(byte[] d) {
                    c.write(d);
                }

                @Override
                public void close() {
                    c.close();
                }

                @Override
                public void abort() {
                    c.abort();
                }
            });
            c.setListener(new TcpConnection.Listener() {
                @Override
                public void onData(TcpConnection connection, byte[] data) {
                    s.onData(data);
                }

                @Override
                public void onEnd(TcpConnection connection) {
                    s.onEnd();
                }

                @Override
                public void onClosed(TcpConnection connection, NetworkException error) {
                    s.onClosed();
                }
            });
            s.start();
        });
        if (tls != null) {
            stack.listen(null, TLS_PORT, c -> {
                TlsConnection[] holder = new TlsConnection[1];
                Session s = new Session(stack.loop(), null, new Out() {
                    @Override
                    public void write(byte[] d) {
                        holder[0].write(d);
                    }

                    @Override
                    public void close() {
                        holder[0].close();
                    }

                    @Override
                    public void abort() {
                        holder[0].abort();
                    }
                });
                holder[0] = TlsConnection.accept(c, tls, new TlsConnection.Listener() {
                    @Override
                    public void onData(TlsConnection connection, byte[] data) {
                        s.onData(data);
                    }

                    @Override
                    public void onEnd(TlsConnection connection) {
                        s.onEnd();
                    }

                    @Override
                    public void onClosed(TlsConnection connection, NetworkException error) {
                        s.onClosed();
                    }
                });
                s.start();
            });
        }
    }

    /** 往连接里写（TCP 或 TLS） */
    private interface Out {
        void write(byte[] d);

        void close();

        void abort();
    }

    private static final class Session {
        private final EventLoop loop;
        /** 回证书服务的 HTTP 认证用（只在不加密的 80 口上；443 口为 null） */
        private final NetStack challenges;
        private final Out connection;
        private final ByteArrayOutputStream buf = new ByteArrayOutputStream();
        private EventLoop.Timer idle;
        private boolean answered;

        Session(EventLoop loop, NetStack challenges, Out connection) {
            this.loop = loop;
            this.challenges = challenges;
            this.connection = connection;
        }

        void start() {
            idle = loop.schedule(connection::abort, IDLE_TIMEOUT_MS);
        }

        void onData(byte[] data) {
            if (answered) {
                return;
            }
            buf.write(data, 0, data.length);
            byte[] b = buf.toByteArray();
            int end = indexOfHeaderEnd(b);
            if (end < 0) {
                if (b.length > MAX_REQUEST) {
                    respond(431, "Request Header Fields Too Large", false, null);
                }
                return;
            }
            String head = new String(b, 0, end, StandardCharsets.ISO_8859_1);
            byte[] response = challenges == null ? null : challengeResponse(challenges, head, ZonedDateTime.now(ZoneOffset.UTC));
            if (response == null) {
                response = responseFor(head, ZonedDateTime.now(ZoneOffset.UTC));
            }
            answered = true;
            idle.cancel();
            connection.write(response);
            connection.close();
        }

        void onEnd() {
            if (!answered) {
                answered = true;
                idle.cancel();
                connection.close();
            }
        }

        void onClosed() {
            if (idle != null) {
                idle.cancel();
            }
        }

        private void respond(int status, String reason, boolean head, byte[] body) {
            answered = true;
            idle.cancel();
            connection.write(build(status, reason, body == null ? errorPage(status, reason) : body, head,
                    ZonedDateTime.now(ZoneOffset.UTC), null));
            connection.close();
        }
    }

    static int indexOfHeaderEnd(byte[] b) {
        for (int i = 0; i + 3 < b.length; i++) {
            if (b[i] == '\r' && b[i + 1] == '\n' && b[i + 2] == '\r' && b[i + 3] == '\n') {
                return i;
            }
        }
        return -1;
    }

    /** 是这台主机正在等的证书服务 HTTP 认证（GET / HEAD {@code /.well-known/acme-challenge/令牌}）时回认证内容，否则 null */
    static byte[] challengeResponse(NetStack stack, String head, ZonedDateTime now) {
        String[] parts = head.split("\r\n", 2)[0].split(" ", -1);
        if (parts.length != 3 || !(parts[0].equals("GET") || parts[0].equals("HEAD"))) {
            return null;
        }
        String path = parts[1];
        int q = path.indexOf('?');
        String content = HttpChallenges.answer(stack, q < 0 ? path : path.substring(0, q));
        if (content == null) {
            return null;
        }
        byte[] body = content.getBytes(StandardCharsets.UTF_8);
        String h = "HTTP/1.1 200 OK\r\n"
                + "Date: " + HTTP_DATE.format(now) + "\r\n"
                + "Server: YouranMod\r\n"
                + "Content-Type: text/plain; charset=utf-8\r\n"
                + "Content-Length: " + body.length + "\r\n"
                + "Connection: close\r\n\r\n";
        byte[] hb = h.getBytes(StandardCharsets.ISO_8859_1);
        if (parts[0].equals("HEAD")) {
            return hb;
        }
        byte[] out = new byte[hb.length + body.length];
        System.arraycopy(hb, 0, out, 0, hb.length);
        System.arraycopy(body, 0, out, hb.length, body.length);
        return out;
    }

    /** 由请求首部（到空行之前）算响应（纯逻辑，测试用） */
    static byte[] responseFor(String head, ZonedDateTime now) {
        String[] lines = head.split("\r\n", -1);
        String[] parts = lines[0].split(" ", -1);
        if (parts.length != 3 || !parts[2].matches("HTTP/1\\.[01]") || parts[1].isEmpty()) {
            return build(400, "Bad Request", errorPage(400, "Bad Request"), false, now, null);
        }
        boolean http11 = parts[2].equals("HTTP/1.1");
        boolean hasHost = false;
        for (int i = 1; i < lines.length; i++) {
            int colon = lines[i].indexOf(':');
            if (colon <= 0) {
                return build(400, "Bad Request", errorPage(400, "Bad Request"), false, now, null);
            }
            if (lines[i].substring(0, colon).trim().equalsIgnoreCase("host")) {
                hasHost = true;
            }
        }
        // HTTP/1.1 的请求必须带 Host（RFC 9112 第 3.2 节）
        if (http11 && !hasHost) {
            return build(400, "Bad Request", errorPage(400, "Bad Request"), false, now, null);
        }
        String method = parts[0];
        boolean headOnly = method.equals("HEAD");
        if (!method.equals("GET") && !headOnly) {
            return build(405, "Method Not Allowed", errorPage(405, "Method Not Allowed"), false, now, "Allow: GET, HEAD\r\n");
        }
        String path = parts[1];
        int q = path.indexOf('?');
        if (q >= 0) {
            path = path.substring(0, q);
        }
        if (path.equals("/") || path.equals("/index.xhtml")) {
            return build(200, "OK", PAGE.getBytes(StandardCharsets.UTF_8), headOnly, now, null);
        }
        return build(404, "Not Found", errorPage(404, "Not Found"), headOnly, now, null);
    }

    static byte[] errorPage(int status, String reason) {
        String s = "<?xml version=\"1.0\" encoding=\"UTF-8\"?>\n"
                + "<html xmlns=\"http://www.w3.org/1999/xhtml\"><head><title>" + status + " " + reason
                + "</title></head><body><h1>" + status + " " + reason + "</h1></body></html>\n";
        return s.getBytes(StandardCharsets.UTF_8);
    }

    static byte[] build(int status, String reason, byte[] body, boolean headOnly, ZonedDateTime now, String extraHeaders) {
        String h = "HTTP/1.1 " + status + " " + reason + "\r\n"
                + "Date: " + HTTP_DATE.format(now) + "\r\n"
                + "Server: YouranMod\r\n"
                + "Content-Type: application/xhtml+xml; charset=utf-8\r\n"
                + "Content-Length: " + body.length + "\r\n"
                + (extraHeaders == null ? "" : extraHeaders)
                + "Connection: close\r\n\r\n";
        byte[] hb = h.getBytes(StandardCharsets.ISO_8859_1);
        if (headOnly) {
            return hb;
        }
        byte[] out = new byte[hb.length + body.length];
        System.arraycopy(hb, 0, out, 0, hb.length);
        System.arraycopy(body, 0, out, hb.length, body.length);
        return out;
    }
}
