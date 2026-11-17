package moe.qingu.youran.server.net.service;

import moe.qingu.youran.api.network.Ipv6Address;
import moe.qingu.youran.api.network.dns.DefaultRegistrar;
import moe.qingu.youran.api.network.dns.DnsRegistry;
import moe.qingu.youran.api.network.dns.DnsServer;
import moe.qingu.youran.api.network.dns.Registration;
import moe.qingu.youran.api.network.packet.DnsMessage;
import moe.qingu.youran.api.network.packet.Ipv6Packet;
import moe.qingu.youran.api.network.stack.DnsResolver;
import moe.qingu.youran.api.network.stack.NameCache;
import moe.qingu.youran.api.network.stack.NetStack;
import moe.qingu.youran.api.network.stack.TestLink;
import moe.qingu.youran.network.DnsState;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.File;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.CompletableFuture;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 分层委派：根 → 顶级域 → 二级域名自己的 DNS → 记录，经递归解析器（假时钟）；运行中注册、删除按 TTL 传开；
 * 服务端网站用的名字缓存（{@link NameCache}，真的 DNS 查询、按 TTL 刷新）。
 */
class DnsDelegationTest {
    static final Ipv6Address ROOT = Ipv6Address.parse("fd00::1:53");
    static final Ipv6Address TLD = Ipv6Address.parse("fd00::2:53");
    static final Ipv6Address SHOP_DNS = Ipv6Address.parse("fd00::c:53");
    static final Ipv6Address RESOLVER = Ipv6Address.parse("fd00::53");
    static final Ipv6Address WEB = Ipv6Address.parse("fd00::80");
    static final Ipv6Address WEB2 = Ipv6Address.parse("fd00::81");

    @TempDir
    File dir;
    TestLink link;
    NetStack client;
    DefaultRegistrar yr;
    DnsServer shopDns;
    /** 解析器往外问的：目的地址 */
    final List<Ipv6Address> asked = new ArrayList<>();

    @BeforeEach
    void setUp() {
        DnsState.reset();
        link = new TestLink();
        client = link.host("fd00::c");
        DnsState.bindRoot(DnsServer.start(link.host(ROOT.toString())));
        DnsServer tld = DnsServer.start(link.host(TLD.toString()));
        yr = new DefaultRegistrar("youranmod", "yr", tld, new File(dir, "yr.json"));
        DnsRegistry.register("yr", yr, Collections.singletonList(TLD));
        shopDns = DnsServer.start(link.host(SHOP_DNS.toString()));
        RecursiveResolver r = new RecursiveResolver(link.host(RESOLVER.toString()), DnsState.rootServers());
        r.start();
        link.loop.drain();
        link.filter = raw -> {
            Ipv6Address d = Ipv6Packet.destinationOf(raw);
            if (d.equals(ROOT) || d.equals(TLD) || d.equals(SHOP_DNS)) {
                asked.add(d);
            }
            return 0L;
        };
    }

    DnsMessage query(String name, int type) throws Exception {
        CompletableFuture<DnsMessage> f = new DnsResolver(client, RESOLVER).query(name, type);
        link.loop.advance(5_000);
        assertTrue(f.isDone());
        return f.get();
    }

    List<Ipv6Address> aaaa(String name) throws Exception {
        List<Ipv6Address> out = new ArrayList<>();
        for (DnsMessage.Record r : query(name, DnsMessage.TYPE_AAAA).answers) {
            if (r.address != null) {
                out.add(r.address);
            }
        }
        return out;
    }

    /** 网站以模组身份注册 shop.yr，自己的 DNS 管记录 */
    void registerShop() {
        shopDns.addZone("shop.yr");
        shopDns.setAddresses("shop.yr", Collections.singletonList(WEB));
        shopDns.setAddresses("www.shop.yr", Collections.singletonList(WEB));
        Registration r = DnsRegistry.registrar("yr").register("shop.yr", "shopmod", Collections.singletonList(shopDns.address()));
        assertEquals(Registration.Status.SUCCESS, r.status());
    }

    @Test
    void walksRootTldAndSecondLevel() throws Exception {
        registerShop();
        assertEquals(Collections.singletonList(WEB), aaaa("www.shop.yr"));
        List<Ipv6Address> chain = new ArrayList<>();
        chain.add(ROOT);
        chain.add(TLD);
        chain.add(SHOP_DNS);
        assertEquals(chain, asked, "根 → .yr → shop.yr 自己的 DNS，各问一次");
        // 同一个二级域名下的另一个名字：直接问 shop.yr 的 DNS（委派已缓存）
        asked.clear();
        assertEquals(DnsMessage.RCODE_NXDOMAIN, query("none.shop.yr", DnsMessage.TYPE_AAAA).rcode);
        assertEquals(Collections.singletonList(SHOP_DNS), asked);
    }

    @Test
    void txtThroughTheResolver() throws Exception {
        registerShop();
        shopDns.setTexts("_acme-challenge.shop.yr", Collections.singletonList("abc123"));
        DnsMessage m = query("_acme-challenge.shop.yr", DnsMessage.TYPE_TXT);
        assertEquals(DnsMessage.RCODE_OK, m.rcode);
        assertEquals("abc123", m.answers.get(0).text());
    }

    @Test
    void runtimeRegistrationVisibleAfterNegativeTtl() throws Exception {
        assertEquals(DnsMessage.RCODE_NXDOMAIN, query("www.shop.yr", DnsMessage.TYPE_AAAA).rcode);
        registerShop();
        // 解析器还缓存着“查无此名”（不主动清）
        assertEquals(DnsMessage.RCODE_NXDOMAIN, query("www.shop.yr", DnsMessage.TYPE_AAAA).rcode);
        link.loop.advance(DnsServer.TTL_NEGATIVE * 1000);
        assertEquals(Collections.singletonList(WEB), aaaa("www.shop.yr"));
    }

    @Test
    void changedAndRemovedRecordsAfterTtl() throws Exception {
        registerShop();
        assertEquals(Collections.singletonList(WEB), aaaa("www.shop.yr"));
        shopDns.setAddresses("www.shop.yr", Collections.singletonList(WEB2));
        // TTL 内：缓存里的旧地址
        link.loop.advance(DnsServer.TTL_RECORD * 1000 - 10_000);
        assertEquals(Collections.singletonList(WEB), aaaa("www.shop.yr"));
        link.loop.advance(10_000);
        assertEquals(Collections.singletonList(WEB2), aaaa("www.shop.yr"));
        // 删掉：过了 TTL 查不到
        shopDns.remove("www.shop.yr");
        assertEquals(Collections.singletonList(WEB2), aaaa("www.shop.yr"));
        link.loop.advance(DnsServer.TTL_RECORD * 1000);
        assertEquals(DnsMessage.RCODE_NXDOMAIN, query("www.shop.yr", DnsMessage.TYPE_AAAA).rcode);
    }

    @Test
    void newTopLevelDomainAfterRootNegativeTtl() throws Exception {
        assertEquals(DnsMessage.RCODE_NXDOMAIN, query("www.game.cfm", DnsMessage.TYPE_AAAA).rcode);
        NetStack modStack = link.host("fd00::d:53");
        DnsServer mod = DnsServer.start(modStack);
        DefaultRegistrar cfm = new DefaultRegistrar("cfm", "cfm", mod, new File(dir, "cfm.json"));
        DnsRegistry.register("cfm", cfm, Collections.singletonList(mod.address()));
        mod.addZone("game.cfm");
        mod.setAddresses("www.game.cfm", Collections.singletonList(WEB));
        cfm.register("game.cfm", "cfm", Collections.singletonList(mod.address()));
        link.loop.advance(DnsServer.TTL_NEGATIVE * 1000);
        assertEquals(Collections.singletonList(WEB), aaaa("www.game.cfm"));
    }

    NameCache cache() {
        return new NameCache(new DnsResolver(client, RESOLVER), link.loop);
    }

    @Test
    void nameCacheAsksTheResolverAndHonorsTtl() throws Exception {
        NameCache names = cache();
        // 从没问过：先给“没有”，开始问（真的 DNS 查询，经递归解析器）
        assertTrue(names.addresses("www.shop.yr").isEmpty());
        link.loop.advance(5_000);
        assertTrue(names.addresses("www.shop.yr").isEmpty(), "查无此名");
        registerShop();
        // 否定回答 60 秒内不再问
        int before = asked.size();
        link.loop.advance(NameCache.NEGATIVE_RETRY_MS - 10_000);
        assertTrue(names.addresses("www.shop.yr").isEmpty());
        link.loop.advance(1_000);
        assertEquals(before, asked.size(), "没到时间不问");
        link.loop.advance(10_000);
        // 到期：先给上一次的，在后台问；回答来了换上
        assertTrue(names.addresses("www.shop.yr").isEmpty());
        link.loop.advance(5_000);
        assertEquals(Collections.singletonList(WEB), names.addresses("www.shop.yr"));
        // 改了地址：TTL（300 秒）内还是旧的，过了以后换成新的
        shopDns.setAddresses("www.shop.yr", Collections.singletonList(WEB2));
        link.loop.advance(DnsServer.TTL_RECORD * 1000 - 20_000);
        assertEquals(Collections.singletonList(WEB), names.addresses("www.shop.yr"));
        link.loop.advance(20_000);
        names.addresses("www.shop.yr");
        link.loop.advance(5_000);
        assertEquals(Collections.singletonList(WEB2), names.addresses("www.shop.yr"));
        // 删掉：过了 TTL 查不到
        shopDns.remove("www.shop.yr");
        link.loop.advance(DnsServer.TTL_RECORD * 1000);
        names.addresses("www.shop.yr");
        link.loop.advance(5_000);
        assertTrue(names.addresses("www.shop.yr").isEmpty());
    }

    @Test
    void nameCacheRefreshFuture() throws Exception {
        registerShop();
        NameCache names = cache();
        CompletableFuture<List<Ipv6Address>> f = names.refresh("www.shop.yr");
        assertTrue(f == names.refresh("WWW.shop.yr"), "同一个名字同时只问一次");
        link.loop.advance(5_000);
        assertEquals(Collections.singletonList(WEB), f.get());
        // 委派到不回答的地址：查询失败，当作没有
        yr.register("custom.yr", "m", Collections.singletonList(Ipv6Address.parse("fd00::99")));
        CompletableFuture<List<Ipv6Address>> g = names.refresh("custom.yr");
        link.loop.advance(30_000);
        assertTrue(g.isDone());
        assertTrue(g.get().isEmpty());
    }
}
