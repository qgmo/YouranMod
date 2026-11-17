package moe.qingu.youran.api.network.dns;

import moe.qingu.youran.api.network.Ipv6Address;
import moe.qingu.youran.api.network.packet.DnsMessage;
import moe.qingu.youran.api.network.stack.TestLink;
import moe.qingu.youran.network.DnsState;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.Arrays;
import java.util.Collection;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.atomic.AtomicLong;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** 默认注册局（先到先得、重名、只收一层、存档读写、whois）与顶级域登记处 */
class DefaultRegistrarTest {
    static final Ipv6Address TLD_NS = Ipv6Address.parse("fd00::2:53");
    static final Ipv6Address A = Ipv6Address.parse("fd00::a:53");
    static final Ipv6Address B = Ipv6Address.parse("fd00::b:53");

    @TempDir
    File dir;
    TestLink link;
    DnsServer tld;
    final AtomicLong clock = new AtomicLong(1_000);

    @BeforeEach
    void setUp() {
        DnsState.reset();
        link = new TestLink();
        tld = DnsServer.start(link.host(TLD_NS.toString()));
    }

    DefaultRegistrar yr() {
        return new DefaultRegistrar("youranmod", "yr", tld, new File(dir, "yr.json"), clock::get);
    }

    static List<Ipv6Address> one(Ipv6Address a) {
        return Collections.singletonList(a);
    }

    DnsMessage ask(String name) {
        link.loop.drain();
        return tld.answer(DnsMessage.query(1, name, DnsMessage.TYPE_AAAA, false));
    }

    @Test
    void firstComeFirstServed() {
        DefaultRegistrar yr = yr();
        Registration r = yr.register("minebay.yr", "cfm", one(A));
        assertEquals(Registration.Status.SUCCESS, r.status());
        assertEquals("minebay.yr", r.whois().name);
        assertEquals("cfm", r.whois().owner);
        assertSame(yr, r.whois().registrar);
        assertFalse(r.whois().registrarIdentity);
        assertEquals(one(A), r.whois().servers);
        assertEquals(1_000, r.whois().registeredAt);
        // 别的模组：重名，失败
        Registration dup = yr.register("MineBay.yr", "othermod", one(B));
        assertEquals(Registration.Status.FAILURE, dup.status());
        assertTrue(dup.reason().contains("cfm"), dup.reason());
        // 同一个模组再注册：更新 DNS 服务器，注册时间不变
        clock.set(5_000);
        Registration again = yr.register("minebay.yr", "cfm", one(B));
        assertEquals(Registration.Status.SUCCESS, again.status());
        assertEquals(one(B), yr.whois("minebay.yr").servers);
        assertEquals(1_000, yr.whois("minebay.yr").registeredAt);
        // 委派跟着换
        assertEquals(B, ask("www.minebay.yr").additional.get(0).address);
    }

    @Test
    void onlyOneLevelBelowTheZone() {
        DefaultRegistrar yr = yr();
        assertEquals(Registration.Status.FAILURE, yr.register("a.b.yr", "m", one(A)).status());
        assertEquals(Registration.Status.FAILURE, yr.register("example.com", "m", one(A)).status());
        assertEquals(Registration.Status.FAILURE, yr.register("yr", "m", one(A)).status());
        assertEquals(Registration.Status.FAILURE, yr.register("bad_name.yr", "m", one(A)).status());
        assertEquals(Registration.Status.FAILURE, yr.register("x.yr", "m", Collections.<Ipv6Address>emptyList()).status());
        assertEquals(Registration.Status.FAILURE, yr.register("x.yr", "m", one(Ipv6Address.parse("ff02::1"))).status());
        // 国际化域名：存 A-label
        assertEquals("xn--ddu228a.yr", yr.register("悠然.yr", "m", one(A)).whois().name);
        assertTrue(yr.list().size() == 1);
    }

    @Test
    void comOnlyTakesModIdentity() {
        DefaultRegistrar com = new DefaultRegistrar("youranmod", "com", tld, new File(dir, "com.json"), clock::get) {
            @Override
            protected String refuse(String name, String owner, boolean registrarIdentity) {
                return registrarIdentity ? ".com 只收以模组身份注册的名字" : null;
            }
        };
        Registrar someone = new DefaultRegistrar("player-shop", "wixsite.com", tld, new File(dir, "w.json"), clock::get);
        Registration refused = com.register("wixsite.com", someone, one(A));
        assertEquals(Registration.Status.FAILURE, refused.status());
        assertEquals(".com 只收以模组身份注册的名字", refused.reason());
        assertNull(com.whois("wixsite.com"));
        Registration ok = com.register("wixsite.com", "youranscp", one(A));
        assertEquals(Registration.Status.SUCCESS, ok.status());
        assertEquals("youranscp", com.whois("dado.wixsite.com").owner);
    }

    @Test
    void saveAndLoadRoundTrip() throws Exception {
        DefaultRegistrar yr = yr();
        yr.register("minebay.yr", "cfm", Arrays.asList(A, B));
        DefaultRegistrar sub = new DefaultRegistrar("shopmod", "shops.yr", tld, new File(dir, "shops.yr.json"), clock::get);
        clock.set(2_000);
        yr.register("shops.yr", sub, one(B));
        assertTrue(new File(dir, "yr.json").isFile());

        // 重启：新的 DNS 服务器、新的注册局对象，读回来
        DnsState.reset();
        TestLink link2 = new TestLink();
        DnsServer tld2 = DnsServer.start(link2.host(TLD_NS.toString()));
        DefaultRegistrar back = new DefaultRegistrar("youranmod", "yr", tld2, new File(dir, "yr.json"), clock::get);
        Whois w = back.whois("minebay.yr");
        assertEquals("cfm", w.owner);
        assertEquals(Arrays.asList(A, B), w.servers);
        assertEquals(1_000, w.registeredAt);
        Whois s = back.whois("shops.yr");
        assertEquals("shopmod", s.owner);
        assertTrue(s.registrarIdentity);
        assertNull(s.subRegistrar, "注册局对象存不下来，要申请人重新交");
        assertEquals(2_000, s.registeredAt);
        // 委派也恢复了
        link2.loop.drain();
        DnsMessage m = tld2.answer(DnsMessage.query(1, "minebay.yr", DnsMessage.TYPE_AAAA, false));
        assertEquals(2, m.additional.size());
        // 名字还是先到的
        assertEquals(Registration.Status.FAILURE, back.register("minebay.yr", "othermod", one(A)).status());
    }

    @Test
    void corruptFileIsKeptAside() throws Exception {
        File f = new File(dir, "yr.json");
        Files.write(f.toPath(), "{坏了".getBytes(StandardCharsets.UTF_8));
        DefaultRegistrar yr = yr();
        assertTrue(yr.list().isEmpty());
        assertFalse(f.exists());
        assertTrue(new File(dir, "yr.json.bad-1000").isFile());
        assertEquals(Registration.Status.SUCCESS, yr.register("a.yr", "m", one(A)).status());
    }

    @Test
    void whoisFollowsSubRegistrar() {
        DefaultRegistrar yr = yr();
        DefaultRegistrar shops = new DefaultRegistrar("shopmod", "shops.yr", tld, new File(dir, "shops.yr.json"), clock::get);
        assertEquals(Registration.Status.SUCCESS, yr.register("shops.yr", shops, one(B)).status());
        assertEquals(Registration.Status.SUCCESS, shops.register("alice.shops.yr", "alicemod", one(A)).status());
        Whois w = yr.whois("www.alice.shops.yr");
        assertEquals("alice.shops.yr", w.name);
        assertEquals("alicemod", w.owner);
        assertSame(shops, w.registrar);
        // 下级没注册的名字：给上一层的
        Whois up = yr.whois("bob.shops.yr");
        assertEquals("shops.yr", up.name);
        assertSame(shops, up.subRegistrar);
        assertNull(yr.whois("nobody.yr"));
    }

    @Test
    void tldRegistryAndRoot() {
        TestLink rootLink = link;
        DnsServer root = DnsServer.start(rootLink.host("fd00::1:53"));
        DnsState.bindRoot(root);
        DefaultRegistrar yr = yr();
        Registration r = DnsRegistry.register(".yr", yr, one(TLD_NS));
        assertEquals(Registration.Status.SUCCESS, r.status());
        assertEquals("youranmod", r.whois().owner);
        // 别的模组抢 .yr：失败；同一个模组再登记：更新
        Registrar other = new DefaultRegistrar("othermod", "yr", tld, new File(dir, "o.json"), clock::get);
        assertEquals(Registration.Status.FAILURE, DnsRegistry.register("yr", other, one(A)).status());
        assertEquals(Registration.Status.SUCCESS, DnsRegistry.register("yr", yr, one(TLD_NS)).status());
        assertThrows(IllegalArgumentException.class, () -> DnsRegistry.register("a.b", yr, one(A)));
        assertThrows(IllegalArgumentException.class, () -> DnsRegistry.register("x", yr, Collections.<Ipv6Address>emptyList()));
        assertEquals(Collections.singletonList("yr"), DnsRegistry.tlds());
        assertSame(yr, DnsRegistry.registrar("yr"));
        Whois t = DnsRegistry.whois("yr");
        assertSame(yr, t.registrar);
        assertEquals(one(TLD_NS), t.servers);
        // 二级域名的 whois 交给顶级域的注册局
        yr.register("shop.yr", "m", one(A));
        assertEquals("m", DnsRegistry.whois("www.shop.yr").owner);
        assertNull(DnsRegistry.whois("shop.nothing"));
        // 根区委派了 .yr
        rootLink.loop.drain();
        DnsMessage m = root.answer(DnsMessage.query(1, "shop.yr", DnsMessage.TYPE_AAAA, false));
        assertEquals("ns1.yr", m.authority.get(0).target);
        assertEquals(TLD_NS, m.additional.get(0).address);
        DnsMessage nx = root.answer(DnsMessage.query(1, "shop.nothing", DnsMessage.TYPE_AAAA, false));
        assertEquals(DnsMessage.RCODE_NXDOMAIN, nx.rcode);
    }

    /** 测试用：总是先“等待”的注册局（有审核流程） */
    static final class ReviewingRegistrar implements Registrar {
        Registration last;
        int withdrawn;

        @Override
        public String modId() {
            return "review";
        }

        @Override
        public Registration register(String name, Registrar registrar, Collection<Ipv6Address> servers) {
            return register(name, registrar.modId(), servers);
        }

        @Override
        public Registration register(String name, String modId, Collection<Ipv6Address> servers) {
            last = Registration.pending(() -> withdrawn++);
            return last;
        }

        @Override
        public Whois whois(String name) {
            return null;
        }
    }

    @Test
    void pendingRegistrarFromTheCallerSide() {
        ReviewingRegistrar rev = new ReviewingRegistrar();
        DnsRegistry.register("review", rev, one(A));
        Registration r = DnsRegistry.registrar("review").register("x.review", "m", one(B));
        assertEquals(Registration.Status.PENDING, r.status());
        String[] got = new String[1];
        r.whenDone(x -> got[0] = x.toString());
        rev.last.succeed(new Whois("x.review", rev, "m", false, null, one(B), 0));
        assertTrue(got[0].startsWith("成功"), got[0]);
        Registration r2 = DnsRegistry.registrar("review").register("y.review", "m", one(B));
        assertTrue(r2.cancel());
        assertEquals(1, rev.withdrawn);
    }
}
