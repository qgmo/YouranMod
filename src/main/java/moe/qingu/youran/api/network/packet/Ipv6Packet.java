package moe.qingu.youran.api.network.packet;

import moe.qingu.youran.api.network.Ipv6Address;

import java.util.Arrays;

/**
 * IPv6 数据包（RFC 8200）：40 字节固定首部 + 载荷。不支持扩展首部（下一首部直接是上层协议；
 * 收到带扩展首部的包，协议栈按“不认识的下一首部”回参数问题）。
 * 整个包不超过 {@link #MAX_PACKET} = 1280 字节（IPv6 最小 MTU），不分片。
 */
public final class Ipv6Packet {
    public static final int HEADER = 40;
    /** 整个包的上限：IPv6 最小 MTU */
    public static final int MAX_PACKET = 1280;
    public static final int MAX_PAYLOAD = MAX_PACKET - HEADER;
    public static final int DEFAULT_HOP_LIMIT = 64;

    public static final int PROTO_TCP = 6;
    public static final int PROTO_UDP = 17;
    public static final int PROTO_ICMPV6 = 58;

    public final int trafficClass;
    public final int flowLabel;
    public final int nextHeader;
    public final int hopLimit;
    public final Ipv6Address source;
    public final Ipv6Address destination;
    /** 载荷（不含 IPv6 首部）；不要改 */
    public final byte[] payload;

    public Ipv6Packet(int trafficClass, int flowLabel, int nextHeader, int hopLimit,
                      Ipv6Address source, Ipv6Address destination, byte[] payload) {
        this.trafficClass = trafficClass & 0xFF;
        this.flowLabel = flowLabel & 0xFFFFF;
        this.nextHeader = nextHeader & 0xFF;
        this.hopLimit = hopLimit & 0xFF;
        this.source = source;
        this.destination = destination;
        this.payload = payload;
    }

    public Ipv6Packet(int nextHeader, Ipv6Address source, Ipv6Address destination, byte[] payload) {
        this(0, 0, nextHeader, DEFAULT_HOP_LIMIT, source, destination, payload);
    }

    /**
     * 解析。包长不到 40、版本不是 6、首部里的载荷长度超过实际长度、整包超过 1280 字节都算坏包。
     * 实际长度比载荷长度长时，多出来的字节忽略（同真网卡上链路层的填充）。
     */
    public static Ipv6Packet parse(byte[] b) throws MalformedPacketException {
        return parse(b, b.length);
    }

    public static Ipv6Packet parse(byte[] b, int length) throws MalformedPacketException {
        if (length < HEADER) {
            throw new MalformedPacketException("IPv6 包不到 40 字节（" + length + "）");
        }
        if (length > MAX_PACKET) {
            throw new MalformedPacketException("IPv6 包超过 1280 字节（" + length + "）");
        }
        int first = Bytes.u8(b, 0);
        if ((first >>> 4) != 6) {
            throw new MalformedPacketException("版本不是 6（" + (first >>> 4) + "）");
        }
        int tc = ((first & 0x0F) << 4) | (Bytes.u8(b, 1) >>> 4);
        int flow = ((Bytes.u8(b, 1) & 0x0F) << 16) | Bytes.u16(b, 2);
        int plen = Bytes.u16(b, 4);
        if (plen > length - HEADER) {
            throw new MalformedPacketException("载荷长度 " + plen + " 超过实际的 " + (length - HEADER));
        }
        int nh = Bytes.u8(b, 6);
        int hop = Bytes.u8(b, 7);
        Ipv6Address src = Ipv6Address.read(b, 8);
        Ipv6Address dst = Ipv6Address.read(b, 24);
        return new Ipv6Packet(tc, flow, nh, hop, src, dst, Arrays.copyOfRange(b, HEADER, HEADER + plen));
    }

    /** 编码成字节（载荷超过 1240 字节时抛 {@link IllegalArgumentException}） */
    public byte[] encode() {
        if (payload.length > MAX_PAYLOAD) {
            throw new IllegalArgumentException("IPv6 载荷超过 " + MAX_PAYLOAD + " 字节（" + payload.length + "）");
        }
        byte[] b = new byte[HEADER + payload.length];
        b[0] = (byte) (0x60 | (trafficClass >>> 4));
        b[1] = (byte) (((trafficClass & 0x0F) << 4) | (flowLabel >>> 16));
        Bytes.put16(b, 2, flowLabel);
        Bytes.put16(b, 4, payload.length);
        b[6] = (byte) nextHeader;
        b[7] = (byte) hopLimit;
        source.write(b, 8);
        destination.write(b, 24);
        System.arraycopy(payload, 0, b, HEADER, payload.length);
        return b;
    }

    /** 跳数减一后的副本（路由器转发用） */
    public Ipv6Packet withHopLimit(int hop) {
        return new Ipv6Packet(trafficClass, flowLabel, nextHeader, hop, source, destination, payload);
    }

    // ---- 不完整解析：路由器只看首部 ----

    /** 原始包里的目的地址（调用前已确认长度 ≥ 40） */
    public static Ipv6Address destinationOf(byte[] b) {
        return Ipv6Address.read(b, 24);
    }

    public static Ipv6Address sourceOf(byte[] b) {
        return Ipv6Address.read(b, 8);
    }

    @Override
    public String toString() {
        return "IPv6[" + source + " -> " + destination + " nh=" + nextHeader + " hop=" + hopLimit + " len=" + payload.length + "]";
    }
}
