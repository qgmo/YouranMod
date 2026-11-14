package moe.qingu.youran.server.net.service;

import moe.qingu.youran.api.network.DnsZone;
import moe.qingu.youran.api.network.Ipv6Address;
import moe.qingu.youran.api.network.packet.DnsMessage;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 一个权威区（根区、顶级域）的数据：SOA、本区的 NS（带粘连地址）、往下的委派、本区里的 AAAA 记录。
 * 建好以后不改（开服时建），网络线程只读。
 */
public final class DnsZoneData {
    static final long TTL_RECORD = 300;
    static final long TTL_NS = 3600;
    static final long TTL_NEGATIVE = 60;

    final String apex;
    final DnsMessage.Soa soa;
    final List<NameServer> nameServers;
    /** 下级区 → 它的权威服务器 */
    final Map<String, List<NameServer>> delegations;
    /** 本区里固定的记录（名字小写） */
    final Map<String, List<Ipv6Address>> records;
    /** 固定记录里没有的名字再问它（可以为 null） */
    final DnsZone dynamic;

    public static final class NameServer {
        public final String name;
        public final Ipv6Address address;

        public NameServer(String name, Ipv6Address address) {
            this.name = DnsMessage.canonical(name);
            this.address = address;
        }
    }

    public DnsZoneData(String apex, List<NameServer> nameServers, Map<String, List<NameServer>> delegations,
                       Map<String, List<Ipv6Address>> records, DnsZone dynamic, long serial) {
        this.apex = DnsMessage.canonical(apex);
        this.nameServers = Collections.unmodifiableList(new ArrayList<>(nameServers));
        Map<String, List<NameServer>> d = new LinkedHashMap<>();
        for (Map.Entry<String, List<NameServer>> e : delegations.entrySet()) {
            d.put(DnsMessage.canonical(e.getKey()), Collections.unmodifiableList(new ArrayList<>(e.getValue())));
        }
        this.delegations = Collections.unmodifiableMap(d);
        Map<String, List<Ipv6Address>> r = new LinkedHashMap<>();
        for (Map.Entry<String, List<Ipv6Address>> e : records.entrySet()) {
            r.put(DnsMessage.canonical(e.getKey()), Collections.unmodifiableList(new ArrayList<>(e.getValue())));
        }
        this.records = Collections.unmodifiableMap(r);
        this.dynamic = dynamic;
        String mname = nameServers.isEmpty() ? this.apex : nameServers.get(0).name;
        this.soa = new DnsMessage.Soa(mname, "hostmaster.yr", serial, 3600, 600, 86400, TTL_NEGATIVE);
    }

    public String apex() {
        return apex;
    }

    /** 回答一个问题（名字已确认在本区里）。返回填好的应答 */
    DnsMessage answer(DnsMessage q) {
        DnsMessage.Question question = q.question();
        String name = DnsMessage.canonical(question.name);
        int type = question.type;
        List<DnsMessage.Record> an = new ArrayList<>();
        List<DnsMessage.Record> ns = new ArrayList<>();
        List<DnsMessage.Record> ar = new ArrayList<>();
        // 1. 在委派下面：给下级区的 NS 与粘连地址（不是权威回答）
        String cut = null;
        for (String child : delegations.keySet()) {
            if (DnsMessage.isUnder(name, child) && (cut == null || child.length() > cut.length())) {
                cut = child;
            }
        }
        if (cut != null) {
            for (NameServer s : delegations.get(cut)) {
                ns.add(DnsMessage.Record.ns(cut, TTL_NS, s.name));
                if (s.address != null) {
                    ar.add(DnsMessage.Record.aaaa(s.name, TTL_NS, s.address));
                }
            }
            return DnsMessage.reply(q, false, false, DnsMessage.RCODE_OK, an, ns, ar);
        }
        // 2. 区顶
        if (name.equals(apex)) {
            if (type == DnsMessage.TYPE_NS || type == DnsMessage.TYPE_ANY) {
                for (NameServer s : nameServers) {
                    an.add(DnsMessage.Record.ns(apex, TTL_NS, s.name));
                    if (s.address != null) {
                        ar.add(DnsMessage.Record.aaaa(s.name, TTL_NS, s.address));
                    }
                }
            }
            if (type == DnsMessage.TYPE_SOA || type == DnsMessage.TYPE_ANY) {
                an.add(DnsMessage.Record.soa(apex, TTL_NS, soa));
            }
            if (an.isEmpty()) {
                ns.add(DnsMessage.Record.soa(apex, TTL_NEGATIVE, soa));
            }
            return DnsMessage.reply(q, true, false, DnsMessage.RCODE_OK, an, ns, ar);
        }
        // 3. 区里的名字
        List<Ipv6Address> addresses = records.get(name);
        if (addresses == null && dynamic != null) {
            try {
                addresses = dynamic.aaaa(name);
            } catch (RuntimeException e) {
                return DnsMessage.reply(q, true, false, DnsMessage.RCODE_SERVFAIL, an, ns, ar);
            }
        }
        if (addresses == null) {
            ns.add(DnsMessage.Record.soa(apex, TTL_NEGATIVE, soa));
            return DnsMessage.reply(q, true, false, DnsMessage.RCODE_NXDOMAIN, an, ns, ar);
        }
        if (type == DnsMessage.TYPE_AAAA || type == DnsMessage.TYPE_ANY) {
            for (Ipv6Address a : addresses) {
                an.add(DnsMessage.Record.aaaa(name, TTL_RECORD, a));
            }
        }
        if (an.isEmpty()) {
            ns.add(DnsMessage.Record.soa(apex, TTL_NEGATIVE, soa));
        }
        return DnsMessage.reply(q, true, false, DnsMessage.RCODE_OK, an, ns, ar);
    }
}
