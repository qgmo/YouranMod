package moe.qingu.youran.server.net.service;

import moe.qingu.youran.api.network.Ipv6Address;
import moe.qingu.youran.api.network.packet.DnsMessage;
import moe.qingu.youran.api.network.packet.MalformedPacketException;
import moe.qingu.youran.api.network.stack.DnsTcp;
import moe.qingu.youran.api.network.stack.EventLoop;
import moe.qingu.youran.api.network.stack.NetStack;
import moe.qingu.youran.api.network.stack.UdpSocket;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;

/**
 * 递归解析器（相当于现实里的 8.8.8.8）：设备只问它（UDP 53 与 TCP 53，设 RD），它从根开始问：根 → 顶级域 → 权威服务器，
 * 跟着委派（用粘连地址；没有粘连地址时先去解析 NS 的名字）、展开 CNAME，带缓存（按 TTL，否定回答按 SOA 的 minimum）。
 * 以后接公网时，转发也在这里做。全部在网络线程上，异步。
 * <p>
 * 照标准做（服主 11-27）：
 * <ul>
 * <li>TCP 53（RFC 7766，{@link DnsTcp}）：两字节长度前缀，一条连接可以连续问；TCP 上的回答不截断。</li>
 * <li>EDNS(0)（RFC 6891）：设备的查询带 OPT 时回答也带；UDP 回答超过上限（没带 EDNS 时 512，带了时按它说的、最多 1232）时截断、置 TC。
 * 往外问时也带 OPT（载荷 1232）。</li>
 * <li>往外问的应答置了 TC 时不用、不缓存，改用 TCP 重问同一台服务器。</li>
 * <li>往外问时每个查询开一个新的 UDP 套接字，源端口随机（RFC 5452），标识符也随机；只认从那台服务器 53 口、发到这个端口、标识符与问题都对得上的应答。</li>
 * </ul>
 */
public final class RecursiveResolver implements UdpSocket.Handler {
    static final long QUERY_TIMEOUT_MS = 1500;
    static final long TCP_TIMEOUT_MS = 5000;
    static final int TRIES_PER_SERVER = 2;
    static final int MAX_REFERRALS = 16;
    static final int MAX_DEPTH = 8;
    static final int MAX_CACHE = 10000;
    static final int MAX_IN_FLIGHT = 512;

    private final NetStack stack;
    private final EventLoop loop;
    private final List<Ipv6Address> rootServers;
    private final Map<String, CacheEntry> cache = new HashMap<>();
    /** 区 → 它的权威服务器地址 */
    private final Map<String, ServerSet> zoneServers = new HashMap<>();
    private final Map<String, CompletableFuture<Answer>> inFlight = new HashMap<>();
    /** 往外问、还没回来的（统计与上限用） */
    private int pendingQueries;
    private final java.util.Random random;
    private UdpSocket socket;

    // 统计
    public long queries;
    public long cacheHits;
    /** 往外问时收到截断、改用 TCP 的次数 */
    public long tcpRetries;
    /** 测试用：往外问用过的源端口（最近的 64 个） */
    final java.util.ArrayDeque<Integer> recentPorts = new java.util.ArrayDeque<>();

    public RecursiveResolver(NetStack stack, List<Ipv6Address> rootServers) {
        this(stack, rootServers, new java.security.SecureRandom());
    }

    /** {@code random} 给标识符用（源端口由协议栈的随机数分）；单元测试用固定种子 */
    public RecursiveResolver(NetStack stack, List<Ipv6Address> rootServers, java.util.Random random) {
        this.stack = stack;
        this.loop = stack.loop();
        this.rootServers = Collections.unmodifiableList(new ArrayList<>(rootServers));
        this.random = random;
    }

    public void start() {
        stack.openUdp(null, DnsMessage.PORT, this).thenAccept(s -> socket = s);
        DnsTcp.serve(stack, this::onTcpQuery);
    }

    /** 解析结果 */
    static final class Answer {
        final int rcode;
        final List<DnsMessage.Record> records;

        Answer(int rcode, List<DnsMessage.Record> records) {
            this.rcode = rcode;
            this.records = records;
        }
    }

    private static final class CacheEntry {
        final Answer answer;
        final long expires;

        CacheEntry(Answer answer, long expires) {
            this.answer = answer;
            this.expires = expires;
        }
    }

    private static final class ServerSet {
        final List<Ipv6Address> servers;
        final long expires;

        ServerSet(List<Ipv6Address> servers, long expires) {
            this.servers = servers;
            this.expires = expires;
        }
    }

    // ---- 设备发来的查询 ----

    @Override
    public void onDatagram(UdpSocket s, Ipv6Address from, int fromPort, Ipv6Address to, byte[] data) {
        DnsMessage q;
        try {
            q = DnsMessage.parse(data);
        } catch (MalformedPacketException e) {
            return;
        }
        if (q.response) {
            return;
        }
        int limit = DnsMessage.udpLimitFor(q);
        answer(q).thenAccept(r -> s.send(to, from, fromPort, r.encodeForUdp(limit)));
    }

    /** TCP 53 上的一个查询：回答不截断 */
    private CompletableFuture<byte[]> onTcpQuery(byte[] data) {
        DnsMessage q;
        try {
            q = DnsMessage.parse(data);
        } catch (MalformedPacketException e) {
            return CompletableFuture.completedFuture(null);
        }
        if (q.response) {
            return CompletableFuture.completedFuture(null);
        }
        return answer(q).thenApply(DnsMessage::encode);
    }

    /** 回答设备的一个查询（带不带 OPT 照查询） */
    private CompletableFuture<DnsMessage> answer(DnsMessage q) {
        queries++;
        List<DnsMessage.Record> none = Collections.emptyList();
        if (q.ednsMalformed) {
            return CompletableFuture.completedFuture(DnsMessage.reply(q, false, true, DnsMessage.RCODE_FORMERR, none, none, none));
        }
        if (q.edns != null && q.edns.version != 0) {
            return CompletableFuture.completedFuture(DnsMessage.reply(q, false, true, DnsMessage.RCODE_OK, none, none, none)
                    .withEdns(DnsMessage.Edns.standard()).withRcode(DnsMessage.RCODE_BADVERS));
        }
        DnsMessage.Edns edns = q.edns == null ? null : DnsMessage.Edns.standard();
        if (q.opcode != 0) {
            return CompletableFuture.completedFuture(DnsMessage.reply(q, false, true, DnsMessage.RCODE_NOTIMP, none, none, none).withEdns(edns));
        }
        if (q.questions.size() != 1 || q.question().qclass != DnsMessage.CLASS_IN) {
            return CompletableFuture.completedFuture(DnsMessage.reply(q, false, true, DnsMessage.RCODE_FORMERR, none, none, none).withEdns(edns));
        }
        DnsMessage.Question question = q.question();
        CompletableFuture<DnsMessage> out = new CompletableFuture<>();
        resolve(DnsMessage.canonical(question.name), question.type, 0).whenComplete((a, err) -> {
            DnsMessage r;
            if (err != null || a == null) {
                r = DnsMessage.reply(q, false, true, DnsMessage.RCODE_SERVFAIL, none, none, none);
            } else {
                r = DnsMessage.reply(q, false, true, a.rcode, a.records, none, none);
            }
            out.complete(r.withEdns(edns));
        });
        return out;
    }

    // ---- 解析 ----

    CompletableFuture<Answer> resolve(String name, int type, int depth) {
        String key = type + " " + name;
        long now = loop.nowMillis();
        CacheEntry c = cache.get(key);
        if (c != null) {
            if (c.expires > now) {
                cacheHits++;
                return CompletableFuture.completedFuture(withRemainingTtl(c, now));
            }
            cache.remove(key);
        }
        // 同一个问题同时只问一遍（只合并设备发来的；内部的递归不合并，免得 CNAME 绕圈时互相等）
        if (depth == 0) {
            CompletableFuture<Answer> existing = inFlight.get(key);
            if (existing != null) {
                return existing;
            }
        }
        if (depth > MAX_DEPTH || inFlight.size() >= MAX_IN_FLIGHT) {
            return CompletableFuture.completedFuture(new Answer(DnsMessage.RCODE_SERVFAIL, Collections.<DnsMessage.Record>emptyList()));
        }
        CompletableFuture<Answer> f = new CompletableFuture<>();
        if (depth == 0) {
            inFlight.put(key, f);
            f.whenComplete((a, e) -> inFlight.remove(key, f));
        }
        iterate(name, type, depth, closestZone(name), 0, f);
        return f;
    }

    private static Answer withRemainingTtl(CacheEntry c, long now) {
        long left = Math.max(0, (c.expires - now) / 1000);
        List<DnsMessage.Record> list = new ArrayList<>();
        for (DnsMessage.Record r : c.answer.records) {
            list.add(r.withTtl(Math.min(r.ttl, left)));
        }
        return new Answer(c.answer.rcode, list);
    }

    /** 缓存里离 {@code name} 最近的、有服务器地址的区；都没有就是根 */
    private String closestZone(String name) {
        long now = loop.nowMillis();
        String n = name;
        while (true) {
            ServerSet s = zoneServers.get(n);
            if (s != null && s.expires > now && !s.servers.isEmpty()) {
                return n;
            }
            if (n.isEmpty()) {
                return "";
            }
            int dot = n.indexOf('.');
            n = dot < 0 ? "" : n.substring(dot + 1);
        }
    }

    private List<Ipv6Address> serversFor(String zone) {
        if (zone.isEmpty()) {
            return rootServers;
        }
        ServerSet s = zoneServers.get(zone);
        return s == null ? Collections.<Ipv6Address>emptyList() : s.servers;
    }

    private void iterate(String name, int type, int depth, String zone, int step, CompletableFuture<Answer> f) {
        if (step > MAX_REFERRALS) {
            finish(f, name, type, new Answer(DnsMessage.RCODE_SERVFAIL, Collections.<DnsMessage.Record>emptyList()), 0);
            return;
        }
        List<Ipv6Address> servers = serversFor(zone);
        if (servers.isEmpty()) {
            finish(f, name, type, new Answer(DnsMessage.RCODE_SERVFAIL, Collections.<DnsMessage.Record>emptyList()), 0);
            return;
        }
        ask(servers, 0, 0, name, type).whenComplete((m, err) -> {
            if (err != null || m == null) {
                finish(f, name, type, new Answer(DnsMessage.RCODE_SERVFAIL, Collections.<DnsMessage.Record>emptyList()), 0);
                return;
            }
            onResponse(name, type, depth, zone, step, m, f);
        });
    }

    private void onResponse(String name, int type, int depth, String zone, int step, DnsMessage m, CompletableFuture<Answer> f) {
        if (m.rcode == DnsMessage.RCODE_NXDOMAIN) {
            finish(f, name, type, new Answer(DnsMessage.RCODE_NXDOMAIN, Collections.<DnsMessage.Record>emptyList()), negativeTtl(m));
            return;
        }
        if (m.rcode != DnsMessage.RCODE_OK) {
            finish(f, name, type, new Answer(DnsMessage.RCODE_SERVFAIL, Collections.<DnsMessage.Record>emptyList()), 0);
            return;
        }
        // 回答：要的类型，或者 CNAME
        List<DnsMessage.Record> direct = new ArrayList<>();
        DnsMessage.Record cname = null;
        for (DnsMessage.Record r : m.answers) {
            if (!DnsMessage.canonical(r.name).equals(name)) {
                continue;
            }
            if (r.type == type || type == DnsMessage.TYPE_ANY) {
                direct.add(r);
            } else if (r.type == DnsMessage.TYPE_CNAME && r.target != null) {
                cname = r;
            }
        }
        if (!direct.isEmpty()) {
            finish(f, name, type, new Answer(DnsMessage.RCODE_OK, direct), minTtl(direct));
            return;
        }
        if (cname != null) {
            DnsMessage.Record cn = cname;
            resolve(DnsMessage.canonical(cn.target), type, depth + 1).whenComplete((a, err) -> {
                if (err != null || a == null) {
                    finish(f, name, type, new Answer(DnsMessage.RCODE_SERVFAIL, Collections.<DnsMessage.Record>emptyList()), 0);
                    return;
                }
                List<DnsMessage.Record> chain = new ArrayList<>();
                chain.add(cn);
                chain.addAll(a.records);
                finish(f, name, type, new Answer(a.rcode, chain), Math.min(cn.ttl, minTtl(a.records)));
            });
            return;
        }
        if (m.authoritative) {
            // 名字在，没有这种记录
            finish(f, name, type, new Answer(DnsMessage.RCODE_OK, Collections.<DnsMessage.Record>emptyList()), negativeTtl(m));
            return;
        }
        // 委派：只接受比当前区更近、且包含要问的名字的
        String child = null;
        List<String> nsNames = new ArrayList<>();
        long nsTtl = Long.MAX_VALUE;
        for (DnsMessage.Record r : m.authority) {
            if (r.type != DnsMessage.TYPE_NS || r.target == null) {
                continue;
            }
            String owner = DnsMessage.canonical(r.name);
            if (owner.equals(zone) || !DnsMessage.isUnder(owner, zone) || !DnsMessage.isUnder(name, owner)) {
                continue;
            }
            if (child == null) {
                child = owner;
            }
            if (owner.equals(child)) {
                nsNames.add(DnsMessage.canonical(r.target));
                nsTtl = Math.min(nsTtl, r.ttl);
            }
        }
        if (child == null) {
            finish(f, name, type, new Answer(DnsMessage.RCODE_SERVFAIL, Collections.<DnsMessage.Record>emptyList()), 0);
            return;
        }
        List<Ipv6Address> glue = new ArrayList<>();
        for (DnsMessage.Record r : m.additional) {
            if (r.type == DnsMessage.TYPE_AAAA && r.address != null && nsNames.contains(DnsMessage.canonical(r.name))) {
                glue.add(r.address);
            }
        }
        String next = child;
        long ttl = nsTtl == Long.MAX_VALUE ? 0 : nsTtl;
        if (!glue.isEmpty()) {
            putZone(next, glue, ttl);
            iterate(name, type, depth, next, step + 1, f);
            return;
        }
        // 没有粘连地址：先解析第一个 NS 的名字（不能在要解析的区里，否则绕圈）
        String ns = nsNames.get(0);
        if (DnsMessage.isUnder(ns, next)) {
            finish(f, name, type, new Answer(DnsMessage.RCODE_SERVFAIL, Collections.<DnsMessage.Record>emptyList()), 0);
            return;
        }
        resolve(ns, DnsMessage.TYPE_AAAA, depth + 1).whenComplete((a, err) -> {
            List<Ipv6Address> addrs = new ArrayList<>();
            if (err == null && a != null) {
                for (DnsMessage.Record r : a.records) {
                    if (r.type == DnsMessage.TYPE_AAAA && r.address != null) {
                        addrs.add(r.address);
                    }
                }
            }
            if (addrs.isEmpty()) {
                finish(f, name, type, new Answer(DnsMessage.RCODE_SERVFAIL, Collections.<DnsMessage.Record>emptyList()), 0);
                return;
            }
            putZone(next, addrs, ttl);
            iterate(name, type, depth, next, step + 1, f);
        });
    }

    private void putZone(String zone, List<Ipv6Address> servers, long ttlSeconds) {
        if (zoneServers.size() >= MAX_CACHE) {
            pruneZones();
        }
        zoneServers.put(zone, new ServerSet(servers, loop.nowMillis() + Math.max(1, ttlSeconds) * 1000));
    }

    private static long minTtl(List<DnsMessage.Record> list) {
        long t = Long.MAX_VALUE;
        for (DnsMessage.Record r : list) {
            t = Math.min(t, r.ttl);
        }
        return t == Long.MAX_VALUE ? 0 : t;
    }

    private static long negativeTtl(DnsMessage m) {
        for (DnsMessage.Record r : m.authority) {
            if (r.type == DnsMessage.TYPE_SOA && r.soa != null) {
                return Math.min(r.ttl, r.soa.minimum);
            }
        }
        return 0;
    }

    private void finish(CompletableFuture<Answer> f, String name, int type, Answer a, long ttlSeconds) {
        if (ttlSeconds > 0 && a.rcode != DnsMessage.RCODE_SERVFAIL) {
            if (cache.size() >= MAX_CACHE) {
                pruneCache();
            }
            cache.put(type + " " + name, new CacheEntry(a, loop.nowMillis() + ttlSeconds * 1000));
        }
        f.complete(a);
    }

    private void pruneCache() {
        long now = loop.nowMillis();
        cache.values().removeIf(e -> e.expires <= now);
        if (cache.size() >= MAX_CACHE) {
            cache.clear();
        }
    }

    private void pruneZones() {
        long now = loop.nowMillis();
        zoneServers.values().removeIf(e -> e.expires <= now);
        if (zoneServers.size() >= MAX_CACHE) {
            zoneServers.clear();
        }
    }

    /** 清掉缓存（测试命令用） */
    public void flush() {
        cache.clear();
        zoneServers.clear();
    }

    public int cacheSize() {
        return cache.size();
    }

    /** 测试用：这个问题的回答在不在缓存里（没过期的） */
    boolean isCached(String name, int type) {
        CacheEntry c = cache.get(type + " " + DnsMessage.canonical(name));
        return c != null && c.expires > loop.nowMillis();
    }

    // ---- 往外问 ----

    /** 依次问 {@code servers}，每台问 {@value #TRIES_PER_SERVER} 次；都没回应时 future 以 null 结束 */
    private CompletableFuture<DnsMessage> ask(List<Ipv6Address> servers, int index, int attempt, String name, int type) {
        if (index >= servers.size()) {
            return CompletableFuture.completedFuture(null);
        }
        if (pendingQueries >= MAX_IN_FLIGHT) {
            return CompletableFuture.completedFuture(null);
        }
        Ipv6Address server = servers.get(index);
        return askOne(server, name, type).thenCompose(m -> {
            if (m != null) {
                return CompletableFuture.completedFuture(m);
            }
            return attempt + 1 < TRIES_PER_SERVER ? ask(servers, index, attempt + 1, name, type) : ask(servers, index + 1, 0, name, type);
        });
    }

    /**
     * 问一台服务器一次：新开一个随机端口的 UDP 套接字，随机标识符，带 OPT；{@value #QUERY_TIMEOUT_MS} 毫秒没回应以 null 结束。
     * 应答截断时改用 TCP 问同一台（截断的那份不用）
     */
    private CompletableFuture<DnsMessage> askOne(Ipv6Address server, String name, int type) {
        CompletableFuture<DnsMessage> f = new CompletableFuture<>();
        int id = random.nextInt(0x10000);
        byte[] query = DnsMessage.query(id, name, type, false).withEdns(DnsMessage.Edns.standard()).encode();
        pendingQueries++;
        f.whenComplete((m, e) -> pendingQueries--);
        stack.openUdp(null, 0, new UdpSocket.Handler() {
            @Override
            public void onDatagram(UdpSocket s, Ipv6Address from, int fromPort, Ipv6Address to, byte[] data) {
                if (f.isDone() || !from.equals(server) || fromPort != DnsMessage.PORT) {
                    return;
                }
                DnsMessage m;
                try {
                    m = DnsMessage.parse(data);
                } catch (MalformedPacketException e) {
                    return;
                }
                if (m.id != id || !m.response || m.questions.size() != 1 || !DnsMessage.canonical(m.question().name).equals(name)
                        || m.question().type != type) {
                    return;
                }
                s.close();
                if (m.truncated) {
                    tcpRetries++;
                    askTcp(server, query, id, name, type, f);
                    return;
                }
                f.complete(m);
            }
        }).whenComplete((s, err) -> {
            if (err != null) {
                f.complete(null);
                return;
            }
            synchronized (recentPorts) {
                recentPorts.add(s.port());
                if (recentPorts.size() > 64) {
                    recentPorts.poll();
                }
            }
            s.send(server, DnsMessage.PORT, query);
            loop.schedule(() -> {
                if (!s.isClosed()) {
                    s.close();
                    f.complete(null);
                }
            }, QUERY_TIMEOUT_MS);
        });
        return f;
    }

    private void askTcp(Ipv6Address server, byte[] query, int id, String name, int type, CompletableFuture<DnsMessage> f) {
        DnsTcp.query(stack, null, server, query, TCP_TIMEOUT_MS).whenComplete((data, err) -> {
            if (err != null) {
                f.complete(null);
                return;
            }
            try {
                DnsMessage m = DnsMessage.parse(data);
                if (m.id == id && m.response && m.questions.size() == 1 && DnsMessage.canonical(m.question().name).equals(name)
                        && m.question().type == type && !m.truncated) {
                    f.complete(m);
                    return;
                }
            } catch (MalformedPacketException e) {
                // 下面按没回应算
            }
            f.complete(null);
        });
    }

    /** 测试用 */
    Map<String, Integer> debugCounts() {
        Map<String, Integer> m = new LinkedHashMap<>();
        m.put("cache", cache.size());
        m.put("zones", zoneServers.size());
        m.put("inFlight", inFlight.size());
        m.put("pending", pendingQueries);
        return m;
    }

}
