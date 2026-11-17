package moe.qingu.youran.network;

import moe.qingu.youran.api.network.Ipv6Address;
import moe.qingu.youran.api.network.dns.DnsServer;
import moe.qingu.youran.api.network.dns.Registrar;
import moe.qingu.youran.api.network.dns.Registration;
import moe.qingu.youran.api.network.dns.Whois;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.function.LongSupplier;
import java.util.regex.Pattern;

/**
 * DNS 的全局状态（YouranMod 内部用；给别的模组的入口是 {@code api.network.dns.DnsRegistry}）：
 * 顶级域登记表、根区由哪台 {@link DnsServer} 回答。关服（网络停）时清空。
 */
public final class DnsState {
    /** 顶级域：字母、数字、连字符，不以连字符开头或结尾（国际化顶级域是 A-label） */
    public static final Pattern TLD = Pattern.compile("[a-z0-9]([a-z0-9-]{0,61}[a-z0-9])?");

    private static final class Tld {
        final Registrar registrar;
        final List<Ipv6Address> servers;
        final long registeredAt;

        Tld(Registrar registrar, List<Ipv6Address> servers, long registeredAt) {
            this.registrar = registrar;
            this.servers = Collections.unmodifiableList(servers);
            this.registeredAt = registeredAt;
        }
    }

    private static final Map<String, Tld> tlds = new TreeMap<>();
    private static DnsServer root;
    private static volatile List<Ipv6Address> rootServers = Collections.emptyList();
    static LongSupplier clock = System::currentTimeMillis;

    private DnsState() {
    }

    // ---- 顶级域 ----

    public static Registration registerTld(String tld, Registrar registrar, Collection<Ipv6Address> addresses) {
        if (registrar == null) {
            throw new IllegalArgumentException("registrar 不能为 null");
        }
        String t = tld == null ? "" : tld.trim();
        if (t.startsWith(".")) {
            t = t.substring(1);
        }
        t = moe.qingu.youran.api.network.DomainNames.toAscii(t);
        if (!TLD.matcher(t).matches()) {
            throw new IllegalArgumentException("顶级域不合法：" + tld);
        }
        List<Ipv6Address> list = checkServers(addresses);
        DnsServer r;
        synchronized (DnsState.class) {
            Tld old = tlds.get(t);
            if (old != null && !old.registrar.modId().equals(registrar.modId())) {
                return Registration.failure("顶级域 ." + t + " 已经由 " + old.registrar.modId() + " 登记了");
            }
            tlds.put(t, new Tld(registrar, list, old != null ? old.registeredAt : clock.getAsLong()));
            r = root;
        }
        if (r != null) {
            r.delegate(t, list);
        }
        return Registration.success(tldWhois(t));
    }

    private static List<Ipv6Address> checkServers(Collection<Ipv6Address> addresses) {
        if (addresses == null) {
            throw new IllegalArgumentException("DNS 服务器地址不能为 null");
        }
        List<Ipv6Address> list = new ArrayList<>(new LinkedHashSet<>(addresses));
        if (list.isEmpty() || list.size() > DnsServer.MAX_SERVERS) {
            throw new IllegalArgumentException("DNS 服务器地址要有 1–" + DnsServer.MAX_SERVERS + " 个");
        }
        for (Ipv6Address a : list) {
            if (a == null || a.equals(Ipv6Address.UNSPECIFIED) || a.isMulticast()) {
                throw new IllegalArgumentException("DNS 服务器地址要是一台主机的 /128：" + a);
            }
        }
        return list;
    }

    public static synchronized Registrar registrar(String tld) {
        Tld t = tlds.get(tld);
        return t == null ? null : t.registrar;
    }

    public static synchronized List<String> tlds() {
        return new ArrayList<>(tlds.keySet());
    }

    public static synchronized Whois tldWhois(String tld) {
        Tld t = tlds.get(tld);
        return t == null ? null : new Whois(tld, t.registrar, t.registrar.modId(), true, t.registrar, t.servers, t.registeredAt);
    }

    // ---- 根区 ----

    /** 根区由 {@code server} 回答（YouranMod 开服时调用）：它开始管根区，按登记表委派每个顶级域 */
    public static void bindRoot(DnsServer server) {
        Map<String, Tld> copy;
        synchronized (DnsState.class) {
            root = server;
            rootServers = Collections.singletonList(server.address());
            copy = new TreeMap<>(tlds);
        }
        server.addZone("");
        for (Map.Entry<String, Tld> e : copy.entrySet()) {
            server.delegate(e.getKey(), e.getValue().servers);
        }
    }

    /** 根服务器的地址；网络没在运行时为空 */
    public static List<Ipv6Address> rootServers() {
        return rootServers;
    }

    /** 网络停了：全部清空 */
    public static synchronized void reset() {
        tlds.clear();
        root = null;
        rootServers = Collections.emptyList();
    }
}
