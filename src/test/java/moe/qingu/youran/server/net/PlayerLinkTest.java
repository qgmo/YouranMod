package moe.qingu.youran.server.net;

import moe.qingu.youran.api.network.Ipv6Address;
import moe.qingu.youran.api.network.packet.Dhcpv6Message;
import moe.qingu.youran.api.network.packet.Icmpv6Message;
import moe.qingu.youran.api.network.packet.Ipv6Packet;
import moe.qingu.youran.api.network.packet.MalformedPacketException;
import moe.qingu.youran.api.network.packet.NdpMessage;
import moe.qingu.youran.api.network.packet.UdpDatagram;
import moe.qingu.youran.api.network.stack.NetStack;
import moe.qingu.youran.api.network.stack.PingResult;
import moe.qingu.youran.net.NicMessages;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Random;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 玩家链路（服主 11-27）：两头的链路本地地址与重复地址检测；RS → RA（M 位、默认路由器、MTU 1280）→ DHCPv6 四步拿到地址、DNS、NTP；
 * 拿到的地址过了重复地址检测才用；DHCPv6 报文坏了只丢不崩；Decline 以后换一个地址。客户端是真的 {@code ClientHostState}。
 */
class PlayerLinkTest {
    final TestNetwork net = new TestNetwork();
    final PlayerNics players = net.enablePlayers(new Random(3), 1000, 1000);
    /** 链路上的每一个包（方向、包） */
    final List<Object[]> wire = new ArrayList<>();

    PlayerLinkTest() {
        net.settle();
    }

    /** 上线一个玩家，记下链路上两个方向的包 */
    TestNetwork.Player tapped(String name) {
        TestNetwork.Player p = net.new Player(name, false);
        return p;
    }

    static Object describe(byte[] p) {
        try {
            Ipv6Packet ip = Ipv6Packet.parse(p);
            if (ip.nextHeader == Ipv6Packet.PROTO_ICMPV6) {
                Icmpv6Message m = Icmpv6Message.parse(ip);
                if (NdpMessage.isNdpType(m.type)) {
                    return NdpMessage.parse(ip, m);
                }
                return m;
            }
            if (ip.nextHeader == Ipv6Packet.PROTO_UDP) {
                UdpDatagram d = UdpDatagram.parse(ip);
                if (d.destinationPort == Dhcpv6Message.SERVER_PORT || d.destinationPort == Dhcpv6Message.CLIENT_PORT) {
                    return Dhcpv6Message.parse(d.data);
                }
            }
            return ip;
        } catch (MalformedPacketException e) {
            return e;
        }
    }

    @Test
    void raThenFourStepDhcpv6ThenDad() {
        // 在链路两头装上窃听：照 TestNetwork.Player 的接法再包一层
        List<Object[]> log = new ArrayList<>();
        UUID id = UUID.randomUUID();
        moe.qingu.youran.network.ClientHostState client = new moe.qingu.youran.network.ClientHostState(net.loop, new Random(21), m -> {
            if (m[0] == NicMessages.PACKET) {
                byte[] raw = NicMessages.packetBody(m);
                log.add(new Object[]{"上", raw, net.loop.nowMillis()});
                net.loop.execute(() -> players.fromPlayer(id, "Eve", raw));
            }
        });
        players.attach(id, "Eve", new PlayerNics.Link() {
            @Override
            public void deliver(byte[] packet) {
                log.add(new Object[]{"下", packet, net.loop.nowMillis()});
                net.loop.execute(() -> client.onMessage(NicMessages.packet(0, packet)));
            }

            @Override
            public void control(byte[] message) {
                net.loop.execute(() -> client.onMessage(message));
            }
        }, false);
        List<moe.qingu.youran.api.network.ClientHost.Port> configured = new ArrayList<>();
        net.loop.drain();
        assertNotNull(client.port(0), "上线就有 0 号网口");
        assertNull(client.port(0).address(), "还没有地址");
        client.port(0).iface().addListener(new NetStack.Interface.Listener() {
            @Override
            public void onConfigured(NetStack.Interface iface) {
                if (iface.address() != null) {
                    configured.add(client.port(0));
                }
            }
        });
        net.settle();
        Ipv6Address leased = client.port(0).address();
        assertNotNull(leased, "DHCPv6 拿到了地址");
        assertEquals(leased, players.address(id));
        assertTrue(net.layout.isPlayerAddress(leased));
        assertEquals(net.resolverAddr, client.port(0).resolverAddress());
        assertEquals(net.ntpAddr, client.port(0).ntpAddress());
        assertFalse(configured.isEmpty(), "通知了“网口配好了”");

        // 逐个看链路上的报文
        List<String> seq = new ArrayList<>();
        Ipv6Address clientLl = client.port(0).iface().linkLocalAddress();
        long dadDoneAt = -1;
        long firstUseAt = -1;
        long replyAt = -1;
        for (Object[] e : log) {
            byte[] p = (byte[]) e[1];
            long at = (Long) e[2];
            Object d = describe(p);
            Ipv6Address src = Ipv6Packet.sourceOf(p);
            if (d instanceof NdpMessage) {
                NdpMessage n = (NdpMessage) d;
                assertEquals(255, p[7] & 0xFF, "邻居发现报文跳数 255");
                if (n.type == NdpMessage.ROUTER_ADVERTISEMENT) {
                    assertEquals(PlayerNics.ROUTER_LINK_LOCAL, src, "RA 从路由器这头的链路本地地址发");
                    assertTrue(n.managed, "M 位：地址用 DHCPv6 要");
                    assertTrue(n.routerLifetime > 0, "当默认路由器（网关）");
                    assertEquals(1280, n.mtu);
                    assertTrue(n.prefixes.isEmpty(), "不带前缀：玩家的 /128 不在链路上");
                    seq.add("RA");
                } else if (n.type == NdpMessage.ROUTER_SOLICITATION) {
                    assertEquals(clientLl, src, "RS 从客户端的链路本地地址发（过了重复地址检测）");
                    seq.add("RS");
                } else if (n.type == NdpMessage.NEIGHBOR_SOLICITATION && src.isUnspecified() && leased.equals(n.target)) {
                    seq.add("DAD");
                    dadDoneAt = at + 1000;
                }
            } else if (d instanceof Dhcpv6Message) {
                Dhcpv6Message m = (Dhcpv6Message) d;
                seq.add(Dhcpv6Message.typeName(m.type));
                if (m.type == Dhcpv6Message.SOLICIT || m.type == Dhcpv6Message.REQUEST) {
                    assertEquals(clientLl, src);
                    assertEquals(Ipv6Address.ALL_DHCP_SERVERS, Ipv6Packet.destinationOf(p));
                    assertEquals(Arrays.asList(Dhcpv6Message.OPT_DNS_SERVERS, Dhcpv6Message.OPT_NTP_SERVER), m.requestedOptions);
                    assertEquals(1, m.iaNa.size());
                }
                if (m.type == Dhcpv6Message.ADVERTISE) {
                    assertEquals(255, m.preference);
                }
                if (m.type == Dhcpv6Message.REPLY) {
                    replyAt = at;
                    Dhcpv6Message.IaNa ia = m.iaNa.get(0);
                    assertEquals(Dhcpv6Message.INFINITY, ia.t1, "租约到下线为止：不用续");
                    assertEquals(Dhcpv6Message.INFINITY, ia.addresses.get(0).validLifetime);
                    assertEquals(leased, ia.addresses.get(0).address);
                    assertEquals(Collections.singletonList(net.resolverAddr), m.dnsServers, "DNS 选项（RFC 3646）");
                    assertEquals(Collections.singletonList(net.ntpAddr), m.ntpServers, "NTP 选项（RFC 5908）");
                }
            } else if (src.equals(leased) && firstUseAt < 0) {
                firstUseAt = at;
            }
        }
        assertTrue(seq.indexOf("RA") >= 0);
        int sol = seq.indexOf("Solicit");
        assertTrue(sol > seq.indexOf("RA"), "收到 RA（M 位）以后才开 DHCPv6：" + seq);
        assertEquals(Arrays.asList("Solicit", "Advertise", "Request", "Reply"), seq.subList(sol, sol + 4), "四步：" + seq);
        assertTrue(seq.indexOf("DAD") > sol + 3, "拿到的地址先做重复地址检测：" + seq);
        assertTrue(replyAt >= 0 && dadDoneAt > replyAt);

        // 地址能用以后才从它发包
        CompletableFuture<PingResult> f = client.stack().ping(null, net.address(RouterNetwork.PLAYERS), 8, 1000);
        net.loop.advance(1500);
        for (Object[] e : log) {
            byte[] p = (byte[]) e[1];
            if (Ipv6Packet.sourceOf(p).equals(leased) && !(describe(p) instanceof NdpMessage)) {
                assertTrue((Long) e[2] >= dadDoneAt, "过了重复地址检测才用这个地址");
            }
        }
        assertEquals(PingResult.Status.UNREACHABLE, f.join().status, "普通玩家 ping 不了路由器（防火墙）");
    }

    @Test
    void brokenDhcpv6IsDroppedNotFatal() {
        TestNetwork.Player p = net.new Player("Mallory", false);
        net.settle();
        Ipv6Address ll = p.port().iface().linkLocalAddress();
        long dropped = players.dhcp.dropped;
        Random rnd = new Random(1234);
        for (int i = 0; i < 2000; i++) {
            byte[] junk = new byte[rnd.nextInt(120)];
            rnd.nextBytes(junk);
            if (junk.length > 0 && i % 3 == 0) {
                junk[0] = (byte) (1 + rnd.nextInt(11));
            }
            byte[] pkt = new UdpDatagram(Dhcpv6Message.CLIENT_PORT, Dhcpv6Message.SERVER_PORT, junk).toPacket(ll, Ipv6Address.ALL_DHCP_SERVERS).encode();
            players.fromPlayer(p.id, p.name, pkt);
        }
        net.loop.advance(100);
        assertTrue(players.dhcp.dropped > dropped, "坏报文丢掉了");
        // 别人照样能拿到地址，他自己的地址也还在
        TestNetwork.Player q = net.online("Victor");
        assertNotNull(q.address());
        assertNotNull(p.address());
        assertNotEquals(p.address(), q.address());
    }

    @Test
    void declinedAddressIsReplaced() throws Exception {
        TestNetwork.Player p = net.online("Dupe");
        Ipv6Address first = p.address();
        PlayerNics.PlayerLink link = players.link(p.id);
        // 客户端说这个地址重复（Decline）：服务器收回绑定、去掉路由，这次在线期间不再给他这个地址
        byte[] duid = p.port().iface().dhcp().duid();
        Dhcpv6Message decline = new Dhcpv6Message(Dhcpv6Message.DECLINE, 0x123456, duid, players.dhcp.duid(),
                Collections.singletonList(new Dhcpv6Message.IaNa(1, 0, 0,
                        Collections.singletonList(new Dhcpv6Message.IaAddress(first, 0, 0, -1)), -1)),
                Collections.<Integer>emptyList(), -1, 0, -1, null, Collections.<Ipv6Address>emptyList(), Collections.<Ipv6Address>emptyList());
        Ipv6Address ll = p.port().iface().linkLocalAddress();
        players.fromPlayer(p.id, p.name, new UdpDatagram(Dhcpv6Message.CLIENT_PORT, Dhcpv6Message.SERVER_PORT, decline.encode())
                .toPacket(ll, Ipv6Address.ALL_DHCP_SERVERS).encode());
        net.loop.drain();
        assertNull(players.address(p.id), "收回了");
        assertTrue(link.binding.declined.contains(first));
        assertNull(link.routed);
    }

    @Test
    void neighborDiscoveryRunsOnThePlayerLink() {
        TestNetwork.Player p = net.online("Neo");
        PlayerNics.PlayerLink link = players.link(p.id);
        // 路由器这头解析过客户端（发包给他的地址前先 NS），客户端这头也认得路由器（RA 带的、NS / NA）
        CompletableFuture<PingResult> f = new CompletableFuture<>();
        net.host(net.resolverAddr);
        net.settle();
        assertTrue(link.nd.isUsable(PlayerNics.ROUTER_LINK_LOCAL), "路由器这头的链路本地地址过了重复地址检测");
        assertEquals(1, p.port().iface().neighborDiscovery().defaultRouters().size());
        assertEquals(PlayerNics.ROUTER_LINK_LOCAL, p.port().iface().neighborDiscovery().defaultRouters().get(0));
        f.complete(null);
    }
}
