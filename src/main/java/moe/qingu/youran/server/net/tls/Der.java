package moe.qingu.youran.server.net.tls;

import java.io.ByteArrayOutputStream;
import java.math.BigInteger;
import java.nio.charset.StandardCharsets;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;
import java.util.TimeZone;

/** 写 DER（X.690）：签证书用到的那几种类型 */
final class Der {
    static final int SEQUENCE = 0x30;
    static final int SET = 0x31;

    private Der() {
    }

    static byte[] tlv(int tag, byte[] content) {
        ByteArrayOutputStream out = new ByteArrayOutputStream(content.length + 6);
        out.write(tag);
        int len = content.length;
        if (len < 0x80) {
            out.write(len);
        } else if (len < 0x100) {
            out.write(0x81);
            out.write(len);
        } else if (len < 0x10000) {
            out.write(0x82);
            out.write(len >> 8);
            out.write(len);
        } else {
            out.write(0x83);
            out.write(len >> 16);
            out.write(len >> 8);
            out.write(len);
        }
        out.write(content, 0, content.length);
        return out.toByteArray();
    }

    static byte[] concat(byte[]... parts) {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        for (byte[] p : parts) {
            out.write(p, 0, p.length);
        }
        return out.toByteArray();
    }

    static byte[] sequence(byte[]... parts) {
        return tlv(SEQUENCE, concat(parts));
    }

    static byte[] set(byte[]... parts) {
        return tlv(SET, concat(parts));
    }

    static byte[] integer(BigInteger v) {
        return tlv(0x02, v.toByteArray());
    }

    static byte[] integer(long v) {
        return integer(BigInteger.valueOf(v));
    }

    static byte[] bool(boolean v) {
        return tlv(0x01, new byte[]{(byte) (v ? 0xFF : 0)});
    }

    static byte[] nul() {
        return new byte[]{0x05, 0x00};
    }

    static byte[] octets(byte[] v) {
        return tlv(0x04, v);
    }

    /** 整字节的位串（未用位为 0） */
    static byte[] bits(byte[] v) {
        byte[] c = new byte[v.length + 1];
        System.arraycopy(v, 0, c, 1, v.length);
        return tlv(0x03, c);
    }

    /** 命名位串（如 keyUsage）：位 0 是最高位，去掉末尾的 0 位（DER 要求） */
    static byte[] namedBits(boolean... bits) {
        int last = -1;
        for (int i = 0; i < bits.length; i++) {
            if (bits[i]) {
                last = i;
            }
        }
        if (last < 0) {
            return tlv(0x03, new byte[]{0});
        }
        int bytes = last / 8 + 1;
        byte[] c = new byte[bytes + 1];
        c[0] = (byte) (bytes * 8 - 1 - last);
        for (int i = 0; i <= last; i++) {
            if (bits[i]) {
                c[1 + i / 8] |= (byte) (0x80 >> (i % 8));
            }
        }
        return tlv(0x03, c);
    }

    static byte[] oid(String dotted) {
        String[] p = dotted.split("\\.");
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        out.write(Integer.parseInt(p[0]) * 40 + Integer.parseInt(p[1]));
        for (int i = 2; i < p.length; i++) {
            long v = Long.parseLong(p[i]);
            byte[] tmp = new byte[10];
            int n = 0;
            do {
                tmp[n++] = (byte) (v & 0x7F);
                v >>>= 7;
            } while (v != 0);
            for (int j = n - 1; j >= 0; j--) {
                out.write(tmp[j] | (j > 0 ? 0x80 : 0));
            }
        }
        return tlv(0x06, out.toByteArray());
    }

    static byte[] utf8(String s) {
        return tlv(0x0C, s.getBytes(StandardCharsets.UTF_8));
    }

    static byte[] ia5(String s) {
        return tlv(0x16, s.getBytes(StandardCharsets.US_ASCII));
    }

    /** RFC 5280：2049 年以前用 UTCTime，以后用 GeneralizedTime */
    static byte[] time(Date d) {
        SimpleDateFormat f;
        java.util.Calendar c = java.util.Calendar.getInstance(TimeZone.getTimeZone("UTC"), Locale.ROOT);
        c.setTime(d);
        boolean utc = c.get(java.util.Calendar.YEAR) < 2050;
        f = new SimpleDateFormat(utc ? "yyMMddHHmmss'Z'" : "yyyyMMddHHmmss'Z'", Locale.ROOT);
        f.setTimeZone(TimeZone.getTimeZone("UTC"));
        return tlv(utc ? 0x17 : 0x18, f.format(d).getBytes(StandardCharsets.US_ASCII));
    }

    /** 上下文标签：显式（构造） */
    static byte[] explicit(int n, byte[] inner) {
        return tlv(0xA0 | n, inner);
    }

    /** 上下文标签：隐式、基本类型 */
    static byte[] implicitPrimitive(int n, byte[] content) {
        return tlv(0x80 | n, content);
    }

    /** 上下文标签：隐式、构造类型（内容是若干个 TLV） */
    static byte[] implicitConstructed(int n, byte[] content) {
        return tlv(0xA0 | n, content);
    }
}
