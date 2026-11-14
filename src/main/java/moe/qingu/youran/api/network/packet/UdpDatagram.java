package moe.qingu.youran.api.network.packet;

import moe.qingu.youran.api.network.Ipv6Address;

import java.util.Arrays;

/** UDP 数据报（RFC 768；IPv6 下校验和必填，RFC 8200 第 8.1 节） */
public final class UdpDatagram {
    public static final int HEADER = 8;
    /** 一个包里 UDP 数据最多这么多（1280 − 40 − 8） */
    public static final int MAX_DATA = Ipv6Packet.MAX_PAYLOAD - HEADER;

    public final int sourcePort;
    public final int destinationPort;
    public final byte[] data;

    public UdpDatagram(int sourcePort, int destinationPort, byte[] data) {
        this.sourcePort = sourcePort & 0xFFFF;
        this.destinationPort = destinationPort & 0xFFFF;
        this.data = data;
    }

    /** 长度字段与实际不符、校验和为 0（IPv6 不许）或不对时算坏包 */
    public static UdpDatagram parse(Ipv6Packet p) throws MalformedPacketException {
        byte[] b = p.payload;
        if (b.length < HEADER) {
            throw new MalformedPacketException("UDP 不到 8 字节");
        }
        int len = Bytes.u16(b, 4);
        if (len < HEADER || len != b.length) {
            throw new MalformedPacketException("UDP 长度字段 " + len + " 与实际 " + b.length + " 不符");
        }
        if (Bytes.u16(b, 6) == 0) {
            throw new MalformedPacketException("UDP 校验和为 0（IPv6 下必须填）");
        }
        if (!Checksum.verify(p.source, p.destination, Ipv6Packet.PROTO_UDP, b, 0, b.length)) {
            throw new MalformedPacketException("UDP 校验和不对");
        }
        return new UdpDatagram(Bytes.u16(b, 0), Bytes.u16(b, 2), Arrays.copyOfRange(b, HEADER, b.length));
    }

    public byte[] encode(Ipv6Address src, Ipv6Address dst) {
        byte[] b = new byte[HEADER + data.length];
        Bytes.put16(b, 0, sourcePort);
        Bytes.put16(b, 2, destinationPort);
        Bytes.put16(b, 4, b.length);
        System.arraycopy(data, 0, b, HEADER, data.length);
        int sum = Checksum.upperLayer(src, dst, Ipv6Packet.PROTO_UDP, b, 0, b.length);
        Bytes.put16(b, 6, sum == 0 ? 0xFFFF : sum);
        return b;
    }

    public Ipv6Packet toPacket(Ipv6Address src, Ipv6Address dst) {
        return new Ipv6Packet(Ipv6Packet.PROTO_UDP, src, dst, encode(src, dst));
    }
}
