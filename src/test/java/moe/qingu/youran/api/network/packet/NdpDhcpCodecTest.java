package moe.qingu.youran.api.network.packet;

import moe.qingu.youran.api.network.Ipv6Address;
import org.junit.jupiter.api.Test;

import java.util.Arrays;
import java.util.Collections;
import java.util.Random;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** 邻居发现（RFC 4861）、DHCPv6（RFC 8415、3646、5908）、EDNS(0)（RFC 6891）报文的编解码与验证 */
class NdpDhcpCodecTest {
    static final Ipv6Address LL = Ipv6Address.parse("fe80::1");
    static final Ipv6Address A = Ipv6Address.parse("fd00::a");

    static NdpMessage roundTrip(Ipv6Packet p) throws MalformedPacketException {
        Ipv6Packet back = Ipv6Packet.parse(p.encode());
        return NdpMessage.parse(back, Icmpv6Message.parse(back));
    }

    @Test
    void addressHelpers() {
        assertEquals(Ipv6Address.parse("ff02::1:ff00:a"), A.solicitedNode());
        assertTrue(LL.isLinkLocal());
        assertFalse(A.isLinkLocal());
        assertTrue(Ipv6Address.ALL_NODES.isLinkScoped());
        assertEquals(2, Ipv6Address.ALL_DHCP_SERVERS.multicastScope());
        assertEquals(Ipv6Address.parse("ff02::1:2"), Ipv6Address.ALL_DHCP_SERVERS);
        assertEquals(Ipv6Address.parse("fe80::200:ff:fe00:1"), Ipv6Address.linkLocal(moe.qingu.youran.api.network.stack.NeighborDiscovery.eui64(0x000000000001L)));
    }

    @Test
    void routerAdvertisementRoundTrip() throws Exception {
        NdpMessage ra = NdpMessage.routerAdvertisement(64, true, false, 1800, 0, 0, 0x020000000001L,
                Collections.singletonList(new NdpMessage.Prefix(Ipv6Address.parse("fd00::"), 96, true, false, NdpMessage.INFINITE, NdpMessage.INFINITE)),
                1280, Collections.singletonList(new NdpMessage.Rdnss(1200, Arrays.asList(A, Ipv6Address.parse("fd00::b")))));
        Ipv6Packet p = ra.toPacket(LL, Ipv6Address.ALL_NODES);
        assertEquals(255, p.hopLimit);
        NdpMessage back = roundTrip(p);
        assertEquals(NdpMessage.ROUTER_ADVERTISEMENT, back.type);
        assertTrue(back.managed);
        assertFalse(back.otherConfig);
        assertEquals(1800, back.routerLifetime);
        assertEquals(1280, back.mtu);
        assertEquals(0x020000000001L, back.sourceLinkAddress);
        assertEquals(96, back.prefixes.get(0).length);
        assertTrue(back.prefixes.get(0).onLink);
        assertFalse(back.prefixes.get(0).autonomous);
        assertEquals(NdpMessage.INFINITE, back.prefixes.get(0).validLifetime);
        assertEquals(Arrays.asList(A, Ipv6Address.parse("fd00::b")), back.rdnss.get(0).servers);
    }

    @Test
    void validationRules() throws Exception {
        // 跳数不是 255
        Ipv6Packet ns = NdpMessage.neighborSolicitation(A, NdpMessage.NO_LINK_ADDRESS).toPacket(Ipv6Address.UNSPECIFIED, A.solicitedNode());
        assertEquals(A, roundTrip(ns).target);
        Ipv6Packet hop64 = ns.withHopLimit(64);
        assertThrows(MalformedPacketException.class, () -> roundTrip(hop64));
        // RA 不是从链路本地地址发的
        Ipv6Packet ra = NdpMessage.routerAdvertisement(64, false, false, 1800, 0, 0, NdpMessage.NO_LINK_ADDRESS,
                Collections.<NdpMessage.Prefix>emptyList(), -1, Collections.<NdpMessage.Rdnss>emptyList()).toPacket(A, Ipv6Address.ALL_NODES);
        assertThrows(MalformedPacketException.class, () -> roundTrip(ra));
        // 源地址未指定的 NS：不能带源链路层地址、要发给请求节点组播地址
        assertThrows(MalformedPacketException.class, () -> roundTrip(NdpMessage.neighborSolicitation(A, 5).toPacket(Ipv6Address.UNSPECIFIED, A.solicitedNode())));
        assertThrows(MalformedPacketException.class, () -> roundTrip(NdpMessage.neighborSolicitation(A, NdpMessage.NO_LINK_ADDRESS).toPacket(Ipv6Address.UNSPECIFIED, Ipv6Address.ALL_NODES)));
        // 发给组播地址的 NA 不能置 S 位；目标不能是组播
        assertThrows(MalformedPacketException.class, () -> roundTrip(NdpMessage.neighborAdvertisement(A, false, true, true, NdpMessage.NO_LINK_ADDRESS).toPacket(A, Ipv6Address.ALL_NODES)));
        assertThrows(MalformedPacketException.class, () -> roundTrip(NdpMessage.neighborAdvertisement(Ipv6Address.ALL_NODES, false, false, true, NdpMessage.NO_LINK_ADDRESS).toPacket(A, Ipv6Address.ALL_NODES)));
        // 选项长度为 0
        byte[] raw = NdpMessage.neighborSolicitation(A, 7).toPacket(LL, A.solicitedNode()).encode();
        raw[40 + 24 + 1] = 0;
        Ipv6Packet bad = Ipv6Packet.parse(raw);
        assertThrows(MalformedPacketException.class, () -> NdpMessage.parse(bad, new Icmpv6Message(135, 0, Arrays.copyOfRange(raw, 44, raw.length))));
    }

    @Test
    void dhcpv6RoundTrip() throws Exception {
        byte[] client = Dhcpv6Message.uuidDuid(1, 2);
        byte[] server = Dhcpv6Message.uuidDuid(3, 4);
        Dhcpv6Message reply = new Dhcpv6Message(Dhcpv6Message.REPLY, 0xABCDEF, client, server,
                Collections.singletonList(new Dhcpv6Message.IaNa(1, Dhcpv6Message.INFINITY, Dhcpv6Message.INFINITY,
                        Collections.singletonList(new Dhcpv6Message.IaAddress(A, Dhcpv6Message.INFINITY, Dhcpv6Message.INFINITY, -1)), -1)),
                Arrays.asList(23, 56), 255, 100, Dhcpv6Message.STATUS_SUCCESS, "ok", Collections.singletonList(Ipv6Address.parse("fd00::53")),
                Collections.singletonList(Ipv6Address.parse("fd00::123")));
        Dhcpv6Message back = Dhcpv6Message.parse(reply.encode());
        assertEquals(Dhcpv6Message.REPLY, back.type);
        assertEquals(0xABCDEF, back.transactionId);
        assertArrayEquals(client, back.clientId);
        assertArrayEquals(server, back.serverId);
        assertEquals(A, back.iaNa.get(0).addresses.get(0).address);
        assertEquals(Dhcpv6Message.INFINITY, back.iaNa.get(0).t1);
        assertEquals(Arrays.asList(23, 56), back.requestedOptions);
        assertEquals(255, back.preference);
        assertEquals(100, back.elapsedTime);
        assertEquals("ok", back.statusMessage);
        assertEquals(Collections.singletonList(Ipv6Address.parse("fd00::53")), back.dnsServers);
        assertEquals(Collections.singletonList(Ipv6Address.parse("fd00::123")), back.ntpServers);
    }

    @Test
    void brokenDhcpv6IsRejectedNotFatal() {
        Random r = new Random(5);
        int rejected = 0;
        for (int i = 0; i < 5000; i++) {
            byte[] b = new byte[r.nextInt(80)];
            r.nextBytes(b);
            try {
                Dhcpv6Message.parse(b);
            } catch (MalformedPacketException e) {
                rejected++;
            }
        }
        assertTrue(rejected > 1000, "大多数乱码不合格（抛的是 MalformedPacketException，没有别的异常）");
        assertThrows(MalformedPacketException.class, () -> Dhcpv6Message.parse(new byte[]{1, 0, 0}));
        assertThrows(MalformedPacketException.class, () -> Dhcpv6Message.parse(new byte[]{12, 0, 0, 0}), "中继报文不认");
    }

    @Test
    void ednsRoundTrip() throws Exception {
        DnsMessage q = DnsMessage.query(7, "test.yr", DnsMessage.TYPE_AAAA, true).withEdns(DnsMessage.Edns.standard());
        DnsMessage back = DnsMessage.parse(q.encode());
        assertEquals(DnsMessage.EDNS_PAYLOAD, back.edns.payloadSize);
        assertTrue(back.additional.isEmpty(), "OPT 不算在附加区的记录里");
        assertEquals(1232, DnsMessage.udpLimitFor(back));
        assertEquals(512, DnsMessage.udpLimitFor(DnsMessage.query(1, "a.yr", 1, true)));
        assertEquals(512, DnsMessage.udpLimitFor(DnsMessage.query(1, "a.yr", 1, true).withEdns(new DnsMessage.Edns(100, 0, 0, false, null))), "小于 512 的按 512");
        assertEquals(1232, DnsMessage.udpLimitFor(DnsMessage.query(1, "a.yr", 1, true).withEdns(new DnsMessage.Edns(4096, 0, 0, false, null))), "最多 1232");
        // 扩展响应码
        DnsMessage bad = DnsMessage.reply(back, false, false, 0, Collections.<DnsMessage.Record>emptyList(), Collections.<DnsMessage.Record>emptyList(),
                Collections.<DnsMessage.Record>emptyList()).withEdns(DnsMessage.Edns.standard()).withRcode(DnsMessage.RCODE_BADVERS);
        DnsMessage badBack = DnsMessage.parse(bad.encode());
        assertEquals(DnsMessage.RCODE_BADVERS, badBack.fullRcode());
        assertEquals(0, badBack.rcode);
        // 两个 OPT：不合格
        DnsMessage plain = DnsMessage.query(9, "x.yr", 1, true);
        byte[] twice = new DnsMessage(9, false, 0, false, false, true, false, 0, plain.questions, plain.answers, plain.authority,
                Collections.singletonList(DnsMessage.Record.raw("", DnsMessage.TYPE_OPT, 0, new byte[0])), DnsMessage.Edns.standard(), false).encode();
        assertTrue(DnsMessage.parse(twice).ednsMalformed);
        assertNull(DnsMessage.parse(plain.encode()).edns);
        // TCP 的长度前缀
        byte[] f = DnsMessage.tcpFrame(new byte[]{1, 2, 3});
        assertArrayEquals(new byte[]{0, 3, 1, 2, 3}, f);
    }
}
