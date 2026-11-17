package moe.qingu.youran.api.network.dns;

import moe.qingu.youran.api.network.Ipv6Address;
import moe.qingu.youran.api.network.packet.DnsMessage;
import moe.qingu.youran.api.network.stack.TestLink;
import moe.qingu.youran.network.DnsState;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** 默认权威 DNS 服务：AAAA、TXT、区顶 NS / SOA、委派、查无此名、运行中改记录（攒一批再换） */
class DnsServerTest {
    static final Ipv6Address SELF = Ipv6Address.parse("fd00::53");
    static final Ipv6Address WEB = Ipv6Address.parse("fd00::80");
    static final Ipv6Address CHILD = Ipv6Address.parse("fd00::1:53");
    static final Ipv6Address CHILD2 = Ipv6Address.parse("fd00::2:53");

    TestLink link;
    DnsServer dns;

    @BeforeEach
    void setUp() {
        DnsState.reset();
        link = new TestLink();
        dns = DnsServer.start(link.host(SELF.toString()));
        dns.addZone("shop.yr");
        dns.setAddresses("shop.yr", Collections.singletonList(WEB));
        dns.setAddresses("www.shop.yr", Arrays.asList(WEB, CHILD));
        link.loop.drain();
    }

    DnsMessage ask(String name, int type) {
        return dns.answer(DnsMessage.query(7, name, type, false));
    }

    static List<String> texts(DnsMessage m) {
        List<String> out = new ArrayList<>();
        for (DnsMessage.Record r : m.answers) {
            if (r.type == DnsMessage.TYPE_TXT) {
                out.add(r.text());
            }
        }
        return out;
    }

    @Test
    void aaaaAnswers() {
        DnsMessage m = ask("WWW.shop.yr.", DnsMessage.TYPE_AAAA);
        assertEquals(DnsMessage.RCODE_OK, m.rcode);
        assertTrue(m.authoritative);
        assertEquals(2, m.answers.size());
        assertEquals(WEB, m.answers.get(0).address);
        assertEquals(DnsServer.TTL_RECORD, m.answers.get(0).ttl);
    }

    @Test
    void apexHasGeneratedNsAndSoa() {
        DnsMessage ns = ask("shop.yr", DnsMessage.TYPE_NS);
        assertEquals("ns1.shop.yr", ns.answers.get(0).target);
        assertEquals(SELF, ns.additional.get(0).address);
        DnsMessage soa = ask("shop.yr", DnsMessage.TYPE_SOA);
        assertEquals(DnsMessage.TYPE_SOA, soa.answers.get(0).type);
        assertEquals("ns1.shop.yr", soa.answers.get(0).soa.mname);
        // 名字服务器的名字在区里也能查到
        assertEquals(SELF, ask("ns1.shop.yr", DnsMessage.TYPE_AAAA).answers.get(0).address);
    }

    @Test
    void nxdomainNodataAndRefused() {
        DnsMessage nx = ask("nothing.shop.yr", DnsMessage.TYPE_AAAA);
        assertEquals(DnsMessage.RCODE_NXDOMAIN, nx.rcode);
        assertEquals(DnsMessage.TYPE_SOA, nx.authority.get(0).type);
        assertEquals(DnsServer.TTL_NEGATIVE, nx.authority.get(0).ttl);
        // 名字在、没有这种记录
        DnsMessage nodata = ask("www.shop.yr", DnsMessage.TYPE_TXT);
        assertEquals(DnsMessage.RCODE_OK, nodata.rcode);
        assertTrue(nodata.answers.isEmpty());
        assertEquals(DnsMessage.TYPE_SOA, nodata.authority.get(0).type);
        // 不在管的区里
        assertEquals(DnsMessage.RCODE_REFUSED, ask("other.yr", DnsMessage.TYPE_AAAA).rcode);
        // 应答报文不回
        assertNull(dns.answer(nx));
    }

    @Test
    void txtRecordsAddAndRemoveAtRuntime() {
        String challenge = "_acme-challenge.shop.yr";
        assertEquals(DnsMessage.RCODE_NXDOMAIN, ask(challenge, DnsMessage.TYPE_TXT).rcode);
        dns.setTexts(challenge, Arrays.asList("token-1", "token-2"));
        dns.setTexts("shop.yr", Collections.singletonList("v=site1"));
        // 还没换上去
        assertEquals(DnsMessage.RCODE_NXDOMAIN, ask(challenge, DnsMessage.TYPE_TXT).rcode);
        link.loop.drain();
        DnsMessage m = ask(challenge, DnsMessage.TYPE_TXT);
        assertEquals(Arrays.asList("token-1", "token-2"), texts(m));
        assertEquals(DnsServer.TTL_RECORD, m.answers.get(0).ttl);
        // 区顶的 TXT 与 AAAA 并存
        assertEquals(Collections.singletonList("v=site1"), texts(ask("shop.yr", DnsMessage.TYPE_TXT)));
        assertEquals(WEB, ask("shop.yr", DnsMessage.TYPE_AAAA).answers.get(0).address);
        // ANY 都给
        assertEquals(4, ask("shop.yr", DnsMessage.TYPE_ANY).answers.size(), "NS、SOA、AAAA、TXT");
        // 删掉
        dns.setTexts(challenge, Collections.<String>emptyList());
        link.loop.drain();
        assertEquals(DnsMessage.RCODE_NXDOMAIN, ask(challenge, DnsMessage.TYPE_TXT).rcode);
    }

    @Test
    void longTxtSurvivesEncoding() throws Exception {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < 300; i++) {
            sb.append((char) ('a' + i % 26));
        }
        String text = sb + "中文";
        dns.setTexts("t.shop.yr", Collections.singletonList(text));
        link.loop.drain();
        DnsMessage m = ask("t.shop.yr", DnsMessage.TYPE_TXT);
        // 报文编码、解码一遍（超过 255 字节切成几段字符串）
        DnsMessage back = DnsMessage.parse(m.encode());
        assertEquals(Collections.singletonList(text), texts(back));
    }

    @Test
    void delegationGivesReferralWithGlue() {
        dns.delegate("sub.shop.yr", Arrays.asList(CHILD, CHILD2));
        link.loop.drain();
        DnsMessage m = ask("a.sub.shop.yr", DnsMessage.TYPE_AAAA);
        assertEquals(DnsMessage.RCODE_OK, m.rcode);
        assertFalse(m.authoritative);
        assertTrue(m.answers.isEmpty());
        assertEquals(2, m.authority.size());
        assertEquals("ns1.sub.shop.yr", m.authority.get(0).target);
        assertEquals("ns2.sub.shop.yr", m.authority.get(1).target);
        assertEquals(CHILD, m.additional.get(0).address);
        assertEquals(CHILD2, m.additional.get(1).address);
        assertEquals(DnsServer.TTL_NS, m.authority.get(0).ttl);
        // 委派下面不能直接设记录；区顶不能委派
        assertThrows(IllegalArgumentException.class, () -> dns.setAddresses("x.sub.shop.yr", Collections.singletonList(WEB)));
        assertThrows(IllegalArgumentException.class, () -> dns.delegate("shop.yr", Collections.singletonList(CHILD)));
        // 取消委派
        dns.delegate("sub.shop.yr", Collections.<Ipv6Address>emptyList());
        link.loop.drain();
        assertEquals(DnsMessage.RCODE_NXDOMAIN, ask("a.sub.shop.yr", DnsMessage.TYPE_AAAA).rcode);
    }

    @Test
    void emptyNonTerminalExists() {
        dns.setAddresses("a.b.shop.yr", Collections.singletonList(WEB));
        link.loop.drain();
        DnsMessage m = ask("b.shop.yr", DnsMessage.TYPE_AAAA);
        assertEquals(DnsMessage.RCODE_OK, m.rcode);
        assertTrue(m.answers.isEmpty());
    }

    @Test
    void changesArePublishedTogether() {
        dns.setAddresses("shop.yr", Collections.singletonList(CHILD));
        dns.remove("www.shop.yr");
        // 换上去之前还是旧的
        assertEquals(WEB, ask("shop.yr", DnsMessage.TYPE_AAAA).answers.get(0).address);
        assertEquals(DnsMessage.RCODE_OK, ask("www.shop.yr", DnsMessage.TYPE_AAAA).rcode);
        assertEquals(1, link.loop.pending(), "两处改动只排了一次换数据");
        link.loop.drain();
        assertEquals(CHILD, ask("shop.yr", DnsMessage.TYPE_AAAA).answers.get(0).address);
        assertEquals(DnsMessage.RCODE_NXDOMAIN, ask("www.shop.yr", DnsMessage.TYPE_AAAA).rcode);
    }

    @Test
    void namesAndZones() {
        // 记录名可以带下划线，Unicode 转 A-label
        dns.addZone("悠然.yr");
        dns.setAddresses("商店.悠然.yr", Collections.singletonList(WEB));
        link.loop.drain();
        assertEquals(WEB, ask("xn--czrs0t.xn--ddu228a.yr", DnsMessage.TYPE_AAAA).answers.get(0).address);
        assertThrows(IllegalArgumentException.class, () -> dns.setAddresses("other.yr", Collections.singletonList(WEB)));
        assertThrows(IllegalArgumentException.class, () -> dns.setTexts("bad..shop.yr", Collections.singletonList("x")));
        assertThrows(IllegalArgumentException.class, () -> dns.delegate("sub.shop.yr", Collections.singletonList(Ipv6Address.parse("ff02::1"))));
        dns.removeZone("悠然.yr");
        link.loop.drain();
        assertEquals(DnsMessage.RCODE_REFUSED, ask("xn--czrs0t.xn--ddu228a.yr", DnsMessage.TYPE_AAAA).rcode);
        assertEquals(Collections.singletonList("shop.yr"), dns.zones());
    }
}
