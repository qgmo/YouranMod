package moe.qingu.youran.api.network.packet;

import moe.qingu.youran.api.network.Ipv6Address;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** IPv6 / ICMPv6 / UDP / TCP 的字节格式与校验和（期望值用另外写的 Python 算出来） */
class PacketCodecTest {
    private static final Ipv6Address A = Ipv6Address.parse("fd00::1");
    private static final Ipv6Address B = Ipv6Address.parse("fd00::2");

    @Test
    void icmpChecksumMatchesReference() {
        byte[] b = Icmpv6Message.echo(Icmpv6Message.ECHO_REQUEST, 0x1234, 1, "abcd".getBytes(StandardCharsets.US_ASCII)).encode(A, B);
        assertEquals(0xaeb8, Bytes.u16(b, 2));
    }

    @Test
    void udpChecksumMatchesReference() {
        byte[] b = new UdpDatagram(5353, 53, "hello".getBytes(StandardCharsets.US_ASCII)).encode(A, B);
        assertEquals(13, Bytes.u16(b, 4));
        assertEquals(0xacdf, Bytes.u16(b, 6));
    }

    @Test
    void tcpChecksumMatchesReference() {
        byte[] b = new TcpSegment(49152, 80, 1000, 0, TcpSegment.SYN, 65535, 1220, new byte[0]).encode(A, B);
        assertEquals(24, b.length);
        assertEquals(6 << 4, b[12] & 0xFF);
        assertEquals(0xdad9, Bytes.u16(b, 16));
    }

    @Test
    void ipv6RoundTripAndFields() throws Exception {
        Ipv6Packet p = new Ipv6Packet(0xAB, 0x12345, Ipv6Packet.PROTO_UDP, 7, A, B, new byte[]{1, 2, 3});
        byte[] b = p.encode();
        assertEquals(43, b.length);
        assertEquals(0x6A, b[0] & 0xFF);
        assertEquals(0xB1, b[1] & 0xFF);
        Ipv6Packet q = Ipv6Packet.parse(b);
        assertEquals(0xAB, q.trafficClass);
        assertEquals(0x12345, q.flowLabel);
        assertEquals(7, q.hopLimit);
        assertEquals(A, q.source);
        assertEquals(B, q.destination);
        assertArrayEquals(new byte[]{1, 2, 3}, q.payload);
    }

    @Test
    void size1280Boundary() throws Exception {
        byte[] ok = new Ipv6Packet(Ipv6Packet.PROTO_UDP, A, B, new byte[1240]).encode();
        assertEquals(1280, ok.length);
        assertEquals(1240, Ipv6Packet.parse(ok).payload.length);
        assertThrows(IllegalArgumentException.class, () -> new Ipv6Packet(Ipv6Packet.PROTO_UDP, A, B, new byte[1241]).encode());
        byte[] big = new byte[1281];
        System.arraycopy(ok, 0, big, 0, ok.length);
        assertThrows(MalformedPacketException.class, () -> Ipv6Packet.parse(big));
        // 最大的 UDP 数据：1232 字节
        assertEquals(1232, UdpDatagram.MAX_DATA);
        assertEquals(1280, new UdpDatagram(1, 2, new byte[1232]).toPacket(A, B).encode().length);
    }

    @Test
    void malformedIpv6() {
        byte[] b = new Ipv6Packet(Ipv6Packet.PROTO_UDP, A, B, new byte[8]).encode();
        assertThrows(MalformedPacketException.class, () -> Ipv6Packet.parse(new byte[39]));
        byte[] v4 = b.clone();
        v4[0] = 0x45;
        assertThrows(MalformedPacketException.class, () -> Ipv6Packet.parse(v4));
        byte[] longer = b.clone();
        Bytes.put16(longer, 4, 9);
        assertThrows(MalformedPacketException.class, () -> Ipv6Packet.parse(longer));
    }

    @Test
    void badChecksumsAreRejected() throws Exception {
        Ipv6Packet icmp = Icmpv6Message.echo(Icmpv6Message.ECHO_REQUEST, 1, 1, new byte[4]).toPacket(A, B);
        Icmpv6Message.parse(Ipv6Packet.parse(icmp.encode()));
        assertThrows(MalformedPacketException.class, () -> Icmpv6Message.parse(Ipv6Packet.parse(flipLast(icmp.encode()))));
        Ipv6Packet udp = new UdpDatagram(1, 2, new byte[]{9}).toPacket(A, B);
        UdpDatagram.parse(Ipv6Packet.parse(udp.encode()));
        assertThrows(MalformedPacketException.class, () -> UdpDatagram.parse(Ipv6Packet.parse(flipLast(udp.encode()))));
        Ipv6Packet tcp = new TcpSegment(1, 2, 3, 4, TcpSegment.ACK, 100, -1, new byte[]{7}).toPacket(A, B);
        TcpSegment.parse(Ipv6Packet.parse(tcp.encode()));
        assertThrows(MalformedPacketException.class, () -> TcpSegment.parse(Ipv6Packet.parse(flipLast(tcp.encode()))));
        // 换了目的地址（伪首部变了）校验和就不对
        Ipv6Packet moved = new Ipv6Packet(Ipv6Packet.PROTO_UDP, A, Ipv6Address.parse("fd00::3"), udp.payload);
        assertThrows(MalformedPacketException.class, () -> UdpDatagram.parse(moved));
    }

    @Test
    void udpZeroChecksumIsInvalidInIpv6() {
        byte[] u = new UdpDatagram(1, 2, new byte[]{9}).encode(A, B);
        Bytes.put16(u, 6, 0);
        assertThrows(MalformedPacketException.class, () -> UdpDatagram.parse(new Ipv6Packet(Ipv6Packet.PROTO_UDP, A, B, u)));
    }

    @Test
    void tcpOptionsAndFlags() throws Exception {
        TcpSegment s = new TcpSegment(1000, 80, 0xFFFFFFFFL, 5, TcpSegment.SYN | TcpSegment.ACK, 4096, 1220, new byte[0]);
        TcpSegment t = TcpSegment.parse(s.toPacket(A, B));
        assertEquals(0xFFFFFFFFL, t.seq);
        assertEquals(1220, t.mss);
        assertTrue(t.has(TcpSegment.SYN) && t.has(TcpSegment.ACK));
        assertFalse(t.has(TcpSegment.FIN));
        assertEquals(1, t.sequenceLength());
        // 首部长度字段不对
        byte[] b = s.encode(A, B);
        b[12] = (byte) (4 << 4);
        assertThrows(MalformedPacketException.class, () -> TcpSegment.parse(new Ipv6Packet(Ipv6Packet.PROTO_TCP, A, B, b)));
    }

    @Test
    void icmpErrorQuotesAtMost1232Bytes() throws Exception {
        byte[] invoking = new UdpDatagram(1, 2, new byte[1232]).toPacket(A, B).encode();
        Icmpv6Message e = Icmpv6Message.error(Icmpv6Message.DEST_UNREACHABLE, Icmpv6Message.UNREACH_PORT, 0, invoking);
        byte[] pkt = e.toPacket(B, A).encode();
        assertEquals(1280, pkt.length);
        Icmpv6Message back = Icmpv6Message.parse(Ipv6Packet.parse(pkt));
        assertEquals(Icmpv6Message.MAX_INVOKING, back.invokingPacket().length);
        assertEquals(A, Ipv6Packet.sourceOf(back.invokingPacket()));
    }

    @Test
    void noErrorsAboutErrors() {
        byte[] err = Icmpv6Message.error(1, 0, 0, new byte[48]).toPacket(A, B).encode();
        assertFalse(Icmpv6Message.mayReplyWithError(err));
        byte[] echo = Icmpv6Message.echo(128, 1, 1, new byte[0]).toPacket(A, B).encode();
        assertTrue(Icmpv6Message.mayReplyWithError(echo));
        byte[] fromUnspecified = Icmpv6Message.echo(128, 1, 1, new byte[0]).toPacket(Ipv6Address.UNSPECIFIED, B).encode();
        assertFalse(Icmpv6Message.mayReplyWithError(fromUnspecified));
        byte[] toMulticast = Icmpv6Message.echo(128, 1, 1, new byte[0]).toPacket(A, Ipv6Address.parse("ff02::1")).encode();
        assertFalse(Icmpv6Message.mayReplyWithError(toMulticast));
    }

    private static byte[] flipLast(byte[] b) {
        byte[] c = b.clone();
        c[c.length - 1] ^= 0x01;
        return c;
    }
}
