package moe.qingu.youran.api.network.packet;

import moe.qingu.youran.api.network.Ipv6Address;

import java.util.Arrays;

/**
 * ICMPv6 报文（RFC 4443）：类型、代码、校验和，后面是“报文体”（类型相关的 4 字节 + 数据）。
 * 只用到回显请求 / 应答（128 / 129）和三种差错（目标不可达 1、超时 3、参数问题 4）。
 */
public final class Icmpv6Message {
    public static final int DEST_UNREACHABLE = 1;
    public static final int PACKET_TOO_BIG = 2;
    public static final int TIME_EXCEEDED = 3;
    public static final int PARAMETER_PROBLEM = 4;
    public static final int ECHO_REQUEST = 128;
    public static final int ECHO_REPLY = 129;

    /** 目标不可达的代码 */
    public static final int UNREACH_NO_ROUTE = 0;
    public static final int UNREACH_PROHIBITED = 1;
    public static final int UNREACH_ADDRESS = 3;
    public static final int UNREACH_PORT = 4;

    /** 参数问题的代码：不认识的下一首部 */
    public static final int PARAM_UNKNOWN_NEXT_HEADER = 1;

    /** 差错报文里附带的原始包最多这么长：整个差错包不超过 1280 字节 */
    public static final int MAX_INVOKING = Ipv6Packet.MAX_PACKET - Ipv6Packet.HEADER - 8;

    public final int type;
    public final int code;
    /** 校验和后面的全部字节（回显：标识符、序号、数据；差错：4 字节 + 原始包） */
    public final byte[] body;

    public Icmpv6Message(int type, int code, byte[] body) {
        this.type = type & 0xFF;
        this.code = code & 0xFF;
        this.body = body;
    }

    public boolean isError() {
        return type < 128;
    }

    public static Icmpv6Message parse(Ipv6Packet p) throws MalformedPacketException {
        byte[] b = p.payload;
        if (b.length < 4) {
            throw new MalformedPacketException("ICMPv6 不到 4 字节");
        }
        if (!Checksum.verify(p.source, p.destination, Ipv6Packet.PROTO_ICMPV6, b, 0, b.length)) {
            throw new MalformedPacketException("ICMPv6 校验和不对");
        }
        return new Icmpv6Message(Bytes.u8(b, 0), Bytes.u8(b, 1), Arrays.copyOfRange(b, 4, b.length));
    }

    public byte[] encode(Ipv6Address src, Ipv6Address dst) {
        byte[] b = new byte[4 + body.length];
        b[0] = (byte) type;
        b[1] = (byte) code;
        System.arraycopy(body, 0, b, 4, body.length);
        Bytes.put16(b, 2, Checksum.upperLayer(src, dst, Ipv6Packet.PROTO_ICMPV6, b, 0, b.length));
        return b;
    }

    public Ipv6Packet toPacket(Ipv6Address src, Ipv6Address dst) {
        return new Ipv6Packet(Ipv6Packet.PROTO_ICMPV6, src, dst, encode(src, dst));
    }

    // ---- 回显 ----

    public static Icmpv6Message echo(int type, int identifier, int sequence, byte[] data) {
        byte[] body = new byte[4 + data.length];
        Bytes.put16(body, 0, identifier);
        Bytes.put16(body, 2, sequence);
        System.arraycopy(data, 0, body, 4, data.length);
        return new Icmpv6Message(type, 0, body);
    }

    public int echoIdentifier() {
        return body.length >= 2 ? Bytes.u16(body, 0) : -1;
    }

    public int echoSequence() {
        return body.length >= 4 ? Bytes.u16(body, 2) : -1;
    }

    public byte[] echoData() {
        return body.length > 4 ? Arrays.copyOfRange(body, 4, body.length) : new byte[0];
    }

    // ---- 差错 ----

    /**
     * 差错报文：4 字节（目标不可达、超时为 0，参数问题为指针）+ 尽量多的原始包（整个差错包不超过 1280 字节）
     */
    public static Icmpv6Message error(int type, int code, long word, byte[] invokingPacket) {
        int n = Math.min(invokingPacket.length, MAX_INVOKING);
        byte[] body = new byte[4 + n];
        Bytes.put32(body, 0, word);
        System.arraycopy(invokingPacket, 0, body, 4, n);
        return new Icmpv6Message(type, code, body);
    }

    /** 差错报文里附带的原始包（可能被截断）；不是差错或太短时返回 null */
    public byte[] invokingPacket() {
        if (!isError() || body.length < 4 + Ipv6Packet.HEADER) {
            return null;
        }
        return Arrays.copyOfRange(body, 4, body.length);
    }

    /**
     * 规则（RFC 4443 第 2.4 节 e）：不给差错报文、组播目的地、未指定 / 组播源地址的包回差错。
     * {@code raw} 是原始包字节。
     */
    public static boolean mayReplyWithError(byte[] raw) {
        if (raw.length < Ipv6Packet.HEADER) {
            return false;
        }
        Ipv6Address src = Ipv6Packet.sourceOf(raw);
        Ipv6Address dst = Ipv6Packet.destinationOf(raw);
        if (src.equals(Ipv6Address.UNSPECIFIED) || src.isMulticast() || dst.isMulticast()) {
            return false;
        }
        if (Bytes.u8(raw, 6) == Ipv6Packet.PROTO_ICMPV6 && raw.length > Ipv6Packet.HEADER) {
            return Bytes.u8(raw, Ipv6Packet.HEADER) >= 128;
        }
        return true;
    }

    @Override
    public String toString() {
        return "ICMPv6[type=" + type + " code=" + code + " len=" + body.length + "]";
    }
}
