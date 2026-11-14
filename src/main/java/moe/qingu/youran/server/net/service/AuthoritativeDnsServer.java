package moe.qingu.youran.server.net.service;

import moe.qingu.youran.api.network.Ipv6Address;
import moe.qingu.youran.api.network.packet.DnsMessage;
import moe.qingu.youran.api.network.packet.MalformedPacketException;
import moe.qingu.youran.api.network.stack.NetStack;
import moe.qingu.youran.api.network.stack.UdpSocket;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * 权威 DNS 服务器（UDP 53）：根服务器、YouranMod 的 .yr / .com 服务器、替其他模组跑的顶级域服务器都是它。
 * 不递归（RA=0）；问的名字不在它的区里时回 REFUSED。
 */
public final class AuthoritativeDnsServer implements UdpSocket.Handler {
    private final List<DnsZoneData> zones;

    public AuthoritativeDnsServer(List<DnsZoneData> zones) {
        this.zones = Collections.unmodifiableList(new ArrayList<>(zones));
    }

    public void start(NetStack stack) {
        stack.openUdp(null, DnsMessage.PORT, this);
    }

    @Override
    public void onDatagram(UdpSocket socket, Ipv6Address from, int fromPort, Ipv6Address to, byte[] data) {
        DnsMessage reply = handle(data);
        if (reply != null) {
            socket.send(to, from, fromPort, reply.encodeForUdp());
        }
    }

    /** 算应答（纯逻辑，测试用）；不该回的（应答报文、解析不了的）返回 null */
    DnsMessage handle(byte[] data) {
        DnsMessage q;
        try {
            q = DnsMessage.parse(data);
        } catch (MalformedPacketException e) {
            return null;
        }
        if (q.response) {
            return null;
        }
        List<DnsMessage.Record> none = Collections.emptyList();
        if (q.opcode != 0) {
            return DnsMessage.reply(q, false, false, DnsMessage.RCODE_NOTIMP, none, none, none);
        }
        if (q.questions.size() != 1) {
            return DnsMessage.reply(q, false, false, DnsMessage.RCODE_FORMERR, none, none, none);
        }
        DnsMessage.Question question = q.question();
        if (question.qclass != DnsMessage.CLASS_IN) {
            return DnsMessage.reply(q, false, false, DnsMessage.RCODE_REFUSED, none, none, none);
        }
        DnsZoneData zone = null;
        for (DnsZoneData z : zones) {
            if (DnsMessage.isUnder(question.name, z.apex) && (zone == null || z.apex.length() > zone.apex.length())) {
                zone = z;
            }
        }
        if (zone == null) {
            return DnsMessage.reply(q, false, false, DnsMessage.RCODE_REFUSED, none, none, none);
        }
        return zone.answer(q);
    }
}
