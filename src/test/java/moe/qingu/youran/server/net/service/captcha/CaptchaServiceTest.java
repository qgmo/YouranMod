package moe.qingu.youran.server.net.service.captcha;

import moe.qingu.youran.api.network.Ipv6Address;
import moe.qingu.youran.api.network.stack.DnsResolver;
import moe.qingu.youran.api.network.stack.HttpClient;
import moe.qingu.youran.api.network.stack.NetStack;
import moe.qingu.youran.api.network.stack.TestLink;
import org.junit.jupiter.api.Test;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.Socket;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.concurrent.AbstractExecutorService;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** captcha.yr 的接口（经游戏内协议栈）与本机端口 */
class CaptchaServiceTest {
    static final Ipv6Address SITE = Ipv6Address.parse("fd00::7");

    /** 测试里画图就在调用的线程上画 */
    static final class Inline extends AbstractExecutorService {
        boolean down;

        @Override
        public void execute(Runnable command) {
            command.run();
        }

        @Override
        public void shutdown() {
            down = true;
        }

        @Override
        public List<Runnable> shutdownNow() {
            down = true;
            return Collections.emptyList();
        }

        @Override
        public boolean isShutdown() {
            return down;
        }

        @Override
        public boolean isTerminated() {
            return down;
        }

        @Override
        public boolean awaitTermination(long timeout, TimeUnit unit) {
            return true;
        }
    }

    final TestLink link = new TestLink();
    final NetStack visitor = link.host("fd00::a");
    final NetStack other = link.host("fd00::b");
    final AtomicLong now = new AtomicLong(1_000_000);
    final CaptchaService service = new CaptchaService(new CaptchaStore(new Random(5), 100, 4), now::get, new Inline());

    CaptchaServiceTest() {
        service.start(link.host(SITE.toString()), null);
        link.loop.advance(10);
    }

    HttpClient.Response call(NetStack from, String method, String path, String form) {
        Map<String, String> h = form == null ? null : Collections.singletonMap("Content-Type", "application/x-www-form-urlencoded");
        CompletableFuture<HttpClient.Response> f = HttpClient.send(from, new DnsResolver(from, SITE), HttpClient.Url.parse("http://[" + SITE + "]" + path),
                method, h, form == null ? null : form.getBytes(StandardCharsets.US_ASCII), 30_000, null);
        link.loop.advance(30_000);
        return f.join();
    }

    @Test
    void newImageVerify() throws Exception {
        HttpClient.Response n = call(visitor, "POST", "/new", "");
        assertEquals(200, n.status);
        assertTrue(n.header("content-type").startsWith("text/plain"));
        String id = n.bodyText();
        assertTrue(CaptchaStore.validId(id), "正文就是题号，没有别的：" + id);

        HttpClient.Response img = call(other, "GET", "/img/" + id + ".png", null);
        assertEquals(200, img.status);
        assertEquals("image/png", img.header("content-type"));
        assertEquals("no-store", img.header("cache-control"));
        BufferedImage bi = ImageIO.read(new ByteArrayInputStream(img.body));
        assertEquals(160, bi.getWidth());
        assertEquals(60, bi.getHeight());
        assertTrue(java.util.Arrays.equals(img.body, call(visitor, "GET", "/img/" + id + ".png", null).body), "再要一次是同一张");

        String answer = service.store().peek(id, now.get()).answer;
        assertEquals("ok", call(other, "POST", "/verify", "id=" + id + "&answer=" + answer.toLowerCase()).bodyText());
        assertEquals("fail", call(other, "POST", "/verify", "id=" + id + "&answer=" + answer).bodyText(), "只核对一次");
        assertEquals(404, call(visitor, "GET", "/img/" + id + ".png", null).status, "核对过的图也没了");
        assertEquals(404, call(visitor, "GET", "/img/zz.png", null).status);
    }

    @Test
    void methodsAndLimits() {
        assertEquals(405, call(visitor, "GET", "/new", null).status);
        assertEquals(405, call(visitor, "GET", "/verify", null).status);
        assertEquals(200, call(visitor, "GET", "/", null).status);
        // 出题：每个来源攒 6 次，之后 5 秒一次
        for (int i = 0; i < 6; i++) {
            assertEquals(200, call(visitor, "POST", "/new", "").status);
        }
        assertEquals(429, call(visitor, "POST", "/new", "").status);
        assertEquals(200, call(other, "POST", "/new", "").status, "别的来源不受影响");
        now.addAndGet(5_000);
        assertEquals(200, call(visitor, "POST", "/new", "").status);
        // 每个来源同时最多 4 题
        assertEquals(5, service.store().size());
        // 核对也限频
        int ok = 0;
        for (int i = 0; i < 10; i++) {
            if (call(visitor, "POST", "/verify", "id=x&answer=y").status == 200) {
                ok++;
            }
        }
        assertEquals(6, ok);
    }

    @Test
    void localPort() throws Exception {
        CaptchaLocalServer local = new CaptchaLocalServer(service, now::get);
        local.start(0);
        try {
            String id = post(local.port(), "/new", "");
            assertTrue(CaptchaStore.validId(id), id);
            // 游戏内也能取这题的图
            assertEquals(200, call(visitor, "GET", "/img/" + id + ".png", null).status);
            String answer = service.store().peek(id, now.get()).answer;
            assertEquals("fail", post(local.port(), "/verify", "id=" + id + "&answer=WRONG"));
            assertEquals("fail", post(local.port(), "/verify", "id=" + id + "&answer=" + answer), "答错过一次就作废");
            String id2 = post(local.port(), "/new", "");
            assertEquals("ok", post(local.port(), "/verify", "id=" + id2 + "&answer=" + service.store().peek(id2, now.get()).answer));
            // Host 不是本机的不收（防 DNS 重绑定）
            assertTrue(raw(local.port(), "POST /new HTTP/1.1\r\nHost: evil.example\r\nConnection: close\r\nContent-Length: 0\r\n\r\n").startsWith("HTTP/1.1 403"));
            assertTrue(raw(local.port(), "GET /new HTTP/1.1\r\nHost: 127.0.0.1\r\nConnection: close\r\n\r\n").startsWith("HTTP/1.1 405"));
            assertTrue(raw(local.port(), "POST /img/x.png HTTP/1.1\r\nHost: localhost\r\nConnection: close\r\nContent-Length: 0\r\n\r\n").startsWith("HTTP/1.1 404"));
        } finally {
            local.stop();
        }
        assertTrue(CaptchaLocalServer.localHost("127.0.0.1:26241"));
        assertTrue(CaptchaLocalServer.localHost("[::1]:80"));
        assertTrue(CaptchaLocalServer.localHost("LOCALHOST"));
        assertTrue(!CaptchaLocalServer.localHost("127.0.0.1.evil.example"));
        assertTrue(!CaptchaLocalServer.localHost("localhost:abc"));
    }

    static String post(int port, String path, String body) throws Exception {
        HttpURLConnection c = (HttpURLConnection) new URL("http://127.0.0.1:" + port + path).openConnection();
        c.setRequestMethod("POST");
        c.setDoOutput(true);
        c.setRequestProperty("Content-Type", "application/x-www-form-urlencoded");
        try (OutputStream o = c.getOutputStream()) {
            o.write(body.getBytes(StandardCharsets.US_ASCII));
        }
        assertEquals(200, c.getResponseCode());
        try (InputStream in = c.getInputStream()) {
            return read(in);
        }
    }

    static String raw(int port, String request) throws Exception {
        try (Socket s = new Socket("127.0.0.1", port)) {
            s.setSoTimeout(5000);
            s.getOutputStream().write(request.getBytes(StandardCharsets.ISO_8859_1));
            return read(s.getInputStream());
        }
    }

    static String read(InputStream in) throws Exception {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        byte[] b = new byte[1024];
        int n;
        while ((n = in.read(b)) > 0) {
            out.write(b, 0, n);
        }
        return new String(out.toByteArray(), StandardCharsets.UTF_8);
    }
}
