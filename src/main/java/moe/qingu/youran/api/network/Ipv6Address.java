package moe.qingu.youran.api.network;

/**
 * 一个 IPv6 地址（128 位，不可变）。高 64 位与低 64 位各存一个 long（大端：{@link #high()} 的最高位是地址的第 0 位）。
 * 文字形式照 RFC 5952（小写、最长的一段连续 0 写成 {@code ::}）；{@link #parse(String)} 收 RFC 4291 的写法（不收内嵌 IPv4）。
 */
public final class Ipv6Address implements Comparable<Ipv6Address> {
    public static final Ipv6Address UNSPECIFIED = new Ipv6Address(0, 0);

    private final long high;
    private final long low;

    public Ipv6Address(long high, long low) {
        this.high = high;
        this.low = low;
    }

    public long high() {
        return high;
    }

    public long low() {
        return low;
    }

    /** 从字节数组的 {@code offset} 处读 16 字节 */
    public static Ipv6Address read(byte[] b, int offset) {
        long h = 0;
        long l = 0;
        for (int i = 0; i < 8; i++) {
            h = (h << 8) | (b[offset + i] & 0xFF);
            l = (l << 8) | (b[offset + 8 + i] & 0xFF);
        }
        return new Ipv6Address(h, l);
    }

    /** 写进字节数组的 {@code offset} 处（16 字节） */
    public void write(byte[] b, int offset) {
        for (int i = 0; i < 8; i++) {
            b[offset + i] = (byte) (high >>> (56 - 8 * i));
            b[offset + 8 + i] = (byte) (low >>> (56 - 8 * i));
        }
    }

    public byte[] toBytes() {
        byte[] b = new byte[16];
        write(b, 0);
        return b;
    }

    /** 第 {@code i} 个 16 位段（0–7） */
    public int group(int i) {
        long v = i < 4 ? high : low;
        return (int) (v >>> (48 - 16 * (i & 3))) & 0xFFFF;
    }

    /** 是否在 {@code prefix/length} 里（length 0–128） */
    public boolean inPrefix(Ipv6Address prefix, int length) {
        if (length <= 0) {
            return true;
        }
        if (length <= 64) {
            long mask = length == 64 ? -1L : ~(-1L >>> length);
            return (high & mask) == (prefix.high & mask);
        }
        if (high != prefix.high) {
            return false;
        }
        int l = length - 64;
        long mask = l == 64 ? -1L : ~(-1L >>> l);
        return (low & mask) == (prefix.low & mask);
    }

    /** 是否组播地址（ff00::/8） */
    public boolean isMulticast() {
        return (high >>> 56) == 0xFF;
    }

    /**
     * 解析文字形式；不对时抛 {@link IllegalArgumentException}。可以带方括号（{@code [fd00::1]}）。
     */
    public static Ipv6Address parse(String text) {
        String s = text.trim();
        if (s.startsWith("[") && s.endsWith("]")) {
            s = s.substring(1, s.length() - 1);
        }
        if (s.isEmpty() || s.length() > 39 + 2) {
            throw new IllegalArgumentException("不是 IPv6 地址：" + text);
        }
        int dc = s.indexOf("::");
        if (dc >= 0 && s.indexOf("::", dc + 1) >= 0) {
            throw new IllegalArgumentException("不是 IPv6 地址（:: 出现了两次）：" + text);
        }
        int[] groups = new int[8];
        if (dc < 0) {
            String[] parts = s.split(":", -1);
            if (parts.length != 8) {
                throw new IllegalArgumentException("不是 IPv6 地址（要 8 段）：" + text);
            }
            for (int i = 0; i < 8; i++) {
                groups[i] = hex(parts[i], text);
            }
        } else {
            String head = s.substring(0, dc);
            String tail = s.substring(dc + 2);
            String[] h = head.isEmpty() ? new String[0] : head.split(":", -1);
            String[] t = tail.isEmpty() ? new String[0] : tail.split(":", -1);
            if (h.length + t.length > 7) {
                throw new IllegalArgumentException("不是 IPv6 地址（段太多）：" + text);
            }
            for (int i = 0; i < h.length; i++) {
                groups[i] = hex(h[i], text);
            }
            for (int i = 0; i < t.length; i++) {
                groups[8 - t.length + i] = hex(t[i], text);
            }
        }
        long hi = 0;
        long lo = 0;
        for (int i = 0; i < 4; i++) {
            hi = (hi << 16) | groups[i];
            lo = (lo << 16) | groups[4 + i];
        }
        return new Ipv6Address(hi, lo);
    }

    /** 能解析就返回地址，否则 null */
    public static Ipv6Address tryParse(String text) {
        try {
            return parse(text);
        } catch (IllegalArgumentException e) {
            return null;
        }
    }

    private static int hex(String part, String text) {
        if (part.isEmpty() || part.length() > 4) {
            throw new IllegalArgumentException("不是 IPv6 地址（段 \"" + part + "\" 不对）：" + text);
        }
        int v = 0;
        for (int i = 0; i < part.length(); i++) {
            int d = Character.digit(part.charAt(i), 16);
            if (d < 0) {
                throw new IllegalArgumentException("不是 IPv6 地址（段 \"" + part + "\" 不对）：" + text);
            }
            v = (v << 4) | d;
        }
        return v;
    }

    /** RFC 5952 的写法 */
    @Override
    public String toString() {
        int[] g = new int[8];
        for (int i = 0; i < 8; i++) {
            g[i] = group(i);
        }
        // 找最长的一段连续 0（至少两段才缩写；一样长取最前面那段）
        int bestStart = -1;
        int bestLen = 0;
        for (int i = 0; i < 8; ) {
            if (g[i] != 0) {
                i++;
                continue;
            }
            int j = i;
            while (j < 8 && g[j] == 0) {
                j++;
            }
            if (j - i > bestLen) {
                bestStart = i;
                bestLen = j - i;
            }
            i = j;
        }
        if (bestLen < 2) {
            bestStart = -1;
        }
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < 8; i++) {
            if (i == bestStart) {
                sb.append("::");
                i += bestLen - 1;
                continue;
            }
            if (sb.length() > 0 && sb.charAt(sb.length() - 1) != ':') {
                sb.append(':');
            }
            sb.append(Integer.toHexString(g[i]));
        }
        return sb.toString();
    }

    @Override
    public boolean equals(Object o) {
        if (!(o instanceof Ipv6Address)) {
            return false;
        }
        Ipv6Address a = (Ipv6Address) o;
        return a.high == high && a.low == low;
    }

    @Override
    public int hashCode() {
        long x = high * 31 + low;
        return (int) (x ^ (x >>> 32));
    }

    @Override
    public int compareTo(Ipv6Address o) {
        int c = Long.compareUnsigned(high, o.high);
        return c != 0 ? c : Long.compareUnsigned(low, o.low);
    }
}
