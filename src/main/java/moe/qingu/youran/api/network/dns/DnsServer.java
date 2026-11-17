package moe.qingu.youran.api.network.dns;

import moe.qingu.youran.api.network.DomainNames;
import moe.qingu.youran.api.network.Ipv6Address;
import moe.qingu.youran.api.network.packet.DnsMessage;
import moe.qingu.youran.api.network.packet.MalformedPacketException;
import moe.qingu.youran.api.network.stack.NetStack;
import moe.qingu.youran.api.network.stack.UdpSocket;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

/**
 * YouranMod 的默认权威 DNS 服务：跑在一台主机的协议栈上（UDP 53），可以同时管几个区，运行中能改记录。不递归；
 * 问的名字不在它管的区里时回 REFUSED。
 * <p>
 * 支持的记录：AAAA、TXT，以及往下的委派（NS）。区顶的 SOA、NS 自动生成：NS 叫 {@code ns1.<区>}（根区叫 {@code ns1}），
 * 指向这台主机自己的地址；委派给下级的 NS 叫 {@code ns1.<下级>}、{@code ns2.<下级>}……，带粘连地址。
 * TTL 固定：记录 {@value #TTL_RECORD} 秒、NS {@value #TTL_NS} 秒、查无此名 {@value #TTL_NEGATIVE} 秒。
 * <p>
 * 改记录的方法任意线程都可以调用：改动先记下来，排到网络线程上一起换成新的区数据（同一批改动一起生效）；
 * 解析器里已经缓存的旧回答按 TTL 过期，不会主动清。名字可以写 Unicode，自动转成 A-label。
 */
public final class DnsServer implements UdpSocket.Handler {
    public static final long TTL_RECORD = 300;
    public static final long TTL_NS = 3600;
    public static final long TTL_NEGATIVE = 60;
    /** 一个委派最多几个 DNS 服务器地址 */
    public static final int MAX_SERVERS = 8;

    private final NetStack stack;
    private final Ipv6Address self;
    /** 还没换上去的区数据（锁 this） */
    private final Map<String, Draft> drafts = new TreeMap<>();
    private boolean publishQueued;
    private long serial = 1;
    /** 正在用的区数据（只读，任意线程） */
    private volatile Map<String, Zone> zones = Collections.emptyMap();

    private DnsServer(NetStack stack) {
        this.stack = stack;
        this.self = stack.primaryAddress();
        if (self == null) {
            throw new IllegalArgumentException("协议栈还没有地址");
        }
    }

    /** 在协议栈 {@code stack} 上开一个默认权威 DNS 服务（UDP 53，地址是这个协议栈的主地址）。在网络线程上调用 */
    public static DnsServer start(NetStack stack) {
        DnsServer s = new DnsServer(stack);
        stack.openUdp(null, DnsMessage.PORT, s);
        return s;
    }

    /** 这台 DNS 服务器的地址（注册域名时交给注册局的就是它） */
    public Ipv6Address address() {
        return self;
    }

    // ---- 改区、改记录 ----

    /** 开始管区 {@code apex}（如 {@code minebay.yr}；根区写空串）。已经在管时什么都不做 */
    public synchronized void addZone(String apex) {
        String a = name(apex);
        if (!drafts.containsKey(a)) {
            drafts.put(a, new Draft(a));
            changed();
        }
    }

    /** 不再管区 {@code apex}（连同里面的记录） */
    public synchronized void removeZone(String apex) {
        if (drafts.remove(name(apex)) != null) {
            changed();
        }
    }

    /** 现在管的区（包括还没换上去的） */
    public synchronized List<String> zones() {
        return new ArrayList<>(drafts.keySet());
    }

    /** 设 {@code name} 的 AAAA 记录（替换原有的）；{@code addresses} 为空时删掉 AAAA。名字要在管的某个区里、不在委派下面 */
    public synchronized void setAddresses(String name, Collection<Ipv6Address> addresses) {
        String n = name(name);
        Draft d = recordZone(n);
        List<Ipv6Address> list = new ArrayList<>(new LinkedHashSet<>(addresses));
        if (list.isEmpty() ? d.aaaa.remove(n) != null : !list.equals(d.aaaa.put(n, list))) {
            changed();
        }
    }

    /** 设 {@code name} 的 TXT 记录（每段文字一条，替换原有的）；{@code texts} 为空时删掉 TXT。名字要在管的某个区里、不在委派下面 */
    public synchronized void setTexts(String name, Collection<String> texts) {
        String n = name(name);
        Draft d = recordZone(n);
        List<String> list = new ArrayList<>(new LinkedHashSet<>(texts));
        for (String t : list) {
            if (t == null || t.getBytes(java.nio.charset.StandardCharsets.UTF_8).length > 1024) {
                throw new IllegalArgumentException("TXT 记录不能为 null，最长 1024 字节");
            }
        }
        if (list.isEmpty() ? d.txt.remove(n) != null : !list.equals(d.txt.put(n, list))) {
            changed();
        }
    }

    /**
     * 把 {@code name}（在管的某个区下面、不是区顶）委派给 {@code servers} 这几台 DNS 服务器（替换原有的委派）；
     * {@code servers} 为空时取消委派。
     */
    public synchronized void delegate(String name, Collection<Ipv6Address> servers) {
        String n = name(name);
        // 上级区（这台服务器同时管下级区时也写在上级区里）
        Draft d = null;
        for (Draft z : drafts.values()) {
            if (!z.apex.equals(n) && DnsMessage.isUnder(n, z.apex) && (d == null || z.apex.length() > d.apex.length())) {
                d = z;
            }
        }
        if (d == null) {
            throw new IllegalArgumentException(n + " 不在这台 DNS 服务器管的区下面（区顶不能委派）");
        }
        List<Ipv6Address> list = checkServers(servers, true);
        if (list.isEmpty() ? d.delegations.remove(n) != null : !list.equals(d.delegations.put(n, list))) {
            changed();
        }
    }

    /** 删掉 {@code name} 的全部记录（AAAA、TXT、委派）。没有时什么都不做 */
    public synchronized void remove(String name) {
        String n = name(name);
        boolean any = false;
        for (Draft d : drafts.values()) {
            if (DnsMessage.isUnder(n, d.apex)) {
                any |= d.aaaa.remove(n) != null | d.txt.remove(n) != null | d.delegations.remove(n) != null;
            }
        }
        if (any) {
            changed();
        }
    }

    private Draft recordZone(String n) {
        Draft d = zoneOf(n);
        if (d == null) {
            throw new IllegalArgumentException(n + " 不在这台 DNS 服务器管的区里");
        }
        for (String c : d.delegations.keySet()) {
            if (DnsMessage.isUnder(n, c)) {
                throw new IllegalArgumentException(n + " 在委派出去的 " + c + " 下面，要在那边的 DNS 服务器上设");
            }
        }
        return d;
    }

    private Draft zoneOf(String n) {
        Draft best = null;
        for (Draft d : drafts.values()) {
            if (DnsMessage.isUnder(n, d.apex) && (best == null || d.apex.length() > best.apex.length())) {
                best = d;
            }
        }
        return best;
    }

    static String name(String name) {
        if (name == null) {
            throw new IllegalArgumentException("名字不能为 null");
        }
        String n = name.trim();
        if (n.endsWith(".")) {
            n = n.substring(0, n.length() - 1);
        }
        if (n.isEmpty()) {
            return "";
        }
        // 记录名可以带下划线（如证书 DNS 认证用的 _acme-challenge），不能按主机名的规则转；只有非 ASCII 的才按国际化域名转
        if (DomainNames.isAscii(n)) {
            String a = DnsMessage.canonical(n);
            if (!RECORD_NAME.matcher(a).matches() || a.length() > DomainNames.MAX_NAME) {
                throw new IllegalArgumentException("名字不合法：" + name);
            }
            return a;
        }
        return DnsMessage.canonical(DomainNames.toAscii(n));
    }

    /** 记录名：每段字母、数字、下划线、连字符，连字符不在两头，每段最长 63 */
    private static final java.util.regex.Pattern RECORD_NAME = java.util.regex.Pattern.compile(
            "[a-z0-9_](?:[a-z0-9_-]{0,61}[a-z0-9_])?(?:\\.[a-z0-9_](?:[a-z0-9_-]{0,61}[a-z0-9_])?)*");

    static List<Ipv6Address> checkServers(Collection<Ipv6Address> servers, boolean allowEmpty) {
        if (servers == null) {
            throw new IllegalArgumentException("DNS 服务器地址不能为 null");
        }
        List<Ipv6Address> list = new ArrayList<>(new LinkedHashSet<>(servers));
        if (list.isEmpty() && !allowEmpty) {
            throw new IllegalArgumentException("至少要一个 DNS 服务器地址");
        }
        if (list.size() > MAX_SERVERS) {
            throw new IllegalArgumentException("DNS 服务器地址最多 " + MAX_SERVERS + " 个");
        }
        for (Ipv6Address a : list) {
            if (a == null || a.equals(Ipv6Address.UNSPECIFIED) || a.isMulticast()) {
                throw new IllegalArgumentException("DNS 服务器地址要是一台主机的 /128：" + a);
            }
        }
        return list;
    }

    /** 记下有改动：排一次换数据（同一批改动一起换） */
    private void changed() {
        if (!publishQueued) {
            publishQueued = true;
            stack.loop().execute(this::publish);
        }
    }

    private void publish() {
        Map<String, Zone> next = new LinkedHashMap<>();
        synchronized (this) {
            publishQueued = false;
            long s = serial++;
            for (Draft d : drafts.values()) {
                next.put(d.apex, new Zone(d, self, s));
            }
        }
        zones = Collections.unmodifiableMap(next);
    }

    // ---- 回答 ----

    @Override
    public void onDatagram(UdpSocket socket, Ipv6Address from, int fromPort, Ipv6Address to, byte[] data) {
        DnsMessage q;
        try {
            q = DnsMessage.parse(data);
        } catch (MalformedPacketException e) {
            return;
        }
        DnsMessage reply = answer(q);
        if (reply != null) {
            socket.send(to, from, fromPort, reply.encodeForUdp());
        }
    }

    /** 按正在用的区数据回答一个查询（不走报文；任意线程）。{@code q} 是应答报文时不回，返回 null */
    public DnsMessage answer(DnsMessage q) {
        if (q.response) {
            return null;
        }
        List<DnsMessage.Record> none = Collections.emptyList();
        if (q.opcode != 0) {
            return DnsMessage.reply(q, false, false, DnsMessage.RCODE_NOTIMP, none, none, none);
        }
        if (q.questions.size() != 1) {
            return DnsMessage.reply(q, false, false, DnsMessage.RCODE_FORMERR, none, none, none);
        }
        DnsMessage.Question question = q.question();
        if (question.qclass != DnsMessage.CLASS_IN) {
            return DnsMessage.reply(q, false, false, DnsMessage.RCODE_REFUSED, none, none, none);
        }
        String n = DnsMessage.canonical(question.name);
        Zone zone = null;
        for (Zone z : zones.values()) {
            if (DnsMessage.isUnder(n, z.apex) && (zone == null || z.apex.length() > zone.apex.length())) {
                zone = z;
            }
        }
        if (zone == null) {
            return DnsMessage.reply(q, false, false, DnsMessage.RCODE_REFUSED, none, none, none);
        }
        return zone.answer(q, n, question.type);
    }

    /** 改动中的区 */
    private static final class Draft {
        final String apex;
        final Map<String, List<Ipv6Address>> aaaa = new TreeMap<>();
        final Map<String, List<String>> txt = new TreeMap<>();
        final Map<String, List<Ipv6Address>> delegations = new TreeMap<>();

        Draft(String apex) {
            this.apex = apex;
        }
    }

    /** 换上去的区（不可变） */
    private static final class Zone {
        final String apex;
        final DnsMessage.Soa soa;
        final String nsName;
        final Ipv6Address nsAddress;
        final Map<String, List<Ipv6Address>> aaaa;
        final Map<String, List<String>> txt;
        final Map<String, List<Ipv6Address>> delegations;

        Zone(Draft d, Ipv6Address self, long serial) {
            this.apex = d.apex;
            this.aaaa = copy(d.aaaa);
            this.txt = copy(d.txt);
            this.delegations = copy(d.delegations);
            this.nsName = nsName(apex, 1);
            this.nsAddress = self;
            this.soa = new DnsMessage.Soa(nsName, apex.isEmpty() ? "hostmaster" : "hostmaster." + apex, serial, 3600, 600, 86400, TTL_NEGATIVE);
        }

        private static <T> Map<String, List<T>> copy(Map<String, List<T>> m) {
            Map<String, List<T>> out = new LinkedHashMap<>();
            for (Map.Entry<String, List<T>> e : m.entrySet()) {
                out.put(e.getKey(), Collections.unmodifiableList(new ArrayList<>(e.getValue())));
            }
            return Collections.unmodifiableMap(out);
        }

        DnsMessage answer(DnsMessage q, String name, int type) {
            List<DnsMessage.Record> an = new ArrayList<>();
            List<DnsMessage.Record> ns = new ArrayList<>();
            List<DnsMessage.Record> ar = new ArrayList<>();
            // 1. 在委派下面：给下级的 NS 与粘连地址（不是权威回答）
            String cut = null;
            for (String child : delegations.keySet()) {
                if (DnsMessage.isUnder(name, child) && (cut == null || child.length() > cut.length())) {
                    cut = child;
                }
            }
            if (cut != null) {
                List<Ipv6Address> servers = delegations.get(cut);
                for (int i = 0; i < servers.size(); i++) {
                    String host = nsName(cut, i + 1);
                    ns.add(DnsMessage.Record.ns(cut, TTL_NS, host));
                    ar.add(DnsMessage.Record.aaaa(host, TTL_NS, servers.get(i)));
                }
                return DnsMessage.reply(q, false, false, DnsMessage.RCODE_OK, an, ns, ar);
            }
            boolean any = type == DnsMessage.TYPE_ANY;
            boolean exists = name.equals(apex);
            // 2. 区顶的 NS、SOA
            if (name.equals(apex)) {
                if (type == DnsMessage.TYPE_NS || any) {
                    an.add(DnsMessage.Record.ns(apex, TTL_NS, nsName));
                    ar.add(DnsMessage.Record.aaaa(nsName, TTL_NS, nsAddress));
                }
                if (type == DnsMessage.TYPE_SOA || any) {
                    an.add(DnsMessage.Record.soa(apex, TTL_NS, soa));
                }
            }
            // 3. 记录
            List<Ipv6Address> addrs = aaaa.get(name);
            if (addrs == null && name.equals(nsName)) {
                addrs = Collections.singletonList(nsAddress);
            }
            if (addrs != null) {
                exists = true;
                if (type == DnsMessage.TYPE_AAAA || any) {
                    for (Ipv6Address a : addrs) {
                        an.add(DnsMessage.Record.aaaa(name, TTL_RECORD, a));
                    }
                }
            }
            List<String> texts = txt.get(name);
            if (texts != null) {
                exists = true;
                if (type == DnsMessage.TYPE_TXT || any) {
                    for (String t : texts) {
                        an.add(DnsMessage.Record.txt(name, TTL_RECORD, t));
                    }
                }
            }
            if (!exists) {
                exists = hasDescendant(name);
            }
            if (!exists) {
                ns.add(DnsMessage.Record.soa(apex, TTL_NEGATIVE, soa));
                return DnsMessage.reply(q, true, false, DnsMessage.RCODE_NXDOMAIN, an, ns, ar);
            }
            if (an.isEmpty()) {
                ns.add(DnsMessage.Record.soa(apex, TTL_NEGATIVE, soa));
            }
            return DnsMessage.reply(q, true, false, DnsMessage.RCODE_OK, an, ns, ar);
        }

        /** 名字下面还有别的名字（空的中间节点：名字存在，只是没有记录） */
        private boolean hasDescendant(String name) {
            String suffix = "." + name;
            for (String k : aaaa.keySet()) {
                if (k.endsWith(suffix)) {
                    return true;
                }
            }
            for (String k : txt.keySet()) {
                if (k.endsWith(suffix)) {
                    return true;
                }
            }
            for (String k : delegations.keySet()) {
                if (k.endsWith(suffix)) {
                    return true;
                }
            }
            return nsName.endsWith(suffix);
        }
    }

    /** 第 {@code i} 个名字服务器的名字：{@code ns<i>.<区>}（根区是 {@code ns<i>}） */
    static String nsName(String zone, int i) {
        return zone.isEmpty() ? "ns" + i : "ns" + i + "." + zone;
    }
}
