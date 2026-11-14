package moe.qingu.youran.server.net.service;

import moe.qingu.youran.api.network.Ipv6Address;
import moe.qingu.youran.api.network.packet.DnsMessage;
import moe.qingu.youran.api.network.packet.MalformedPacketException;
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
 * 递归解析器（相当于现实里的 8.8.8.8）：设备只问它（UDP 53，设 RD），它从根开始问：根 → 顶级域 → 权威服务器，
 * 跟着委派（用粘连地址；没有粘连地址时先去解析 NS 的名字）、展开 CNAME，带缓存（按 TTL，否定回答按 SOA 的 minimum）。
 * 以后接公网时，转发也在这里做。全部在网络线程上，异步。
 */
public final class RecursiveResolver implements UdpSocket.Handler {
    static final long QUERY_TIMEOUT_MS = 1500;
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
    private final Map<Integer, Pending> pendingQueries = new HashMap<>();
    private final java.util.Random random = new java.security.SecureRandom();
    private UdpSocket socket;
    private UdpSocket upstream;

    // 统计
    public long queries;
    public long cacheHits;

    public RecursiveResolver(NetStack stack, List<Ipv6Address> rootServers) {
        this.stack = stack;
        this.loop = stack.loop();
        this.rootServers = Collections.unmodifiableList(new ArrayList<>(rootServers));
    }

    public void start() {
        stack.openUdp(null, DnsMessage.PORT, this).thenAccept(s -> socket = s);
        // 往外问用一个临时端口（标识符随机，校验来源地址、端口、标识符与问题）
        stack.openUdp(null, 0, new UdpSocket.Handler() {
            @Override
            public void onDatagram(UdpSocket s, Ipv6Address from, int fromPort, Ipv6Address to, byte[] data) {
                onUpstreamReply(from, fromPort, data);
            }
        }).thenAccept(s -> upstream = s);
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
        queries++;
        List<DnsMessage.Record> none = Collections.emptyList();
        if (q.opcode != 0) {
            reply(s, to, from, fromPort, DnsMessage.reply(q, false, true, DnsMessage.RCODE_NOTIMP, none, none, none));
            return;
        }
        if (q.questions.size() != 1 || q.question().qclass != DnsMessage.CLASS_IN) {
            reply(s, to, from, fromPort, DnsMessage.reply(q, false, true, DnsMessage.RCODE_FORMERR, none, none, none));
            return;
        }
        DnsMessage.Question question = q.question();
        resolve(DnsMessage.canonical(question.name), question.type, 0).whenComplete((a, err) -> {
            DnsMessage r;
            if (err != null || a == null) {
                r = DnsMessage.reply(q, false, true, DnsMessage.RCODE_SERVFAIL, none, none, none);
            } else {
                r = DnsMessage.reply(q, false, true, a.rcode, a.records, none, none);
            }
            reply(s, to, from, fromPort, r);
        });
    }

    private static void reply(UdpSocket s, Ipv6Address src, Ipv6Address dst, int port, DnsMessage m) {
        s.send(src, dst, port, m.encodeForUdp());
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

    // ---- 往外问 ----

    private final class Pending {
        final int id;
        final Ipv6Address server;
        final String name;
        final int type;
        final CompletableFuture<DnsMessage> future;
        EventLoop.Timer timer;

        Pending(int id, Ipv6Address server, String name, int type, CompletableFuture<DnsMessage> future) {
            this.id = id;
            this.server = server;
            this.name = name;
            this.type = type;
            this.future = future;
        }
    }

    /** 依次问 {@code servers}，每台问 {@value #TRIES_PER_SERVER} 次；都没回应时 future 以 null 结束 */
    private CompletableFuture<DnsMessage> ask(List<Ipv6Address> servers, int index, int attempt, String name, int type) {
        if (index >= servers.size()) {
            return CompletableFuture.completedFuture(null);
        }
        if (upstream == null || pendingQueries.size() >= MAX_IN_FLIGHT) {
            return CompletableFuture.completedFuture(null);
        }
        CompletableFuture<DnsMessage> f = new CompletableFuture<>();
        int id;
        do {
            id = random.nextInt(0x10000);
        } while (pendingQueries.containsKey(id));
        Ipv6Address server = servers.get(index);
        Pending p = new Pending(id, server, name, type, f);
        pendingQueries.put(id, p);
        p.timer = loop.schedule(() -> {
            if (pendingQueries.remove(p.id) == p) {
                f.complete(null);
            }
        }, QUERY_TIMEOUT_MS);
        upstream.send(server, DnsMessage.PORT, DnsMessage.query(id, name, type, false).encode());
        return f.thenCompose(m -> {
            if (m != null) {
                return CompletableFuture.completedFuture(m);
            }
            return attempt + 1 < TRIES_PER_SERVER ? ask(servers, index, attempt + 1, name, type) : ask(servers, index + 1, 0, name, type);
        });
    }

    private void onUpstreamReply(Ipv6Address from, int fromPort, byte[] data) {
        DnsMessage m;
        try {
            m = DnsMessage.parse(data);
        } catch (MalformedPacketException e) {
            return;
        }
        Pending p = pendingQueries.get(m.id);
        if (p == null || !m.response || !from.equals(p.server) || fromPort != DnsMessage.PORT || m.questions.size() != 1
                || !DnsMessage.canonical(m.question().name).equals(p.name) || m.question().type != p.type) {
            return;
        }
        pendingQueries.remove(m.id);
        p.timer.cancel();
        p.future.complete(m);
    }

    /** 测试用 */
    Map<String, Integer> debugCounts() {
        Map<String, Integer> m = new LinkedHashMap<>();
        m.put("cache", cache.size());
        m.put("zones", zoneServers.size());
        m.put("inFlight", inFlight.size());
        m.put("pending", pendingQueries.size());
        return m;
    }

}
