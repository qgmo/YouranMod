package moe.qingu.youran.api.network.http;

import moe.qingu.youran.api.network.Ipv6Address;
import moe.qingu.youran.api.network.stack.HttpClient;
import moe.qingu.youran.api.network.stack.NetStack;
import moe.qingu.youran.api.network.stack.NetworkException;
import moe.qingu.youran.api.network.stack.TcpConnection;
import moe.qingu.youran.api.network.stack.TestLink;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** 游戏内网站用的 HTTP 服务器：请求解析的规则与上限、和 {@link HttpClient} 互通、超时 */
class HttpServerTest {
    static final Ipv6Address WEB = Ipv6Address.parse("fd00::80");

    final TestLink link = new TestLink();
    final NetStack client = link.host("fd00::c");
    final NetStack server = link.host(WEB.toString());

    static int status(String raw) {
        HttpRequestParser.Result r = HttpServer.parseForTest(raw, new HttpServer.Options().maxBodyBytes(16));
        if (r == HttpRequestParser.Result.MORE) {
            return -1;
        }
        return r.error;
    }

    @Test
    void parsingRules() {
        assertEquals(0, status("GET / HTTP/1.1\r\nHost: a.yr\r\n\r\n"));
        assertEquals(0, status("\r\nGET /x?y=1 HTTP/1.0\r\n\r\n"), "前面的空行忽略；HTTP/1.0 可以没有 Host");
        assertEquals(-1, status("GET / HTTP/1.1\r\nHost: a.yr\r\n"), "还没到空行");
        assertEquals(400, status("GET / HTTP/1.1\r\n\r\n"), "1.1 没有 Host");
        assertEquals(400, status("GET / HTTP/1.1\r\nHost: a\r\nHost: b\r\n\r\n"), "两个 Host");
        assertEquals(400, status("GET http://a.yr/ HTTP/1.1\r\nHost: a.yr\r\n\r\n"), "只收 origin-form");
        assertEquals(400, status("GET / HTTP/1.1\r\nHost : a.yr\r\n\r\n"), "名字和冒号之间有空白");
        assertEquals(400, status("GET / HTTP/1.1\r\nHost: a.yr\r\n X: folded\r\n\r\n"), "折行");
        assertEquals(400, status("GET / HTTP/1.1\r\nHost: a\u0001\r\n\r\n"), "控制字符");
        assertEquals(505, status("GET / HTTP/2.0\r\nHost: a.yr\r\n\r\n"));
        assertEquals(501, status("POST / HTTP/1.1\r\nHost: a.yr\r\nTransfer-Encoding: chunked\r\n\r\n0\r\n\r\n"), "不收分块上传");
        assertEquals(400, status("POST / HTTP/1.1\r\nHost: a.yr\r\nContent-Length: 3\r\nContent-Length: 4\r\n\r\nabcd"), "长度不一致");
        assertEquals(400, status("POST / HTTP/1.1\r\nHost: a.yr\r\nContent-Length: -1\r\n\r\n"));
        assertEquals(413, status("POST / HTTP/1.1\r\nHost: a.yr\r\nContent-Length: 17\r\n\r\n"), "正文超过上限");
        assertEquals(-1, status("POST / HTTP/1.1\r\nHost: a.yr\r\nContent-Length: 5\r\n\r\nabc"), "正文还没收齐");
        StringBuilder big = new StringBuilder("GET / HTTP/1.1\r\nHost: a.yr\r\n");
        while (big.length() < 9000) {
            big.append("X-Pad: 0123456789012345678901234567890123456789\r\n");
        }
        assertEquals(431, status(big.toString()), "首部太长（还没到空行就超了）");
        assertEquals(431, status(big + "\r\n"));
    }

    @Test
    void requestFieldsAndForm() {
        HttpRequestParser.Result r = HttpServer.parseForTest("POST /verify?x=1 HTTP/1.1\r\nHost: captcha.yr\r\n"
                + "Content-Type: application/x-www-form-urlencoded\r\nContent-Length: 30\r\nX-A: 1\r\nx-a: 2\r\n\r\nid=abc&answer=%E4%BD%A0+b&id=zz", new HttpServer.Options());
        assertEquals(0, r.error);
        HttpRequest req = new HttpRequest(r.method, r.target, r.version, r.headers, r.body, WEB, 1, WEB, 80, false);
        assertEquals("/verify", req.path);
        assertEquals("x=1", req.query);
        assertEquals(Arrays.asList("1", "2"), req.headers.getAll("X-A"));
        Map<String, String> form = req.form();
        assertEquals("abc", form.get("id"), "同名取第一个");
        assertEquals("你 b", form.get("answer"));
        assertEquals("1", req.queryParameters().get("x"));
    }

    @Test
    void responseEncodingManagesFramingHeaders() {
        HttpHeaders h = new HttpHeaders();
        h.add("Set-Cookie", "a=1; Path=/");
        h.add("Set-Cookie", "b=2; Expires=Wed, 21 Oct 2026 07:28:00 GMT");
        h.add("Content-Length", "999");
        h.add("Transfer-Encoding", "chunked");
        h.add("Connection", "keep-alive");
        String s = new String(new HttpResponse(200, "OK", h, "hi".getBytes(StandardCharsets.UTF_8))
                .encode(false, java.time.ZonedDateTime.now()), StandardCharsets.ISO_8859_1);
        assertTrue(s.startsWith("HTTP/1.1 200 OK\r\n"));
        assertTrue(s.contains("Set-Cookie: a=1; Path=/\r\nSet-Cookie: b=2; Expires=Wed, 21 Oct 2026 07:28:00 GMT\r\n"));
        assertTrue(s.contains("Content-Length: 2\r\n"));
        assertFalse(s.contains("999"));
        assertFalse(s.contains("chunked"));
        assertFalse(s.contains("keep-alive"));
        assertTrue(s.endsWith("Connection: close\r\n\r\nhi"));
        try {
            new HttpHeaders().add("X", "a\r\nInjected: 1");
            throw new AssertionError("首部注入没挡住");
        } catch (IllegalArgumentException expected) {
            // 挡住了
        }
    }

    private HttpClient.Response fetch(String method, String path, Map<String, String> headers, byte[] body) {
        CompletableFuture<HttpClient.Response> f = HttpClient.send(client, new moe.qingu.youran.api.network.stack.DnsResolver(client, WEB), HttpClient.Url.parse("http://[" + WEB + "]" + path),
                method, headers, body, 60_000, null);
        link.loop.advance(60_000);
        assertTrue(f.isDone(), "没有回应");
        return f.join();
    }

    @Test
    void servesRequestsAndMultipleSetCookieReachTheClient() {
        AtomicReference<HttpRequest> seen = new AtomicReference<>();
        HttpServer.listen(server, 80, null, new HttpServer.Options(), req -> {
            seen.set(req);
            HttpHeaders h = new HttpHeaders();
            h.add("Content-Type", "text/plain");
            h.add("Set-Cookie", "sid=1; Expires=Wed, 21 Oct 2026 07:28:00 GMT; Path=/");
            h.add("Set-Cookie", "lang=zh");
            return CompletableFuture.completedFuture(new HttpResponse(200, "OK", h, ("got " + req.body.length).getBytes(StandardCharsets.UTF_8)));
        });
        link.loop.advance(10);
        Map<String, String> headers = new LinkedHashMap<>();
        headers.put("Content-Type", "application/x-www-form-urlencoded");
        HttpClient.Response r = fetch("POST", "/a?b=c", headers, "x=1&y=2".getBytes(StandardCharsets.US_ASCII));
        assertEquals(200, r.status);
        assertEquals("got 7", r.bodyText());
        assertEquals(Arrays.asList("sid=1; Expires=Wed, 21 Oct 2026 07:28:00 GMT; Path=/", "lang=zh"), r.headerValues("set-cookie"));
        assertEquals("/a", seen.get().path);
        assertEquals("1", seen.get().form().get("x"));
        assertEquals(Ipv6Address.parse("fd00::c"), seen.get().remoteAddress);
        assertFalse(seen.get().secure);
    }

    @Test
    void bodyTooLargeAndHandlerErrors() {
        HttpServer.listen(server, 80, null, new HttpServer.Options().maxBodyBytes(10).handlerTimeoutMillis(5_000), req -> {
            if (req.path.equals("/slow")) {
                return new CompletableFuture<>();
            }
            if (req.path.equals("/boom")) {
                throw new IllegalStateException("测试");
            }
            if (req.path.equals("/fail")) {
                CompletableFuture<HttpResponse> f = new CompletableFuture<>();
                f.completeExceptionally(new IllegalStateException("测试"));
                return f;
            }
            return CompletableFuture.completedFuture(HttpResponse.text(200, "ok"));
        });
        link.loop.advance(10);
        assertEquals(413, fetch("POST", "/", null, new byte[11]).status);
        assertEquals(200, fetch("POST", "/", null, new byte[10]).status);
        assertEquals(503, fetch("GET", "/slow", null, null).status, "处理器不回给 503");
        assertEquals(500, fetch("GET", "/boom", null, null).status);
        assertEquals(500, fetch("GET", "/fail", null, null).status);
    }

    @Test
    void customErrorPagesAndIdleTimeout() {
        HttpServer.listen(server, 80, null, new HttpServer.Options().idleTimeoutMillis(2_000)
                .errorPages(s -> HttpResponse.text(s, "自定义 " + s)), req -> CompletableFuture.completedFuture(HttpResponse.text(200, "ok")));
        link.loop.advance(10);
        Map<String, String> h = Collections.singletonMap("Transfer-Encoding", "chunked");
        // HttpClient 不发 Transfer-Encoding（会被它自己去掉），这里直接连上去发原始请求
        ByteArrayOutputStream got = new ByteArrayOutputStream();
        AtomicReference<NetworkException> closed = new AtomicReference<>();
        boolean[] ended = new boolean[1];
        CompletableFuture<TcpConnection> c = client.connect(null, WEB, 80, new TcpConnection.Listener() {
            @Override
            public void onData(TcpConnection connection, byte[] data) {
                got.write(data, 0, data.length);
            }

            @Override
            public void onClosed(TcpConnection connection, NetworkException error) {
                ended[0] = true;
                closed.set(error);
            }
        });
        link.loop.advance(100);
        c.join().write("POST / HTTP/1.1\r\nHost: x\r\nTransfer-Encoding: chunked\r\n\r\n".getBytes(StandardCharsets.ISO_8859_1));
        link.loop.advance(1_000);
        String resp = new String(got.toByteArray(), StandardCharsets.UTF_8);
        assertTrue(resp.startsWith("HTTP/1.1 501 "), resp);
        assertTrue(resp.endsWith("自定义 501"), resp);
        assertNotNull(h);

        // 连上不发东西：2 秒后断开
        ByteArrayOutputStream got2 = new ByteArrayOutputStream();
        boolean[] ended2 = new boolean[1];
        client.connect(null, WEB, 80, new TcpConnection.Listener() {
            @Override
            public void onData(TcpConnection connection, byte[] data) {
                got2.write(data, 0, data.length);
            }

            @Override
            public void onClosed(TcpConnection connection, NetworkException error) {
                ended2[0] = true;
            }
        });
        link.loop.advance(1_000);
        assertFalse(ended2[0]);
        link.loop.advance(3_000);
        assertTrue(ended2[0], "闲着的连接没断");
        assertEquals(0, got2.size());
    }
}
