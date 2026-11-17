package moe.qingu.youran.api.network.ca;

import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;

/**
 * 证书请求（PKCS#10）用到的那点 DER（X.690）：写几种类型，读的时候只认 DER 的写法（定长、最短长度、单字节标签），
 * 越界、嵌套过深都当读不了。不依赖 JDK 内部类。
 */
final class Asn1 {
    static final int INTEGER = 0x02;
    static final int BIT_STRING = 0x03;
    static final int OCTET_STRING = 0x04;
    static final int NULL = 0x05;
    static final int OID = 0x06;
    static final int UTF8_STRING = 0x0C;
    static final int PRINTABLE_STRING = 0x13;
    static final int T61_STRING = 0x14;
    static final int IA5_STRING = 0x16;
    static final int UNIVERSAL_STRING = 0x1C;
    static final int BMP_STRING = 0x1E;
    static final int BOOLEAN = 0x01;
    static final int SEQUENCE = 0x30;
    static final int SET = 0x31;
    /** 构造类型最多套这么多层（正常的证书请求不到 10 层） */
    static final int MAX_DEPTH = 16;

    private Asn1() {
    }

    /** 读不了（截断、长度不对、不是 DER、结构不对） */
    static final class Malformed extends Exception {
        Malformed(String message) {
            super(message);
        }
    }

    // ---- 写 ----

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
        return tlv(OID, out.toByteArray());
    }

    // ---- 读 ----

    /** 一个 TLV：{@code start} 是整个 TLV 在原数组里的起点，{@code end} 是终点（不含） */
    static final class Tlv {
        final byte[] buf;
        final int tag;
        final int start;
        final int contentStart;
        final int end;
        final int depth;

        Tlv(byte[] buf, int tag, int start, int contentStart, int end, int depth) {
            this.buf = buf;
            this.tag = tag;
            this.start = start;
            this.contentStart = contentStart;
            this.end = end;
            this.depth = depth;
        }

        boolean constructed() {
            return (tag & 0x20) != 0;
        }

        int length() {
            return end - contentStart;
        }

        /** 整个 TLV（标签、长度、内容）的副本 */
        byte[] encoded() {
            byte[] b = new byte[end - start];
            System.arraycopy(buf, start, b, 0, b.length);
            return b;
        }

        byte[] content() {
            byte[] b = new byte[end - contentStart];
            System.arraycopy(buf, contentStart, b, 0, b.length);
            return b;
        }

        Tlv expect(int expectedTag, String what) throws Malformed {
            if (tag != expectedTag) {
                throw new Malformed(what + "的类型不对（要 0x" + Integer.toHexString(expectedTag) + "，是 0x" + Integer.toHexString(tag) + "）");
            }
            return this;
        }

        /** 读构造类型的内容 */
        Reader children() throws Malformed {
            if (!constructed()) {
                throw new Malformed("要构造类型，是基本类型（0x" + Integer.toHexString(tag) + "）");
            }
            if (depth + 1 > MAX_DEPTH) {
                throw new Malformed("嵌套超过 " + MAX_DEPTH + " 层");
            }
            return new Reader(buf, contentStart, end, depth + 1);
        }

        /** 点分 OID */
        String oid() throws Malformed {
            expect(OID, "OID ");
            if (length() == 0 || length() > 64) {
                throw new Malformed("OID 长度不对");
            }
            StringBuilder sb = new StringBuilder();
            long v = 0;
            boolean first = true;
            for (int i = contentStart; i < end; i++) {
                int b = buf[i] & 0xFF;
                if (v == 0 && b == 0x80) {
                    throw new Malformed("OID 不是最短写法");
                }
                if (v > (Long.MAX_VALUE >> 7)) {
                    throw new Malformed("OID 的分量太大");
                }
                v = (v << 7) | (b & 0x7F);
                if ((b & 0x80) == 0) {
                    if (first) {
                        long a = Math.min(2, v / 40);
                        sb.append(a).append('.').append(v - a * 40);
                        first = false;
                    } else {
                        sb.append('.').append(v);
                    }
                    v = 0;
                } else if (i == end - 1) {
                    throw new Malformed("OID 截断了");
                }
            }
            return sb.toString();
        }

        /** 小的非负整数（版本号） */
        int smallInt() throws Malformed {
            expect(INTEGER, "整数");
            if (length() != 1) {
                throw new Malformed("整数长度不对");
            }
            return buf[contentStart];
        }

        /** 位串的内容（只收未用位为 0 的） */
        byte[] bitString() throws Malformed {
            expect(BIT_STRING, "位串");
            if (length() < 1 || buf[contentStart] != 0) {
                throw new Malformed("位串的未用位要为 0");
            }
            byte[] b = new byte[length() - 1];
            System.arraycopy(buf, contentStart + 1, b, 0, b.length);
            return b;
        }

        /** 目录字符串（主题里的 CN 等） */
        String string() throws Malformed {
            byte[] c = content();
            switch (tag) {
                case UTF8_STRING:
                    return new String(c, StandardCharsets.UTF_8);
                case PRINTABLE_STRING:
                case IA5_STRING:
                    return ascii(c);
                case T61_STRING:
                    return new String(c, StandardCharsets.ISO_8859_1);
                case BMP_STRING:
                    if (c.length % 2 != 0) {
                        throw new Malformed("BMPString 长度不对");
                    }
                    return new String(c, StandardCharsets.UTF_16BE);
                case UNIVERSAL_STRING:
                    if (c.length % 4 != 0) {
                        throw new Malformed("UniversalString 长度不对");
                    }
                    StringBuilder sb = new StringBuilder();
                    for (int i = 0; i < c.length; i += 4) {
                        int cp = ((c[i] & 0xFF) << 24) | ((c[i + 1] & 0xFF) << 16) | ((c[i + 2] & 0xFF) << 8) | (c[i + 3] & 0xFF);
                        if (!Character.isValidCodePoint(cp)) {
                            throw new Malformed("UniversalString 里有不合法的码位");
                        }
                        sb.appendCodePoint(cp);
                    }
                    return sb.toString();
                default:
                    throw new Malformed("不认识的字符串类型（0x" + Integer.toHexString(tag) + "）");
            }
        }
    }

    static String ascii(byte[] c) throws Malformed {
        for (byte b : c) {
            if (b < 0x20 || b == 0x7F) {
                throw new Malformed("ASCII 字符串里有控制字符或非 ASCII 字节");
            }
        }
        return new String(c, StandardCharsets.US_ASCII);
    }

    /** 依次读一段里的 TLV */
    static final class Reader {
        private final byte[] buf;
        private int pos;
        private final int end;
        private final int depth;

        Reader(byte[] buf, int start, int end, int depth) {
            this.buf = buf;
            this.pos = start;
            this.end = end;
            this.depth = depth;
        }

        /** 读整个数组：里面要正好是一个 TLV */
        static Tlv single(byte[] buf) throws Malformed {
            Reader r = new Reader(buf, 0, buf.length, 0);
            Tlv t = r.next();
            r.end("末尾");
            return t;
        }

        boolean hasNext() {
            return pos < end;
        }

        Tlv next() throws Malformed {
            if (pos >= end) {
                throw new Malformed("截断了（少了内容）");
            }
            int start = pos;
            int tag = buf[pos++] & 0xFF;
            if ((tag & 0x1F) == 0x1F) {
                throw new Malformed("不收多字节标签");
            }
            if (pos >= end) {
                throw new Malformed("截断了（没有长度）");
            }
            int first = buf[pos++] & 0xFF;
            int len;
            if (first < 0x80) {
                len = first;
            } else if (first == 0x80) {
                throw new Malformed("不收不定长（BER）");
            } else {
                int n = first & 0x7F;
                if (n > 3) {
                    throw new Malformed("长度太大");
                }
                if (end - pos < n) {
                    throw new Malformed("截断了（长度不全）");
                }
                if (buf[pos] == 0) {
                    throw new Malformed("长度不是最短写法");
                }
                len = 0;
                for (int i = 0; i < n; i++) {
                    len = (len << 8) | (buf[pos++] & 0xFF);
                }
                if (len < 0x80) {
                    throw new Malformed("长度不是最短写法");
                }
            }
            if (len > end - pos) {
                throw new Malformed("长度越界（说有 " + len + " 字节，只剩 " + (end - pos) + " 字节）");
            }
            int contentStart = pos;
            pos += len;
            return new Tlv(buf, tag, start, contentStart, pos, depth);
        }

        /** 下一个是这个标签时读出来，否则不动、给 null */
        Tlv optional(int tag) throws Malformed {
            if (pos < end && (buf[pos] & 0xFF) == tag) {
                return next();
            }
            return null;
        }

        /** 读完了；还有剩的算读不了 */
        void end(String where) throws Malformed {
            if (pos != end) {
                throw new Malformed(where + "多出 " + (end - pos) + " 字节");
            }
        }
    }

    /** 检查整段的结构：每个构造类型的内容正好是若干个 TLV，嵌套不超过 {@value #MAX_DEPTH} 层（不看基本类型的内容） */
    static void checkStructure(Tlv t) throws Malformed {
        if (!t.constructed()) {
            return;
        }
        Reader r = t.children();
        while (r.hasNext()) {
            checkStructure(r.next());
        }
    }
}
