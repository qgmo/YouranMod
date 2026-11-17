package moe.qingu.youran.server.net.nav;

import moe.qingu.youran.api.network.Ipv6Address;
import moe.qingu.youran.api.network.SiteInfo;
import moe.qingu.youran.api.network.dns.DefaultRegistrar;
import moe.qingu.youran.api.network.dns.DnsRegistry;
import moe.qingu.youran.api.network.dns.DnsServer;
import moe.qingu.youran.api.network.idyr.IdYrClient;
import moe.qingu.youran.api.network.stack.DnsResolver;
import moe.qingu.youran.api.network.stack.NameCache;
import moe.qingu.youran.api.network.stack.NetStack;
import moe.qingu.youran.api.network.stack.TestLink;
import moe.qingu.youran.network.DnsState;
import moe.qingu.youran.server.net.service.RecursiveResolver;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.File;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** 180.yr 只列解析得到的网站、id.yr 开没开：经递归解析器真的查（假时钟），按 TTL 刷新 */
class NavResolveTest {
    static final Ipv6Address RESOLVER = Ipv6Address.parse("fd00::53");
    static final Ipv6Address SITE = Ipv6Address.parse("fd00::80");

    @TempDir
    File dir;
    TestLink link;
    NetStack navStack;
    DefaultRegistrar yr;
    DnsServer siteDns;

    @BeforeEach
    void setUp() {
        DnsState.reset();
        link = new TestLink();
        navStack = link.host("fd00::6");
        DnsState.bindRoot(DnsServer.start(link.host("fd00::1:53")));
        DnsServer tld = DnsServer.start(link.host("fd00::2:53"));
        yr = new DefaultRegistrar("youranmod", "yr", tld, new File(dir, "yr.json"));
        DnsRegistry.register("yr", yr, Collections.singletonList(tld.address()));
        siteDns = DnsServer.start(link.host("fd00::c:53"));
        new RecursiveResolver(link.host(RESOLVER.toString()), DnsState.rootServers()).start();
        link.loop.drain();
    }

    void register(String name) {
        siteDns.addZone(name);
        siteDns.setAddresses(name, Collections.singletonList(SITE));
        yr.register(name, "somemod", Collections.singletonList(siteDns.address()));
    }

    static List<String> shown(NavDirectory d) {
        List<String> out = new ArrayList<>();
        for (NavDirectory.Site s : d.all()) {
            out.add(s.domain);
        }
        return out;
    }

    @Test
    void directoryFollowsResolverAfterTtl() {
        register("pay.yr");
        NameCache names = new NameCache(new DnsResolver(navStack, RESOLVER), navStack.loop());
        List<NavDirectory.Decl> decls = Arrays.asList(
                new NavDirectory.Decl("pay.yr", SiteInfo.of("pay.yr", SiteInfo.Category.SERVICE, ""), false),
                new NavDirectory.Decl("minebay.yr", SiteInfo.of("MineBay", SiteInfo.Category.SHOPPING, ""), false));
        NavDirectory d = NavDirectory.build(decls, new String[0], new String[0], names::resolves, new ArrayList<String>());
        // 开起来时问一遍；回答来之前什么都不列
        assertTrue(shown(d).isEmpty());
        link.loop.advance(5_000);
        assertEquals(Collections.singletonList("pay.yr"), shown(d));
        // MineBay 后来才注册：过了“查无此名”的重问间隔才出现
        register("minebay.yr");
        link.loop.advance(NameCache.NEGATIVE_RETRY_MS);
        shown(d);
        link.loop.advance(5_000);
        assertEquals(Arrays.asList("pay.yr", "minebay.yr"), shown(d));
        // 删了记录：过了 TTL 不再列
        siteDns.remove("minebay.yr");
        link.loop.advance(DnsServer.TTL_RECORD * 1000);
        shown(d);
        link.loop.advance(5_000);
        assertEquals(Collections.singletonList("pay.yr"), shown(d));
    }

    @Test
    void idYrAvailableComesFromDns() {
        IdYrClient id = new IdYrClient(navStack, new DnsResolver(navStack, RESOLVER), "id.yr", false, "platform.180.yr");
        assertFalse(id.available());
        link.loop.advance(5_000);
        assertFalse(id.available(), "id.yr 没注册");
        register("id.yr");
        link.loop.advance(NameCache.NEGATIVE_RETRY_MS);
        id.available();
        link.loop.advance(5_000);
        assertTrue(id.available());
        // 没有协议栈的客户端（只拼网址用）：false
        assertFalse(new IdYrClient(null, null, "id.yr", false, "x.yr").available());
    }
}
