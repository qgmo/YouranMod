package moe.qingu.youran.api.network.stack;

import moe.qingu.youran.api.network.Ipv6Address;
import moe.qingu.youran.api.network.packet.DnsMessage;

import java.io.ByteArrayOutputStream;
import java.util.concurrent.CompletableFuture;
import java.util.function.Function;

/**
 * DNS over TCP（RFC 7766；报文格式是 RFC 1035 第 4.2.2 节的两字节长度前缀）。权威服务器、递归解析器都在 TCP 53 上听
 * （{@link #serve}），存根解析器、递归解析器收到截断（TC = 1）的 UDP 应答时改用 TCP 重问同一台服务器（{@link #query}）。
 * <ul>
 * <li>服务端：一条连接上可以连续问（流水线也行，回答按算完的先后写回，第 6.2.1.1 节）；空闲 {@value #IDLE_TIMEOUT_MS} 毫秒关掉
 * （第 6.2.3 节）；对方发完（FIN）以后，没回完的回完再关。报文长度为 0 或连接上攒的数据超过 {@value #MAX_BUFFERED} 字节时中止连接。</li>
 * <li>客户端：连上、发一个查询、等标识符对得上的回答，然后关掉连接；{@code timeoutMillis} 内没有回答报超时。</li>
 * </ul>
 */
public final class DnsTcp {
    /** 服务端的空闲超时 */
    public static final long IDLE_TIMEOUT_MS = 10_000;
    /** 一条连接上还没处理完的数据最多这么多（一个最长的报文加长度前缀） */
    static final int MAX_BUFFERED = DnsMessage.MAX_TCP + 2;
    /** 一条连接上同时在算的查询最多几个 */
    static final int MAX_IN_FLIGHT = 16;

    private DnsTcp() {
    }

    /** 在 {@code stack} 的 TCP 53 上听：每个查询报文交给 {@code handler}，它给出的回答（null 为不回）加上长度前缀写回 */
    public static void serve(NetStack stack, Function<byte[], CompletableFuture<byte[]>> handler) {
        stack.listen(null, DnsMessage.PORT, c -> new ServerConn(c, handler).start());
    }

    private static final class ServerConn implements TcpConnection.Listener {
        final TcpConnection conn;
        final Function<byte[], CompletableFuture<byte[]>> handler;
        final Framer framer = new Framer();
        int inFlight;
        boolean ended;
        boolean closed;
        EventLoop.Timer idle;

        ServerConn(TcpConnection conn, Function<byte[], CompletableFuture<byte[]>> handler) {
            this.conn = conn;
            this.handler = handler;
        }

        void start() {
            conn.setListener(this);
            touch();
        }

        void touch() {
            if (idle != null) {
                idle.cancel();
            }
            idle = conn.loop().schedule(() -> {
                if (!closed && inFlight == 0) {
                    closed = true;
                    conn.close();
                }
            }, IDLE_TIMEOUT_MS);
        }

        @Override
        public void onData(TcpConnection c, byte[] data) {
            if (closed) {
                return;
            }
            touch();
            if (!framer.add(data)) {
                abort();
                return;
            }
            byte[] m;
            while ((m = framer.next()) != null) {
                if (m.length == 0 || inFlight >= MAX_IN_FLIGHT) {
                    abort();
                    return;
                }
                inFlight++;
                CompletableFuture<byte[]> f;
                try {
                    f = handler.apply(m);
                } catch (RuntimeException e) {
                    f = CompletableFuture.completedFuture(null);
                }
                f.whenComplete((reply, err) -> c.loop().execute(() -> answered(reply)));
            }
        }

        void answered(byte[] reply) {
            inFlight--;
            if (closed) {
                return;
            }
            if (reply != null && reply.length <= DnsMessage.MAX_TCP) {
                conn.write(DnsMessage.tcpFrame(reply));
            }
            touch();
            if (ended && inFlight == 0) {
                closed = true;
                conn.close();
            }
        }

        void abort() {
            closed = true;
            conn.abort();
        }

        @Override
        public void onEnd(TcpConnection c) {
            ended = true;
            if (inFlight == 0 && !closed) {
                closed = true;
                conn.close();
            }
        }

        @Override
        public void onClosed(TcpConnection c, NetworkException error) {
            closed = true;
            if (idle != null) {
                idle.cancel();
            }
        }
    }

    /** 两字节长度前缀的拆包 */
    static final class Framer {
        private final ByteArrayOutputStream buf = new ByteArrayOutputStream();

        /** 攒进来；超过上限返回 false */
        boolean add(byte[] data) {
            if (buf.size() + data.length > MAX_BUFFERED * 2) {
                return false;
            }
            buf.write(data, 0, data.length);
            return true;
        }

        /** 下一个完整的报文；还不完整为 null */
        byte[] next() {
            byte[] b = buf.toByteArray();
            if (b.length < 2) {
                return null;
            }
            int len = ((b[0] & 0xFF) << 8) | (b[1] & 0xFF);
            if (b.length < 2 + len) {
                return null;
            }
            byte[] m = java.util.Arrays.copyOfRange(b, 2, 2 + len);
            buf.reset();
            buf.write(b, 2 + len, b.length - 2 - len);
            return m;
        }
    }

    /**
     * 经 TCP 问 {@code server} 一次（从网口 {@code via}，null 为按源地址选择规则）：回答报文（不管 TC 位）；
     * 连不上、超时、连接断了以 {@link NetworkException} 结束
     */
    public static CompletableFuture<byte[]> query(NetStack stack, NetStack.Interface via, Ipv6Address server, byte[] query, long timeoutMillis) {
        CompletableFuture<byte[]> f = new CompletableFuture<>();
        int id = query.length >= 2 ? ((query[0] & 0xFF) << 8) | (query[1] & 0xFF) : -1;
        Framer framer = new Framer();
        TcpConnection.Listener listener = new TcpConnection.Listener() {
            @Override
            public void onData(TcpConnection c, byte[] data) {
                if (f.isDone()) {
                    return;
                }
                if (!framer.add(data)) {
                    c.abort();
                    f.completeExceptionally(new NetworkException(NetworkException.Reason.LIMIT, "DNS 应答太长"));
                    return;
                }
                byte[] m;
                while ((m = framer.next()) != null) {
                    if (m.length >= 2 && (((m[0] & 0xFF) << 8) | (m[1] & 0xFF)) == id) {
                        f.complete(m);
                        c.close();
                        return;
                    }
                }
            }

            @Override
            public void onClosed(TcpConnection c, NetworkException error) {
                f.completeExceptionally(error != null ? error
                        : new NetworkException(NetworkException.Reason.CLOSED, "DNS 服务器 " + server + " 没回答就关了 TCP 连接"));
            }
        };
        CompletableFuture<TcpConnection> c = via != null ? stack.connectVia(via, server, DnsMessage.PORT, listener)
                : stack.connect(null, server, DnsMessage.PORT, listener);
        c.whenComplete((conn, err) -> {
            if (err != null) {
                f.completeExceptionally(err instanceof java.util.concurrent.CompletionException && err.getCause() != null ? err.getCause() : err);
                return;
            }
            conn.write(DnsMessage.tcpFrame(query));
        });
        EventLoop.Timer t = stack.loop.schedule(() -> {
            if (!f.isDone()) {
                f.completeExceptionally(new NetworkException(NetworkException.Reason.TIMEOUT, "DNS 服务器 " + server + " 的 TCP 没有回答"));
                c.thenAccept(TcpConnection::abort);
            }
        }, timeoutMillis);
        f.whenComplete((v, e) -> t.cancel());
        return f;
    }
}
