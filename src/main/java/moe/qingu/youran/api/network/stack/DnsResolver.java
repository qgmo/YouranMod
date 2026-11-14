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
 */
public final class DnsResolver {
    static final long TIMEOUT_MS = 2000;
    static final int ATTEMPTS = 3;

    private final NetStack stack;
    private final Ipv6Address server;

    public DnsResolver(NetStack stack, Ipv6Address server) {
        this.stack = stack;
        this.server = server;
    }

    public Ipv6Address server() {
        return server;
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
        if (!DnsMessage.isValidName(name)) {
            f.completeExceptionally(new NetworkException(NetworkException.Reason.LIMIT, "域名不合法：" + name));
            return f;
        }
        int id = stack.random.nextInt(0x10000);
        DnsMessage q = DnsMessage.query(id, name, type, true);
        byte[] bytes = q.encode();
        stack.openUdp(null, 0, (socket, from, fromPort, to, data) -> {
            if (!from.equals(server) || fromPort != DnsMessage.PORT) {
                return;
            }
            DnsMessage m;
            try {
                m = DnsMessage.parse(data);
            } catch (MalformedPacketException e) {
                return;
            }
            if (m.id != id || !m.response || m.questions.size() != 1
                    || !DnsMessage.canonical(m.question().name).equals(DnsMessage.canonical(name)) || m.question().type != type) {
                return;
            }
            socket.close();
            f.complete(m);
        }).whenComplete((socket, err) -> {
            if (err != null) {
                f.completeExceptionally(err);
                return;
            }
            attempt(socket, bytes, f, 1);
        });
        return f;
    }

    private void attempt(UdpSocket socket, byte[] bytes, CompletableFuture<DnsMessage> f, int n) {
        if (f.isDone()) {
            return;
        }
        socket.send(server, DnsMessage.PORT, bytes);
        stack.loop.schedule(() -> {
            if (f.isDone()) {
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
