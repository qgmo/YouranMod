package moe.qingu.youran.api.network.http;

import moe.qingu.youran.api.network.Ipv6Address;
import moe.qingu.youran.api.network.stack.EventLoop;
import moe.qingu.youran.api.network.stack.NetStack;
import moe.qingu.youran.api.network.stack.NetworkException;
import moe.qingu.youran.api.network.stack.TcpConnection;
import moe.qingu.youran.api.network.tls.TlsConnection;
import moe.qingu.youran.api.network.tls.TlsServerContext;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.time.ZoneOffset;
import java.time.ZonedDateTime;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;

/**
 * 跑在游戏内协议栈上的最简单的 HTTP/1.1 服务器（服务端的网站用）：每条连接只收一个请求，回完就关（{@code Connection: close}）。
 * 请求按 {@link HttpRequestParser} 的规则检查（首部、正文的上限，不收分块上传）；连接 {@link Options#idleTimeoutMillis} 内没收齐请求就断开；
 * 处理器 {@link Options#handlerTimeoutMillis} 内没给出响应回 503。
 * <p>
 * <b>线程</b>：连接的事件、{@link Handler#handle} 都在协议栈的事件循环（网络线程）上调用，<b>处理器不能在里面阻塞</b>：
 * 要做慢事（读写文件、画图、连别的服务器、动游戏状态）就交给别的线程，返回一个之后再完成的 future（在哪个线程完成都可以，
 * 回包会转回网络线程）。
 */
public final class HttpServer {
    private static final Logger LOG = LogManager.getLogger("YouranMod");

    /** 处理一个请求（网络线程上调用，不要阻塞） */
    @FunctionalInterface
    public interface Handler {
        CompletableFuture<HttpResponse> handle(HttpRequest request);
    }

    /** 服务器自己回的错误页（请求不合格、超时、处理器出错）；网站可以换成自己的样子 */
    @FunctionalInterface
    public interface ErrorPages {
        HttpResponse page(int status);
    }

    /** 上限与时限 */
    public static final class Options {
        int maxHeaderBytes = 8 * 1024;
        int maxBodyBytes = 16 * 1024;
        long idleTimeoutMillis = 15_000;
        long handlerTimeoutMillis = 30_000;
        ErrorPages errorPages = HttpServer::defaultErrorPage;

        public Options() {
        }

        /** 请求首部（请求行 + 首部，不含空行）最多多少字节；默认 8 KiB，超了回 431 */
        public Options maxHeaderBytes(int n) {
            this.maxHeaderBytes = Math.max(256, n);
            return this;
        }

        /** 请求正文最多多少字节；默认 16 KiB，超了回 413 */
        public Options maxBodyBytes(int n) {
            this.maxBodyBytes = Math.max(0, n);
            return this;
        }

        /** 连上以后多久没收齐请求就断开；默认 15 秒 */
        public Options idleTimeoutMillis(long ms) {
            this.idleTimeoutMillis = Math.max(1, ms);
            return this;
        }

        /** 处理器多久没给出响应就回 503；默认 30 秒 */
        public Options handlerTimeoutMillis(long ms) {
            this.handlerTimeoutMillis = Math.max(1, ms);
            return this;
        }

        public Options errorPages(ErrorPages pages) {
            this.errorPages = pages == null ? HttpServer::defaultErrorPage : pages;
            return this;
        }
    }

    private HttpServer() {
    }

    /**
     * 在 {@code stack} 的 TCP {@code port} 上开 HTTP；{@code tls} 不为 null 时这个口是 HTTPS（证书由 {@link TlsServerContext} 给）。
     * 一般一个网站开两次：80 口不带 TLS，443 口带 {@code HostContext.tls()}（服务器没配中间 CA 时为 null，就不开 443）。
     */
    public static void listen(NetStack stack, int port, TlsServerContext tls, Options options, Handler handler) {
        Options o = options == null ? new Options() : options;
        if (tls == null) {
            stack.listen(null, port, c -> {
                Session s = new Session(stack.loop(), o, handler, c.remoteAddress(), c.remotePort(), c.localAddress(), c.localPort(), false, new Out() {
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
            return;
        }
        stack.listen(null, port, c -> {
            TlsConnection[] holder = new TlsConnection[1];
            Session s = new Session(stack.loop(), o, handler, c.remoteAddress(), c.remotePort(), c.localAddress(), c.localPort(), true, new Out() {
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

    /** 默认的错误页：一行状态码的 XHTML */
    public static HttpResponse defaultErrorPage(int status) {
        String reason = HttpResponse.reasonPhrase(status);
        String page = "<?xml version=\"1.0\" encoding=\"UTF-8\"?>\n"
                + "<html xmlns=\"http://www.w3.org/1999/xhtml\"><head><title>" + status + " " + reason
                + "</title></head><body><h1>" + status + " " + reason + "</h1></body></html>\n";
        return HttpResponse.xhtml(status, page);
    }

    private interface Out {
        void write(byte[] d);

        void close();

        void abort();
    }

    /** 一条连接（只在事件循环线程上动） */
    static final class Session {
        private final EventLoop loop;
        private final Options options;
        private final Handler handler;
        private final Ipv6Address remote;
        private final int remotePort;
        private final Ipv6Address local;
        private final int localPort;
        private final boolean secure;
        private final Out out;
        private final ByteArrayOutputStream buf = new ByteArrayOutputStream();
        private EventLoop.Timer timer;
        /** 收齐了请求（或者已经回了错误）：之后收到的数据不管 */
        private boolean done;
        private boolean closed;

        Session(EventLoop loop, Options options, Handler handler, Ipv6Address remote, int remotePort, Ipv6Address local, int localPort,
                boolean secure, Out out) {
            this.loop = loop;
            this.options = options;
            this.handler = handler;
            this.remote = remote;
            this.remotePort = remotePort;
            this.local = local;
            this.localPort = localPort;
            this.secure = secure;
            this.out = out;
        }

        void start() {
            timer = loop.schedule(() -> {
                if (!done) {
                    done = true;
                    out.abort();
                }
            }, options.idleTimeoutMillis);
        }

        void onData(byte[] data) {
            if (done) {
                return;
            }
            buf.write(data, 0, data.length);
            if (buf.size() > options.maxHeaderBytes + options.maxBodyBytes + 8) {
                // 首部里的 Content-Length 还没看到就已经太多（或者正文比说的多）：按太大处理
                HttpRequestParser.Result r = HttpRequestParser.parse(buf.toByteArray(), buf.size(), options.maxHeaderBytes, options.maxBodyBytes);
                fail(r.error != 0 ? r.error : 413);
                return;
            }
            byte[] b = buf.toByteArray();
            HttpRequestParser.Result r = HttpRequestParser.parse(b, b.length, options.maxHeaderBytes, options.maxBodyBytes);
            if (r == HttpRequestParser.Result.MORE) {
                return;
            }
            if (r.error != 0) {
                fail(r.error);
                return;
            }
            done = true;
            cancelTimer();
            HttpRequest req = new HttpRequest(r.method, r.target, r.version, r.headers, r.body, remote, remotePort, local, localPort, secure);
            boolean head = r.method.equals("HEAD");
            CompletableFuture<HttpResponse> f;
            try {
                f = handler.handle(req);
            } catch (Throwable t) {
                LOG.error("游戏内网站处理请求 {} {} 时出错", r.method, r.target, t);
                respond(options.errorPages.page(500), head);
                return;
            }
            if (f == null) {
                respond(options.errorPages.page(500), head);
                return;
            }
            timer = loop.schedule(() -> respond(options.errorPages.page(503), head), options.handlerTimeoutMillis);
            f.whenComplete((resp, err) -> loop.execute(() -> {
                if (err != null || resp == null) {
                    Throwable c = err instanceof CompletionException && err.getCause() != null ? err.getCause() : err;
                    if (c != null) {
                        LOG.error("游戏内网站处理请求 {} {} 时出错", r.method, r.target, c);
                    }
                    respond(options.errorPages.page(500), head);
                } else {
                    respond(resp, head);
                }
            }));
        }

        void onEnd() {
            if (!done) {
                // 对方没发完请求就结束了
                done = true;
                cancelTimer();
                out.close();
            }
        }

        void onClosed() {
            closed = true;
            cancelTimer();
        }

        private void fail(int status) {
            done = true;
            cancelTimer();
            respond(options.errorPages.page(status), false);
        }

        private boolean responded;

        private void respond(HttpResponse resp, boolean head) {
            if (responded) {
                return;
            }
            responded = true;
            cancelTimer();
            if (closed) {
                return;
            }
            out.write(resp.encode(head, ZonedDateTime.now(ZoneOffset.UTC)));
            out.close();
        }

        private void cancelTimer() {
            if (timer != null) {
                timer.cancel();
                timer = null;
            }
        }
    }

    /** 测试用：把一段原始请求按服务器的规则解析，返回状态码（0 为合格）与请求 */
    static HttpRequestParser.Result parseForTest(String raw, Options o) {
        byte[] b = raw.getBytes(StandardCharsets.ISO_8859_1);
        return HttpRequestParser.parse(b, b.length, o.maxHeaderBytes, o.maxBodyBytes);
    }
}
