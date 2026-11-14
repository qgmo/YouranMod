package moe.qingu.youran.api.network.packet;

import moe.qingu.youran.api.network.Ipv6Address;

/** 互联网校验和（RFC 1071），以及 ICMPv6 / UDP / TCP 用的 IPv6 伪首部（RFC 8200 第 8.1 节） */
public final class Checksum {
    private Checksum() {
    }

    /** 把 {@code b[off, off+len)} 按 16 位大端累加进 {@code sum}（不折叠进位） */
    public static long add(long sum, byte[] b, int off, int len) {
        int i = off;
        int end = off + len;
        while (i + 1 < end) {
            sum += ((b[i] & 0xFF) << 8) | (b[i + 1] & 0xFF);
            i += 2;
        }
        if (i < end) {
            sum += (b[i] & 0xFF) << 8;
        }
        return sum;
    }

    /** 折叠进位并取反，得到写进首部的 16 位校验和 */
    public static int finish(long sum) {
        while ((sum >>> 16) != 0) {
            sum = (sum & 0xFFFF) + (sum >>> 16);
        }
        return (int) (~sum & 0xFFFF);
    }

    /** IPv6 伪首部：源地址、目的地址、上层长度（32 位）、下一首部（32 位里的最低 8 位） */
    public static long pseudoHeader(Ipv6Address src, Ipv6Address dst, int upperLength, int nextHeader) {
        long sum = 0;
        for (int i = 0; i < 8; i++) {
            sum += src.group(i);
            sum += dst.group(i);
        }
        sum += (upperLength >>> 16) & 0xFFFF;
        sum += upperLength & 0xFFFF;
        sum += nextHeader & 0xFF;
        return sum;
    }

    /** 上层报文（校验和字段已填 0 或已填好）连同伪首部的校验和 */
    public static int upperLayer(Ipv6Address src, Ipv6Address dst, int nextHeader, byte[] b, int off, int len) {
        return finish(add(pseudoHeader(src, dst, len, nextHeader), b, off, len));
    }

    /** 校验和字段已填好时，整段算下来应为 0 */
    public static boolean verify(Ipv6Address src, Ipv6Address dst, int nextHeader, byte[] b, int off, int len) {
        return upperLayer(src, dst, nextHeader, b, off, len) == 0;
    }
}
