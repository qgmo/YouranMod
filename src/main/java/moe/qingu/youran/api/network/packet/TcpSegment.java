package moe.qingu.youran.api.network.packet;

import moe.qingu.youran.api.network.Ipv6Address;

import java.util.Arrays;

/**
 * TCP 报文段（RFC 9293 的首部格式）。选项只认 MSS（种类 2）；其他选项按长度跳过。
 * 序号、确认号是无符号 32 位，存在 long 的低 32 位里。
 */
public final class TcpSegment {
    public static final int HEADER = 20;
    public static final int FIN = 0x01;
    public static final int SYN = 0x02;
    public static final int RST = 0x04;
    public static final int PSH = 0x08;
    public static final int ACK = 0x10;
    public static final int URG = 0x20;

    public final int sourcePort;
    public final int destinationPort;
    public final long seq;
    public final long ack;
    public final int flags;
    public final int window;
    public final int urgent;
    /** MSS 选项的值；没有为 -1（只在 SYN 里带） */
    public final int mss;
    public final byte[] data;

    public TcpSegment(int sourcePort, int destinationPort, long seq, long ack, int flags, int window, int mss, byte[] data) {
        this(sourcePort, destinationPort, seq, ack, flags, window, 0, mss, data);
    }

    public TcpSegment(int sourcePort, int destinationPort, long seq, long ack, int flags, int window, int urgent, int mss, byte[] data) {
        this.sourcePort = sourcePort & 0xFFFF;
        this.destinationPort = destinationPort & 0xFFFF;
        this.seq = seq & 0xFFFFFFFFL;
        this.ack = ack & 0xFFFFFFFFL;
        this.flags = flags & 0xFF;
        this.window = window & 0xFFFF;
        this.urgent = urgent & 0xFFFF;
        this.mss = mss;
        this.data = data;
    }

    public boolean has(int flag) {
        return (flags & flag) != 0;
    }

    /** 这一段占的序号数：数据长度，SYN、FIN 各占 1 */
    public int sequenceLength() {
        return data.length + (has(SYN) ? 1 : 0) + (has(FIN) ? 1 : 0);
    }

    public static TcpSegment parse(Ipv6Packet p) throws MalformedPacketException {
        byte[] b = p.payload;
        if (b.length < HEADER) {
            throw new MalformedPacketException("TCP 不到 20 字节");
        }
        int offset = (Bytes.u8(b, 12) >>> 4) * 4;
        if (offset < HEADER || offset > b.length) {
            throw new MalformedPacketException("TCP 首部长度 " + offset + " 不对（报文段 " + b.length + " 字节）");
        }
        if (!Checksum.verify(p.source, p.destination, Ipv6Packet.PROTO_TCP, b, 0, b.length)) {
            throw new MalformedPacketException("TCP 校验和不对");
        }
        int mss = -1;
        int i = HEADER;
        while (i < offset) {
            int kind = Bytes.u8(b, i);
            if (kind == 0) {
                break;
            }
            if (kind == 1) {
                i++;
                continue;
            }
            if (i + 1 >= offset) {
                throw new MalformedPacketException("TCP 选项被截断");
            }
            int olen = Bytes.u8(b, i + 1);
            if (olen < 2 || i + olen > offset) {
                throw new MalformedPacketException("TCP 选项长度不对");
            }
            if (kind == 2) {
                if (olen != 4) {
                    throw new MalformedPacketException("TCP MSS 选项长度不是 4");
                }
                mss = Bytes.u16(b, i + 2);
            }
            i += olen;
        }
        return new TcpSegment(Bytes.u16(b, 0), Bytes.u16(b, 2), Bytes.u32(b, 4), Bytes.u32(b, 8),
                Bytes.u8(b, 13), Bytes.u16(b, 14), Bytes.u16(b, 18), mss, Arrays.copyOfRange(b, offset, b.length));
    }

    public byte[] encode(Ipv6Address src, Ipv6Address dst) {
        int optLen = mss >= 0 ? 4 : 0;
        int offset = HEADER + optLen;
        byte[] b = new byte[offset + data.length];
        Bytes.put16(b, 0, sourcePort);
        Bytes.put16(b, 2, destinationPort);
        Bytes.put32(b, 4, seq);
        Bytes.put32(b, 8, ack);
        b[12] = (byte) ((offset / 4) << 4);
        b[13] = (byte) flags;
        Bytes.put16(b, 14, window);
        Bytes.put16(b, 18, urgent);
        if (mss >= 0) {
            b[20] = 2;
            b[21] = 4;
            Bytes.put16(b, 22, mss);
        }
        System.arraycopy(data, 0, b, offset, data.length);
        Bytes.put16(b, 16, Checksum.upperLayer(src, dst, Ipv6Packet.PROTO_TCP, b, 0, b.length));
        return b;
    }

    public Ipv6Packet toPacket(Ipv6Address src, Ipv6Address dst) {
        return new Ipv6Packet(Ipv6Packet.PROTO_TCP, src, dst, encode(src, dst));
    }

    @Override
    public String toString() {
        StringBuilder f = new StringBuilder();
        if (has(SYN)) f.append('S');
        if (has(ACK)) f.append('.');
        if (has(FIN)) f.append('F');
        if (has(RST)) f.append('R');
        if (has(PSH)) f.append('P');
        return "TCP[" + sourcePort + "->" + destinationPort + " " + f + " seq=" + seq + " ack=" + ack
                + " win=" + window + " len=" + data.length + "]";
    }
}
