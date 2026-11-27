package moe.qingu.youran.network;

import moe.qingu.youran.api.network.Ipv6Address;
import moe.qingu.youran.api.network.packet.Dhcpv6Message;
import moe.qingu.youran.api.network.packet.Icmpv6Message;
import moe.qingu.youran.api.network.packet.Ipv6Packet;
import moe.qingu.youran.api.network.packet.MalformedPacketException;
import moe.qingu.youran.api.network.packet.NdpMessage;
import moe.qingu.youran.api.network.packet.UdpDatagram;
import moe.qingu.youran.api.network.stack.EventLoop;
import moe.qingu.youran.api.network.stack.NeighborDiscovery;
import moe.qingu.youran.net.NicMessages;

import java.util.Collections;
import java.util.Random;
import java.util.function.Consumer;

/**
 * 测试用的玩家链路服务器那头（只用公开的报文与邻居发现类，不用服务端的实现）：发 RA（M 位）、回 RS 与 NS、
 * DHCPv6 回 Advertise / Reply（固定给 {@code address}、DNS、NTP）。链路上的包自己收下，别的交还给调用方。
 */
final class FakePlayerRouter {
    static final Ipv6Address LINK_LOCAL = Ipv6Address.linkLocal(1);
    final NeighborDiscovery nd;
    private final Ipv6Address address;
    private final Ipv6Address dns;
    private final Ipv6Address ntp;
    private final Consumer<byte[]> toClient;
    private final byte[] duid = Dhcpv6Message.uuidDuid(1, 2);
    int dhcpMessages;

    FakePlayerRouter(EventLoop loop, Random random, Ipv6Address address, Ipv6Address dns, Ipv6Address ntp, Consumer<byte[]> toClientMessage) {
        this.address = address;
        this.dns = dns;
        this.ntp = ntp;
        this.toClient = p -> loop.execute(() -> toClientMessage.accept(NicMessages.packet(NicMessages.PLAYER_PORT, p)));
        nd = new NeighborDiscovery(loop, random, (p, dst) -> toClient.accept(p), NdpMessage.NO_LINK_ADDRESS, true, null);
        nd.join(Ipv6Address.ALL_DHCP_SERVERS);
        nd.addAddress(LINK_LOCAL);
        nd.advertise(new NeighborDiscovery.Advertising(true, false, 1800, Collections.<NdpMessage.Prefix>emptyList(), 1280,
                Collections.<NdpMessage.Rdnss>emptyList(), 198_000, 600_000));
    }

    /** 服务器要发给玩家的包：在链路上解析邻居再交过去 */
    void toClient(byte[] packet) {
        nd.send(packet, Ipv6Packet.destinationOf(packet));
    }

    /** 客户端 0 号网口发来的包；是链路上的（邻居发现、DHCPv6）就自己收下并返回 true */
    boolean fromClient(byte[] raw) {
        Ipv6Packet p;
        try {
            p = Ipv6Packet.parse(raw);
        } catch (MalformedPacketException e) {
            return true;
        }
        if (!p.destination.isLinkScoped() && !(p.nextHeader == Ipv6Packet.PROTO_ICMPV6 && raw.length > 40 && NdpMessage.isNdpType(raw[40] & 0xFF))) {
            return false;
        }
        if (!nd.accepts(p.destination)) {
            return true;
        }
        try {
            if (p.nextHeader == Ipv6Packet.PROTO_ICMPV6) {
                nd.handle(p, Icmpv6Message.parse(p));
            } else if (p.nextHeader == Ipv6Packet.PROTO_UDP) {
                UdpDatagram d = UdpDatagram.parse(p);
                if (d.destinationPort == Dhcpv6Message.SERVER_PORT) {
                    dhcp(p, Dhcpv6Message.parse(d.data));
                }
            }
        } catch (MalformedPacketException e) {
            // 丢掉
        }
        return true;
    }

    private void dhcp(Ipv6Packet p, Dhcpv6Message m) {
        dhcpMessages++;
        int type = m.type == Dhcpv6Message.SOLICIT ? Dhcpv6Message.ADVERTISE : Dhcpv6Message.REPLY;
        Dhcpv6Message.IaNa ia = new Dhcpv6Message.IaNa(m.iaNa.get(0).iaid, Dhcpv6Message.INFINITY, Dhcpv6Message.INFINITY,
                Collections.singletonList(new Dhcpv6Message.IaAddress(address, Dhcpv6Message.INFINITY, Dhcpv6Message.INFINITY, -1)), -1);
        Dhcpv6Message r = new Dhcpv6Message(type, m.transactionId, m.clientId, duid, Collections.singletonList(ia), Collections.<Integer>emptyList(),
                255, -1, -1, null, Collections.singletonList(dns), Collections.singletonList(ntp));
        byte[] packet = new UdpDatagram(Dhcpv6Message.SERVER_PORT, Dhcpv6Message.CLIENT_PORT, r.encode()).toPacket(LINK_LOCAL, p.source).encode();
        nd.send(packet, p.source);
    }
}
