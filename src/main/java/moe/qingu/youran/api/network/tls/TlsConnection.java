package moe.qingu.youran.api.network.tls;

import moe.qingu.youran.api.network.Ipv6Address;
import moe.qingu.youran.api.network.stack.EventLoop;
import moe.qingu.youran.api.network.stack.NetStack;
import moe.qingu.youran.api.network.stack.NetworkException;
import moe.qingu.youran.api.network.stack.TcpConnection;

import javax.net.ssl.SNIHostName;
import javax.net.ssl.SNIServerName;
import javax.net.ssl.SSLContext;
import javax.net.ssl.SSLEngine;
import javax.net.ssl.SSLEngineResult;
import javax.net.ssl.SSLException;
import javax.net.ssl.SSLHandshakeException;
import javax.net.ssl.SSLParameters;
import javax.net.ssl.SSLPeerUnverifiedException;
import javax.net.ssl.SSLSession;
import javax.net.ssl.TrustManager;
import javax.net.ssl.X509ExtendedTrustManager;
import java.net.Socket;
import java.nio.ByteBuffer;
import java.security.SecureRandom;
import java.security.cert.Certificate;
import java.security.cert.CertificateException;
import java.security.cert.X509Certificate;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.Date;
import java.util.List;
import java.util.concurrent.CompletableFuture;

/**
 * 跑在游戏内 TCP（{@link TcpConnection}）上的 TLS：用 Java 自带的 {@link SSLEngine}，至少 TLS 1.2，Java 支持 TLS 1.3 时优先用 1.3。
 * <ul>
 * <li>客户端（{@link #connect}）：只信任给定的根（默认 QinguMoe CA，见 {@link TlsTrust}），校验证书链与主机名；
 * 不对时握手失败，future 以 {@link NetworkException.Reason#CERTIFICATE} 结束、带上具体问题，没有“继续访问”。</li>
 * <li>服务端（{@link #accept}）：用 {@link TlsServerContext} 的引擎（证书按 SNI 现签）。</li>
 * </ul>
 * 和 {@link TcpConnection} 一样：方法可以从任意线程调用，回调都在协议栈的事件循环线程上（握手里的计算也在那里做）。
 */
public final class TlsConnection {
    /** 用哪些协议版本（按 Java 支持的取交集） */
    static final List<String> PROTOCOLS = Arrays.asList("TLSv1.3", "TLSv1.2");
    /** 测试用：只用这些协议版本（模拟只有 TLS 1.2 的旧 Java）；null 为不限 */
    static volatile List<String> testOnlyProtocols;
    /** 收到还没解开的密文最多这么多（远大于一条 TLS 记录） */
    static final int MAX_NET_BUFFER = 256 * 1024;

    public interface Listener {
        /** 握手完成 */
        default void onOpen(TlsConnection connection) {
        }

        /** 解密后的数据（按顺序） */
        void onData(TlsConnection connection, byte[] data);

        /** 对方发完了（收到 close_notify 或 TCP FIN） */
        default void onEnd(TlsConnection connection) {
        }

        /** 连接结束：正常关闭为 null，否则是原因。只调用一次 */
        default void onClosed(TlsConnection connection, NetworkException error) {
        }
    }

    private static final ByteBuffer EMPTY = ByteBuffer.allocate(0);

    private final EventLoop loop;
    private final SSLEngine engine;
    private final String host;
    private final CheckingTrustManager checker;
    private TcpConnection tcp;
    private Listener listener;
    private CompletableFuture<TlsConnection> openFuture;

    private ByteBuffer netIn;
    private ByteBuffer appIn;
    private ByteBuffer netOut;
    private final ArrayDeque<byte[]> pendingWrites = new ArrayDeque<>();
    private boolean open;
    private boolean closed;
    private boolean closeRequested;
    private boolean endDelivered;
    private TlsInfo info;

    private TlsConnection(EventLoop loop, SSLEngine engine, String host, CheckingTrustManager checker, Listener listener) {
        this.loop = loop;
        this.engine = engine;
        this.host = host;
        this.checker = checker;
        this.listener = listener;
        SSLSession s = engine.getSession();
        this.netIn = ByteBuffer.allocate(s.getPacketBufferSize());
        this.appIn = ByteBuffer.allocate(s.getApplicationBufferSize());
        this.netOut = ByteBuffer.allocate(s.getPacketBufferSize());
    }

    // ---- 建立 ----

    /**
     * 客户端：连到 {@code address}:{@code port}，按 {@code host}（访问时用的域名，或 IPv6 地址）握手、校验证书。
     * 握手完成时 future 完成；失败时以 {@link NetworkException} 结束（证书问题是 {@link NetworkException.Reason#CERTIFICATE}）。
     */
    public static CompletableFuture<TlsConnection> connect(NetStack stack, Ipv6Address address, int port, String host, TlsTrust trust, Listener listener) {
        CompletableFuture<TlsConnection> f = new CompletableFuture<>();
        TlsConnection c;
        try {
            CheckingTrustManager tm = new CheckingTrustManager(trust, host);
            SSLContext ctx = SSLContext.getInstance("TLS");
            ctx.init(null, new TrustManager[]{tm}, new SecureRandom());
            boolean ip = Ipv6Address.tryParse(host) != null;
            SSLEngine e = ip ? ctx.createSSLEngine() : ctx.createSSLEngine(host, port);
            e.setUseClientMode(true);
            SSLParameters p = e.getSSLParameters();
            p.setProtocols(protocols(e.getSupportedProtocols()));
            if (!ip) {
                try {
                    p.setServerNames(Collections.<SNIServerName>singletonList(new SNIHostName(host)));
                } catch (IllegalArgumentException ignored) {
                    // 不是合法的 SNI 名字时不带 SNI，主机名照样核对
                }
            }
            e.setSSLParameters(p);
            c = new TlsConnection(stack.loop(), e, host, tm, listener);
        } catch (Exception e) {
            f.completeExceptionally(new NetworkException(NetworkException.Reason.TLS, "TLS 初始化失败：" + e));
            return f;
        }
        c.openFuture = f;
        stack.connect(null, address, port, c.new TcpSide()).whenComplete((tcp, err) -> {
            if (err != null) {
                f.completeExceptionally(err);
                return;
            }
            c.tcp = tcp;
            c.start();
        });
        return f;
    }

    /** 服务端：在刚接受的 TCP 连接上做 TLS（握手完成时调 {@link Listener#onOpen}） */
    public static TlsConnection accept(TcpConnection tcp, TlsServerContext context, Listener listener) {
        SSLEngine e = context.newEngine();
        e.setUseClientMode(false);
        TlsConnection c = new TlsConnection(tcp.loop(), e, null, null, listener);
        c.tcp = tcp;
        c.openFuture = new CompletableFuture<>();
        tcp.setListener(c.new TcpSide());
        c.runOnLoop(c::start);
        return c;
    }

    static String[] protocols(String[] supported) {
        List<String> s = Arrays.asList(supported);
        List<String> out = new ArrayList<>();
        for (String p : PROTOCOLS) {
            if (s.contains(p) && (testOnlyProtocols == null || testOnlyProtocols.contains(p))) {
                out.add(p);
            }
        }
        if (out.isEmpty()) {
            throw new IllegalStateException("这个 Java 不支持 TLS 1.2 以上");
        }
        return out.toArray(new String[0]);
    }

    // ---- 公开操作 ----

    public void setListener(Listener l) {
        runOnLoop(() -> listener = l);
    }

    /** 握手完成后的连接信息；还没完成为 null */
    public TlsInfo info() {
        return info;
    }

    public Ipv6Address remoteAddress() {
        return tcp == null ? null : tcp.remoteAddress();
    }

    public int remotePort() {
        return tcp == null ? 0 : tcp.remotePort();
    }

    public boolean isOpen() {
        return open && !closed;
    }

    /** 发数据（握手还没完时先存着） */
    public void write(byte[] data) {
        byte[] copy = data.clone();
        runOnLoop(() -> {
            if (closed || closeRequested) {
                return;
            }
            pendingWrites.add(copy);
            if (open) {
                pump();
            }
        });
    }

    /** 发完已写的数据后发 close_notify，再关 TCP */
    public void close() {
        runOnLoop(() -> {
            if (closed || closeRequested) {
                return;
            }
            closeRequested = true;
            if (!open) {
                abortWith(new NetworkException(NetworkException.Reason.CLOSED, "TLS 连接未建立就关闭了"));
                return;
            }
            pump();
            engine.closeOutbound();
            pump();
        });
    }

    /** 立即中止（TCP 发 RST） */
    public void abort() {
        runOnLoop(() -> abortWith(null));
    }

    private void runOnLoop(Runnable r) {
        if (loop.inEventLoop()) {
            r.run();
        } else {
            loop.execute(r);
        }
    }

    // ---- 内部 ----

    private void start() {
        try {
            engine.beginHandshake();
        } catch (SSLException e) {
            fail(map(e));
            return;
        }
        pump();
    }

    private void onTcpData(byte[] data) {
        if (closed) {
            return;
        }
        if (netIn.remaining() < data.length) {
            int need = netIn.position() + data.length;
            if (need > MAX_NET_BUFFER) {
                fail(new NetworkException(NetworkException.Reason.TLS, "TLS 记录太大"));
                return;
            }
            ByteBuffer b = ByteBuffer.allocate(Math.max(need, netIn.capacity() * 2));
            netIn.flip();
            b.put(netIn);
            netIn = b;
        }
        netIn.put(data);
        pump();
    }

    /** 把引擎能做的都做完：跑计算、发握手数据、解开收到的记录、发应用数据 */
    private void pump() {
        try {
            for (int guard = 0; guard < 10_000 && !closed; guard++) {
                SSLEngineResult.HandshakeStatus hs = engine.getHandshakeStatus();
                if (hs == SSLEngineResult.HandshakeStatus.NEED_TASK) {
                    Runnable t;
                    while ((t = engine.getDelegatedTask()) != null) {
                        t.run();
                    }
                    continue;
                }
                if (hs == SSLEngineResult.HandshakeStatus.NEED_WRAP) {
                    if (wrap(EMPTY)) {
                        continue;
                    }
                    break;
                }
                boolean progress = false;
                if (netIn.position() > 0 && !engine.isInboundDone()) {
                    progress = unwrap();
                }
                if (closed) {
                    return;
                }
                if (open && !pendingWrites.isEmpty() && !engine.isOutboundDone()) {
                    byte[] d = pendingWrites.poll();
                    ByteBuffer src = ByteBuffer.wrap(d);
                    while (src.hasRemaining() && !closed) {
                        if (!wrap(src)) {
                            break;
                        }
                    }
                    progress = true;
                }
                if (!progress) {
                    break;
                }
            }
            if (!closed && engine.isOutboundDone() && closeRequested) {
                tcp.close();
            }
        } catch (SSLException e) {
            fail(map(e));
        } catch (RuntimeException e) {
            fail(new NetworkException(NetworkException.Reason.TLS, "TLS 出错：" + e));
        }
    }

    private boolean unwrap() throws SSLException {
        netIn.flip();
        SSLEngineResult r;
        try {
            r = engine.unwrap(netIn, appIn);
        } finally {
            netIn.compact();
        }
        switch (r.getStatus()) {
            case BUFFER_OVERFLOW: {
                ByteBuffer b = ByteBuffer.allocate(appIn.position() + engine.getSession().getApplicationBufferSize());
                appIn.flip();
                b.put(appIn);
                appIn = b;
                return true;
            }
            case BUFFER_UNDERFLOW:
                if (netIn.position() == netIn.capacity()) {
                    int size = engine.getSession().getPacketBufferSize();
                    if (netIn.capacity() < size) {
                        ByteBuffer b = ByteBuffer.allocate(size);
                        netIn.flip();
                        b.put(netIn);
                        netIn = b;
                        return true;
                    }
                }
                return false;
            default:
                break;
        }
        deliverApp();
        if (r.getHandshakeStatus() == SSLEngineResult.HandshakeStatus.FINISHED) {
            handshakeDone();
        }
        if (r.getStatus() == SSLEngineResult.Status.CLOSED) {
            // 收到 close_notify：对方发完了
            deliverEnd();
            return engine.getHandshakeStatus() == SSLEngineResult.HandshakeStatus.NEED_WRAP;
        }
        return r.bytesConsumed() > 0 || r.bytesProduced() > 0;
    }

    private boolean wrap(ByteBuffer src) throws SSLException {
        while (true) {
            netOut.clear();
            SSLEngineResult r = engine.wrap(src, netOut);
            if (r.getStatus() == SSLEngineResult.Status.BUFFER_OVERFLOW) {
                netOut = ByteBuffer.allocate(netOut.capacity() * 2);
                continue;
            }
            netOut.flip();
            if (netOut.hasRemaining()) {
                byte[] out = new byte[netOut.remaining()];
                netOut.get(out);
                tcp.write(out);
            }
            if (r.getHandshakeStatus() == SSLEngineResult.HandshakeStatus.FINISHED) {
                handshakeDone();
            }
            return r.bytesProduced() > 0 || r.bytesConsumed() > 0;
        }
    }

    private void deliverApp() {
        if (appIn.position() == 0) {
            return;
        }
        appIn.flip();
        byte[] d = new byte[appIn.remaining()];
        appIn.get(d);
        appIn.clear();
        if (listener != null) {
            listener.onData(this, d);
        }
    }

    private void handshakeDone() {
        if (open) {
            return;
        }
        open = true;
        SSLSession s = engine.getSession();
        List<X509Certificate> chain = new ArrayList<>();
        if (engine.getUseClientMode()) {
            try {
                for (Certificate c : s.getPeerCertificates()) {
                    if (c instanceof X509Certificate) {
                        chain.add((X509Certificate) c);
                    }
                }
            } catch (SSLPeerUnverifiedException ignored) {
                // 不会发生：信任检查过了才到这里
            }
        }
        info = new TlsInfo(s.getProtocol(), s.getCipherSuite(), tcp.remoteAddress(), tcp.remotePort(),
                host == null ? sniOf(s) : host, chain);
        if (listener != null) {
            listener.onOpen(this);
        }
        openFuture.complete(this);
    }

    private static String sniOf(SSLSession s) {
        if (s instanceof javax.net.ssl.ExtendedSSLSession) {
            for (SNIServerName n : ((javax.net.ssl.ExtendedSSLSession) s).getRequestedServerNames()) {
                if (n instanceof SNIHostName) {
                    return ((SNIHostName) n).getAsciiName();
                }
            }
        }
        return null;
    }

    private void deliverEnd() {
        if (endDelivered) {
            return;
        }
        endDelivered = true;
        if (listener != null) {
            listener.onEnd(this);
        }
    }

    private NetworkException map(SSLException e) {
        CertificateProblem p = checker == null ? null : checker.problem;
        if (p != null) {
            return new NetworkException(NetworkException.Reason.CERTIFICATE, p.description, p);
        }
        String what = e instanceof SSLHandshakeException ? "TLS 握手失败：" : "TLS 出错：";
        return new NetworkException(NetworkException.Reason.TLS, what + e.getMessage());
    }

    /** 失败：尽量把引擎生成的警报发出去，再关 TCP */
    private void fail(NetworkException error) {
        if (closed) {
            return;
        }
        try {
            engine.closeOutbound();
            for (int i = 0; i < 4 && engine.getHandshakeStatus() == SSLEngineResult.HandshakeStatus.NEED_WRAP; i++) {
                if (!wrap(EMPTY)) {
                    break;
                }
            }
        } catch (Exception ignored) {
            // 警报发不出去也没关系
        }
        if (tcp != null) {
            tcp.close();
        }
        finish(error);
    }

    private void abortWith(NetworkException error) {
        if (closed) {
            return;
        }
        if (tcp != null) {
            tcp.abort();
        }
        finish(error);
    }

    private void finish(NetworkException error) {
        if (closed) {
            return;
        }
        closed = true;
        pendingWrites.clear();
        if (!openFuture.isDone()) {
            openFuture.completeExceptionally(error != null ? error
                    : new NetworkException(NetworkException.Reason.TLS, "TLS 握手没完成连接就断了"));
        }
        if (listener != null) {
            listener.onClosed(this, error);
        }
    }

    /** 下面的 TCP 连接的回调 */
    private final class TcpSide implements TcpConnection.Listener {
        @Override
        public void onData(TcpConnection connection, byte[] data) {
            onTcpData(data);
        }

        @Override
        public void onEnd(TcpConnection connection) {
            if (closed) {
                return;
            }
            if (!open) {
                fail(new NetworkException(NetworkException.Reason.TLS, "TLS 握手时对方关闭了连接"));
                return;
            }
            try {
                engine.closeInbound();
            } catch (SSLException ignored) {
                // 没收到 close_notify 就断了（可能被截断）；HTTP 按长度判断完整性，这里只当对方发完
            }
            deliverEnd();
            if (closeRequested || engine.isOutboundDone()) {
                connection.close();
            }
        }

        @Override
        public void onClosed(TcpConnection connection, NetworkException error) {
            if (closed) {
                return;
            }
            if (error == null && !open) {
                error = new NetworkException(NetworkException.Reason.TLS, "TLS 握手没完成连接就断了");
            }
            finish(error);
        }
    }

    /** 客户端的证书检查：只信任给定的根，核对主机名；不通过时记下原因，握手失败 */
    static final class CheckingTrustManager extends X509ExtendedTrustManager {
        private final TlsTrust trust;
        private final String host;
        volatile CertificateProblem problem;

        CheckingTrustManager(TlsTrust trust, String host) {
            this.trust = trust;
            this.host = host;
        }

        private void check(X509Certificate[] chain) throws CertificateException {
            CertificateProblem p = trust.check(chain == null ? Collections.<X509Certificate>emptyList() : Arrays.asList(chain), host, new Date());
            if (p != null) {
                problem = p;
                throw new CertificateException(p.description);
            }
        }

        @Override
        public void checkServerTrusted(X509Certificate[] chain, String authType, SSLEngine engine) throws CertificateException {
            check(chain);
        }

        @Override
        public void checkServerTrusted(X509Certificate[] chain, String authType, Socket socket) throws CertificateException {
            check(chain);
        }

        @Override
        public void checkServerTrusted(X509Certificate[] chain, String authType) throws CertificateException {
            check(chain);
        }

        @Override
        public void checkClientTrusted(X509Certificate[] chain, String authType, SSLEngine engine) throws CertificateException {
            throw new CertificateException("不接受客户端证书");
        }

        @Override
        public void checkClientTrusted(X509Certificate[] chain, String authType, Socket socket) throws CertificateException {
            throw new CertificateException("不接受客户端证书");
        }

        @Override
        public void checkClientTrusted(X509Certificate[] chain, String authType) throws CertificateException {
            throw new CertificateException("不接受客户端证书");
        }

        @Override
        public X509Certificate[] getAcceptedIssuers() {
            return trust.roots().toArray(new X509Certificate[0]);
        }
    }
}
