package moe.qingu.youran.api.network.stack;

import moe.qingu.youran.api.network.Ipv6Address;
import moe.qingu.youran.api.network.packet.DnsMessage;
import moe.qingu.youran.api.network.packet.MalformedPacketException;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.CompletableFuture;

/**
 * 设备上的 DNS 客户端（“存根解析器”）：只问一个递归解析器（服务器内网里是 YouranMod 机房的递归解析器），
 * 设 RD 位，UDP 53，等 {@value #TIMEOUT_MS} 毫秒，最多问 {@value #ATTEMPTS} 次。不带缓存（缓存在递归解析器那里）。
 * <p>
 * 查询带 EDNS(0)（RFC 6891，载荷 {@value DnsMessage#EDNS_PAYLOAD}）。每个查询开一个新的 UDP 套接字（随机的临时端口，RFC 5452）、
 * 随机的标识符；只认从那台服务器 53 口来的、标识符和问题都对得上的应答。应答置了 TC（截断）时不用它，改用 TCP 重问同一台服务器
 * （RFC 7766，{@link DnsTcp}），整个 TCP 查询等 {@value #TCP_TIMEOUT_MS} 毫秒。
 */
public final class DnsResolver {
    static final long TIMEOUT_MS = 2000;
    static final int ATTEMPTS = 3;
    static final long TCP_TIMEOUT_MS = 10_000;

    private final NetStack stack;
    /** 从哪个网口问；null 为按源地址选择规则（默认网口） */
    private final NetStack.Interface via;
    private final Ipv6Address server;
    /** 截断后改用 TCP 的次数；最近一次回答是不是 TCP 拿到的（给 /ynet 显示） */
    private volatile long tcpFallbacks;
    private volatile boolean lastOverTcp;

    public DnsResolver(NetStack stack, Ipv6Address server) {
        this(stack, null, server);
    }

    /** 从网口 {@code via} 问（网口去掉时还没回来的查询报错）；{@code via} 为 null 同 {@link #DnsResolver(NetStack, Ipv6Address)} */
    public DnsResolver(NetStack stack, NetStack.Interface via, Ipv6Address server) {
        this.stack = stack;
        this.via = via;
        this.server = server;
    }

    public Ipv6Address server() {
        return server;
    }

    /** 收到截断的应答、改用 TCP 重问过几次 */
    public long tcpFallbacks() {
        return tcpFallbacks;
    }

    /** 最近完成的那个查询是不是经 TCP 拿到的回答 */
    public boolean lastOverTcp() {
        return lastOverTcp;
    }

    /** 查询结果 */
    public static final class Result {
        /** DNS 响应码（0 成功，3 名字不存在……） */
        public final int rcode;
        public final List<Ipv6Address> addresses;
        public final DnsMessage response;

        Result(int rcode, List<Ipv6Address> addresses, DnsMessage response) {
            this.rcode = rcode;
            this.addresses = Collections.unmodifiableList(addresses);
            this.response = response;
        }
    }

    /**
     * 查 AAAA。名字本身就是 IPv6 地址时直接返回它。回答里的 CNAME 链由递归解析器展开，这里只收 AAAA。
     * 等不到回应时以 {@link NetworkException}（TIMEOUT）结束；名字不存在时正常结束，{@link Result#rcode} 为 3。
     */
    public CompletableFuture<Result> resolveAaaa(String name) {
        Ipv6Address literal = Ipv6Address.tryParse(name);
        if (literal != null) {
            return CompletableFuture.completedFuture(new Result(DnsMessage.RCODE_OK, Collections.singletonList(literal), null));
        }
        return query(name, DnsMessage.TYPE_AAAA).thenApply(m -> {
            List<Ipv6Address> list = new ArrayList<>();
            for (DnsMessage.Record r : m.answers) {
                if (r.type == DnsMessage.TYPE_AAAA && r.address != null) {
                    list.add(r.address);
                }
            }
            return new Result(m.rcode, list, m);
        });
    }

    /** 发一个查询，返回原样的应答 */
    public CompletableFuture<DnsMessage> query(String name, int type) {
        CompletableFuture<DnsMessage> f = new CompletableFuture<>();
        if (!moe.qingu.youran.api.network.DomainNames.isAscii(name)) {
            // 国际化域名：报文里只用 A-label
            try {
                return query(moe.qingu.youran.api.network.DomainNames.toAscii(name), type);
            } catch (IllegalArgumentException e) {
                f.completeExceptionally(new NetworkException(NetworkException.Reason.LIMIT, e.getMessage()));
                return f;
            }
        }
        if (!DnsMessage.isValidName(name)) {
            f.completeExceptionally(new NetworkException(NetworkException.Reason.LIMIT, "域名不合法：" + name));
            return f;
        }
        if (server == null) {
            // 网口还没拿到 DNS 服务器（玩家网口的 DHCPv6 没走完）
            f.completeExceptionally(new NetworkException(NetworkException.Reason.CLOSED, "还没有 DNS 服务器"));
            return f;
        }
        int id = stack.random.nextInt(0x10000);
        DnsMessage q = DnsMessage.query(id, name, type, true).withEdns(DnsMessage.Edns.standard());
        byte[] bytes = q.encode();
        UdpSocket.Handler handler = new UdpSocket.Handler() {
            @Override
            public void onDatagram(UdpSocket socket, Ipv6Address from, int fromPort, Ipv6Address to, byte[] data) {
                if (!from.equals(server) || fromPort != DnsMessage.PORT) {
                    return;
                }
                DnsMessage m;
                try {
                    m = DnsMessage.parse(data);
                } catch (MalformedPacketException e) {
                    return;
                }
                if (!matches(m, id, name, type)) {
                    return;
                }
                socket.close();
                if (m.truncated) {
                    // 截断的不用：改用 TCP 问同一台服务器
                    tcpFallbacks++;
                    overTcp(bytes, id, name, type, f);
                    return;
                }
                lastOverTcp = false;
                f.complete(m);
            }

            @Override
            public void onClosed(UdpSocket socket, NetworkException reason) {
                f.completeExceptionally(reason);
            }
        };
        (via != null ? stack.openUdpVia(via, 0, handler) : stack.openUdp(null, 0, handler)).whenComplete((socket, err) -> {
            if (err != null) {
                f.completeExceptionally(err);
                return;
            }
            attempt(socket, bytes, f, 1);
        });
        return f;
    }

    static boolean matches(DnsMessage m, int id, String name, int type) {
        return m.id == id && m.response && m.questions.size() == 1
                && DnsMessage.canonical(m.question().name).equals(DnsMessage.canonical(name)) && m.question().type == type;
    }

    private void overTcp(byte[] query, int id, String name, int type, CompletableFuture<DnsMessage> f) {
        DnsTcp.query(stack, via, server, query, TCP_TIMEOUT_MS).whenComplete((data, err) -> {
            if (err != null) {
                f.completeExceptionally(err);
                return;
            }
            DnsMessage m;
            try {
                m = DnsMessage.parse(data);
            } catch (MalformedPacketException e) {
                f.completeExceptionally(new NetworkException(NetworkException.Reason.CLOSED, "DNS 服务器 " + server + " 的 TCP 应答不对"));
                return;
            }
            if (!matches(m, id, name, type)) {
                f.completeExceptionally(new NetworkException(NetworkException.Reason.CLOSED, "DNS 服务器 " + server + " 的 TCP 应答对不上"));
                return;
            }
            lastOverTcp = true;
            f.complete(m);
        });
    }

    private void attempt(UdpSocket socket, byte[] bytes, CompletableFuture<DnsMessage> f, int n) {
        if (f.isDone()) {
            return;
        }
        socket.send(server, DnsMessage.PORT, bytes);
        stack.loop.schedule(() -> {
            if (f.isDone() || socket.isClosed()) {
                return;
            }
            if (n >= ATTEMPTS) {
                socket.close();
                f.completeExceptionally(new NetworkException(NetworkException.Reason.TIMEOUT, "DNS 服务器 " + server + " 没有回应"));
            } else {
                attempt(socket, bytes, f, n + 1);
            }
        }, TIMEOUT_MS);
    }
}
