package moe.qingu.youran.api.network.stack;

import moe.qingu.youran.api.network.Ipv6Address;
import moe.qingu.youran.api.network.packet.DnsMessage;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.CompletableFuture;

/**
 * 服务端网站要在生成页面时同步知道“这个名字现在解析得到哪些地址”（例如 180.yr 只列解析得到的网站、id.yr 开没开）时用的小缓存：
 * 每个名字记最后一次的回答，经递归解析器用真的 DNS 查询在后台刷新（{@link DnsResolver}，走这台主机的协议栈）。
 * <ul>
 * <li>有地址的回答按记录的 TTL（解析器给的剩余时间）到期；查无此名、没有地址、查询失败的 {@value #NEGATIVE_RETRY_MS} 毫秒后再问
 * （设备那边的解析器回答里不带 SOA，拿不到否定回答的 TTL）。</li>
 * <li>到期以后 {@link #addresses} 照样先给上一次的回答，同时在后台问一次，回答来了就换（同 RFC 8767 的“过期先用”）。</li>
 * <li>从没问过的名字给空表，并开始问。</li>
 * </ul>
 * 读任意线程都行；查询排到协议栈的网络线程上发。最多记 {@value #MAX_NAMES} 个名字。
 */
public final class NameCache {
    /** 否定回答、失败多久以后再问 */
    public static final long NEGATIVE_RETRY_MS = 60_000;
    public static final int MAX_NAMES = 256;

    private final DnsResolver resolver;
    private final EventLoop loop;
    private final Map<String, Entry> entries = new LinkedHashMap<>();

    private static final class Entry {
        List<Ipv6Address> addresses = Collections.emptyList();
        long expires;
        CompletableFuture<List<Ipv6Address>> inFlight;
    }

    /** {@code resolver} 跑在 {@code loop} 那个协议栈上（一般是 {@code new DnsResolver(stack, YouranNetworkApi.resolverAddress())} 与 {@code stack.loop()}） */
    public NameCache(DnsResolver resolver, EventLoop loop) {
        this.resolver = resolver;
        this.loop = loop;
    }

    /** 名字现在的地址（上一次的回答；到期或没问过时在后台问） */
    public List<Ipv6Address> addresses(String name) {
        String n = name.trim().toLowerCase(Locale.ROOT);
        synchronized (this) {
            Entry e = entries.get(n);
            if (e != null && (e.inFlight != null || loop.nowMillis() < e.expires)) {
                return e.addresses;
            }
        }
        refresh(n);
        synchronized (this) {
            Entry e = entries.get(n);
            return e == null ? Collections.<Ipv6Address>emptyList() : e.addresses;
        }
    }

    /** 名字现在解析得到地址 */
    public boolean resolves(String name) {
        return !addresses(name).isEmpty();
    }

    /** 马上问一次（已经在问时返回那一次）；回答来了以后换进缓存，future 以新的地址表结束（查不到为空表） */
    public CompletableFuture<List<Ipv6Address>> refresh(String name) {
        String n = name.trim().toLowerCase(Locale.ROOT);
        CompletableFuture<List<Ipv6Address>> f;
        synchronized (this) {
            Entry e = entries.get(n);
            if (e == null) {
                if (entries.size() >= MAX_NAMES) {
                    Iterator<Map.Entry<String, Entry>> it = entries.entrySet().iterator();
                    while (it.hasNext() && entries.size() >= MAX_NAMES) {
                        if (it.next().getValue().inFlight == null) {
                            it.remove();
                        }
                    }
                }
                e = new Entry();
                entries.put(n, e);
            }
            if (e.inFlight != null) {
                return e.inFlight;
            }
            f = new CompletableFuture<>();
            e.inFlight = f;
        }
        loop.execute(() -> {
            CompletableFuture<DnsResolver.Result> q;
            try {
                q = resolver.resolveAaaa(n);
            } catch (RuntimeException ex) {
                q = new CompletableFuture<>();
                q.completeExceptionally(ex);
            }
            q.whenComplete((r, err) -> settle(n, f, r, err));
        });
        return f;
    }

    private void settle(String n, CompletableFuture<List<Ipv6Address>> f, DnsResolver.Result r, Throwable err) {
        List<Ipv6Address> addrs = Collections.emptyList();
        long ttlMs = NEGATIVE_RETRY_MS;
        if (err == null && r != null && r.rcode == DnsMessage.RCODE_OK && !r.addresses.isEmpty()) {
            addrs = Collections.unmodifiableList(new ArrayList<>(r.addresses));
            long min = Long.MAX_VALUE;
            if (r.response != null) {
                for (DnsMessage.Record x : r.response.answers) {
                    if (x.type == DnsMessage.TYPE_AAAA) {
                        min = Math.min(min, x.ttl);
                    }
                }
            }
            ttlMs = min == Long.MAX_VALUE ? NEGATIVE_RETRY_MS : Math.max(1, min) * 1000;
        }
        synchronized (this) {
            Entry e = entries.get(n);
            if (e != null && e.inFlight == f) {
                e.addresses = addrs;
                e.expires = loop.nowMillis() + ttlMs;
                e.inFlight = null;
            }
        }
        f.complete(addrs);
    }
}
