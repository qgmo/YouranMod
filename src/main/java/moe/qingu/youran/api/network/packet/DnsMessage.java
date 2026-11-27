package moe.qingu.youran.api.network.packet;

import moe.qingu.youran.api.network.Ipv6Address;

import java.io.ByteArrayOutputStream;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * DNS 报文（RFC 1035，AAAA 见 RFC 3596）。名字用不带结尾点的字符串表示，根是空字符串；比较时不分大小写。
 * 解析时展开名字压缩（防循环）；编码时压缩名字。认识的记录类型：A、NS、CNAME、SOA、AAAA，其他按原始字节保留。
 * <p>
 * EDNS(0)（RFC 6891）：附加区里的 OPT 伪记录解析成 {@link #edns}（不放进 {@link #additional}），编码时放在附加区最后。
 * UDP 报文的上限：问的一方不带 EDNS 时 512 字节，带了时按它声明的载荷大小，但不超过 {@value #EDNS_PAYLOAD}
 * （MTU 1280 去掉 IPv6、UDP 首部，{@link #udpLimitFor}）。超过上限的由 {@link #encodeForUdp(int)} 截断：只留问题（和 OPT），置 TC；
 * 收到 TC 的一方改用 TCP（RFC 7766，见 {@link #tcpFrame}）重问。
 */
public final class DnsMessage {
    public static final int PORT = 53;
    public static final int MAX_UDP = 512;
    /** EDNS(0) 的 UDP 载荷上限（服主 11-27：1232 = 1280 − 40 − 8） */
    public static final int EDNS_PAYLOAD = 1232;
    /** TCP 上一个报文最长（两字节长度前缀） */
    public static final int MAX_TCP = 65535;

    public static final int TYPE_A = 1;
    public static final int TYPE_NS = 2;
    public static final int TYPE_CNAME = 5;
    public static final int TYPE_SOA = 6;
    public static final int TYPE_TXT = 16;
    public static final int TYPE_AAAA = 28;
    public static final int TYPE_OPT = 41;
    public static final int TYPE_ANY = 255;
    public static final int CLASS_IN = 1;

    public static final int RCODE_OK = 0;
    public static final int RCODE_FORMERR = 1;
    public static final int RCODE_SERVFAIL = 2;
    public static final int RCODE_NXDOMAIN = 3;
    public static final int RCODE_NOTIMP = 4;
    public static final int RCODE_REFUSED = 5;
    /** 扩展响应码 BADVERS（16，RFC 6891 第 6.1.3 节）：高 8 位放在 OPT 里 */
    public static final int RCODE_BADVERS = 16;

    private static final int MAX_NAME = 255;
    private static final int MAX_LABEL = 63;

    public final int id;
    public final boolean response;
    public final int opcode;
    public final boolean authoritative;
    public final boolean truncated;
    public final boolean recursionDesired;
    public final boolean recursionAvailable;
    public final int rcode;
    public final List<Question> questions;
    public final List<Record> answers;
    public final List<Record> authority;
    public final List<Record> additional;
    /** EDNS(0) 的 OPT；没带为 null */
    public final Edns edns;
    /** OPT 不合格（不止一个、名字不是根）：服务器应回 FORMERR（RFC 6891 第 6.1.1 节） */
    public final boolean ednsMalformed;

    /** EDNS(0) 的 OPT 伪记录（RFC 6891 第 6.1.2 节） */
    public static final class Edns {
        /** 发送方能收的 UDP 载荷大小（OPT 的 CLASS 字段） */
        public final int payloadSize;
        /** 扩展响应码的高 8 位 */
        public final int extendedRcode;
        public final int version;
        public final boolean dnssecOk;
        /** OPT 的 RDATA（各个选项，原样） */
        public final byte[] options;

        public Edns(int payloadSize, int extendedRcode, int version, boolean dnssecOk, byte[] options) {
            this.payloadSize = payloadSize & 0xFFFF;
            this.extendedRcode = extendedRcode & 0xFF;
            this.version = version & 0xFF;
            this.dnssecOk = dnssecOk;
            this.options = options == null ? new byte[0] : options.clone();
        }

        /** 版本 0、载荷 {@value DnsMessage#EDNS_PAYLOAD}、没有选项 */
        public static Edns standard() {
            return new Edns(EDNS_PAYLOAD, 0, 0, false, null);
        }
    }

    public DnsMessage(int id, boolean response, int opcode, boolean authoritative, boolean truncated,
                      boolean recursionDesired, boolean recursionAvailable, int rcode,
                      List<Question> questions, List<Record> answers, List<Record> authority, List<Record> additional) {
        this(id, response, opcode, authoritative, truncated, recursionDesired, recursionAvailable, rcode, questions, answers, authority, additional,
                null, false);
    }

    /** {@code rcode} 可以是扩展响应码（大于 15 的高 8 位放进 OPT；这时要有 {@code edns}） */
    public DnsMessage(int id, boolean response, int opcode, boolean authoritative, boolean truncated,
                      boolean recursionDesired, boolean recursionAvailable, int rcode,
                      List<Question> questions, List<Record> answers, List<Record> authority, List<Record> additional,
                      Edns edns, boolean ednsMalformed) {
        if (rcode > 15 && edns != null) {
            edns = new Edns(edns.payloadSize, rcode >>> 4, edns.version, edns.dnssecOk, edns.options);
        }
        this.edns = edns;
        this.ednsMalformed = ednsMalformed;
        this.id = id & 0xFFFF;
        this.response = response;
        this.opcode = opcode & 0xF;
        this.authoritative = authoritative;
        this.truncated = truncated;
        this.recursionDesired = recursionDesired;
        this.recursionAvailable = recursionAvailable;
        this.rcode = rcode & 0xF;
        this.questions = Collections.unmodifiableList(new ArrayList<>(questions));
        this.answers = Collections.unmodifiableList(new ArrayList<>(answers));
        this.authority = Collections.unmodifiableList(new ArrayList<>(authority));
        this.additional = Collections.unmodifiableList(new ArrayList<>(additional));
    }

    /** 完整的响应码：头里的 4 位加上 OPT 里的高 8 位 */
    public int fullRcode() {
        return edns == null ? rcode : (edns.extendedRcode << 4) | rcode;
    }

    /** 换一个响应码的副本（大于 15 的扩展响应码要有 OPT） */
    public DnsMessage withRcode(int fullRcode) {
        return new DnsMessage(id, response, opcode, authoritative, truncated, recursionDesired, recursionAvailable, fullRcode,
                questions, answers, authority, additional, edns, false);
    }

    /** 换上（或去掉，null）OPT 的副本 */
    public DnsMessage withEdns(Edns e) {
        return new DnsMessage(id, response, opcode, authoritative, truncated, recursionDesired, recursionAvailable,
                e == null ? rcode : fullRcode(), questions, answers, authority, additional, e, false);
    }

    /**
     * 回给 {@code query} 的 UDP 应答最多多长：问的一方没带 EDNS 时 512，带了时取它声明的载荷大小（小于 512 的按 512），
     * 但不超过 {@value #EDNS_PAYLOAD}（RFC 6891 第 6.2.5 节）
     */
    public static int udpLimitFor(DnsMessage query) {
        if (query == null || query.edns == null) {
            return MAX_UDP;
        }
        return Math.max(MAX_UDP, Math.min(query.edns.payloadSize, EDNS_PAYLOAD));
    }

    /** TCP 上的报文：两字节长度前缀（RFC 1035 第 4.2.2 节、RFC 7766 第 8 节） */
    public static byte[] tcpFrame(byte[] message) {
        if (message.length > MAX_TCP) {
            throw new IllegalArgumentException("DNS 报文超过 65535 字节");
        }
        byte[] out = new byte[2 + message.length];
        out[0] = (byte) (message.length >>> 8);
        out[1] = (byte) message.length;
        System.arraycopy(message, 0, out, 2, message.length);
        return out;
    }

    /** 一个问题的查询 */
    public static DnsMessage query(int id, String name, int type, boolean recursionDesired) {
        return new DnsMessage(id, false, 0, false, false, recursionDesired, false, RCODE_OK,
                Collections.singletonList(new Question(name, type, CLASS_IN)),
                Collections.<Record>emptyList(), Collections.<Record>emptyList(), Collections.<Record>emptyList());
    }

    /** 回给 {@code q} 的应答（问题照抄） */
    public static DnsMessage reply(DnsMessage q, boolean authoritative, boolean recursionAvailable, int rcode,
                                   List<Record> answers, List<Record> authority, List<Record> additional) {
        return new DnsMessage(q.id, true, q.opcode, authoritative, false, q.recursionDesired, recursionAvailable, rcode,
                q.questions, answers, authority, additional);
    }

    public Question question() {
        return questions.isEmpty() ? null : questions.get(0);
    }

    // ---- 名字 ----

    /** 规范形式：小写、去掉结尾的点 */
    public static String canonical(String name) {
        String n = name.endsWith(".") ? name.substring(0, name.length() - 1) : name;
        return n.toLowerCase(Locale.ROOT);
    }

    /** {@code name} 是否等于 {@code zone} 或在它下面（不分大小写，根 "" 包含一切） */
    public static boolean isUnder(String name, String zone) {
        String n = canonical(name);
        String z = canonical(zone);
        return z.isEmpty() || n.equals(z) || n.endsWith("." + z);
    }

    /** 名字是否合法（每段 1–63 个可打印 ASCII，不含点；总长不超过 255 字节） */
    public static boolean isValidName(String name) {
        String n = name.endsWith(".") ? name.substring(0, name.length() - 1) : name;
        if (n.isEmpty()) {
            return true;
        }
        if (n.length() + 2 > MAX_NAME) {
            return false;
        }
        for (String label : n.split("\\.", -1)) {
            if (label.isEmpty() || label.length() > MAX_LABEL) {
                return false;
            }
            for (int i = 0; i < label.length(); i++) {
                char c = label.charAt(i);
                if (c <= 0x20 || c >= 0x7F) {
                    return false;
                }
            }
        }
        return true;
    }

    // ---- 解析 ----

    public static DnsMessage parse(byte[] b) throws MalformedPacketException {
        if (b.length < 12) {
            throw new MalformedPacketException("DNS 报文不到 12 字节");
        }
        int id = Bytes.u16(b, 0);
        int flags = Bytes.u16(b, 2);
        int qd = Bytes.u16(b, 4);
        int an = Bytes.u16(b, 6);
        int ns = Bytes.u16(b, 8);
        int ar = Bytes.u16(b, 10);
        // 每个问题至少 5 字节、每条记录至少 11 字节：防止计数字段很大时分配很多
        if ((long) qd * 5 + (long) (an + ns + ar) * 11 > b.length - 12) {
            throw new MalformedPacketException("DNS 计数字段超过报文长度");
        }
        int[] pos = {12};
        List<Question> questions = new ArrayList<>();
        for (int i = 0; i < qd; i++) {
            String name = readName(b, pos);
            need(b, pos[0], 4);
            questions.add(new Question(name, Bytes.u16(b, pos[0]), Bytes.u16(b, pos[0] + 2)));
            pos[0] += 4;
        }
        List<Record> answers = readRecords(b, pos, an);
        List<Record> authority = readRecords(b, pos, ns);
        List<Record> withOpt = readRecords(b, pos, ar);
        List<Record> additional = new ArrayList<>();
        Edns edns = null;
        boolean bad = false;
        for (Record r : withOpt) {
            if (r.type != TYPE_OPT) {
                additional.add(r);
                continue;
            }
            if (edns != null || !r.name.isEmpty()) {
                bad = true;
            }
            if (edns == null) {
                // TTL 字段：扩展响应码 8 位、版本 8 位、DO 1 位、Z 15 位
                edns = new Edns(r.rclass, (int) (r.ttl >>> 24), (int) (r.ttl >>> 16) & 0xFF, (r.ttl & 0x8000) != 0, r.rdata);
            }
        }
        // 扩展响应码由构造函数从 OPT 里拿（头里只有低 4 位）
        DnsMessage m = new DnsMessage(id, (flags & 0x8000) != 0, (flags >>> 11) & 0xF, (flags & 0x0400) != 0,
                (flags & 0x0200) != 0, (flags & 0x0100) != 0, (flags & 0x0080) != 0, flags & 0xF,
                questions, answers, authority, additional, edns, bad);
        return m;
    }

    private static void need(byte[] b, int pos, int n) throws MalformedPacketException {
        if (pos + n > b.length) {
            throw new MalformedPacketException("DNS 报文被截断");
        }
    }

    private static List<Record> readRecords(byte[] b, int[] pos, int count) throws MalformedPacketException {
        List<Record> list = new ArrayList<>();
        for (int i = 0; i < count; i++) {
            String name = readName(b, pos);
            need(b, pos[0], 10);
            int type = Bytes.u16(b, pos[0]);
            int cls = Bytes.u16(b, pos[0] + 2);
            long ttl = Bytes.u32(b, pos[0] + 4);
            int rdlen = Bytes.u16(b, pos[0] + 8);
            pos[0] += 10;
            need(b, pos[0], rdlen);
            int start = pos[0];
            int end = start + rdlen;
            Record r;
            switch (type) {
                case TYPE_AAAA:
                    if (rdlen != 16) {
                        throw new MalformedPacketException("AAAA 记录不是 16 字节");
                    }
                    r = new Record(name, type, cls, ttl, Ipv6Address.read(b, start), null, null, null);
                    break;
                case TYPE_NS:
                case TYPE_CNAME: {
                    int[] p = {start};
                    String target = readName(b, p);
                    if (p[0] != end) {
                        throw new MalformedPacketException("NS / CNAME 记录长度不对");
                    }
                    r = new Record(name, type, cls, ttl, null, target, null, null);
                    break;
                }
                case TYPE_SOA: {
                    int[] p = {start};
                    String mname = readName(b, p);
                    String rname = readName(b, p);
                    if (p[0] + 20 != end) {
                        throw new MalformedPacketException("SOA 记录长度不对");
                    }
                    long[] nums = new long[5];
                    for (int k = 0; k < 5; k++) {
                        nums[k] = Bytes.u32(b, p[0] + 4 * k);
                    }
                    r = new Record(name, type, cls, ttl, null, null, null, new Soa(mname, rname, nums[0], nums[1], nums[2], nums[3], nums[4]));
                    break;
                }
                default: {
                    byte[] raw = new byte[rdlen];
                    System.arraycopy(b, start, raw, 0, rdlen);
                    r = new Record(name, type, cls, ttl, null, null, raw, null);
                    break;
                }
            }
            pos[0] = end;
            list.add(r);
        }
        return list;
    }

    /** 读名字，展开压缩指针；指针只能往前指，跳的次数有上限 */
    static String readName(byte[] b, int[] pos) throws MalformedPacketException {
        StringBuilder sb = new StringBuilder();
        int p = pos[0];
        int jumps = 0;
        int length = 1;
        boolean jumped = false;
        while (true) {
            need(b, p, 1);
            int len = Bytes.u8(b, p);
            if ((len & 0xC0) == 0xC0) {
                need(b, p, 2);
                int target = ((len & 0x3F) << 8) | Bytes.u8(b, p + 1);
                if (target >= p || ++jumps > 64) {
                    throw new MalformedPacketException("DNS 名字压缩指针不对");
                }
                if (!jumped) {
                    pos[0] = p + 2;
                    jumped = true;
                }
                p = target;
                continue;
            }
            if ((len & 0xC0) != 0) {
                throw new MalformedPacketException("DNS 名字里有不认识的标签类型");
            }
            if (len == 0) {
                if (!jumped) {
                    pos[0] = p + 1;
                }
                break;
            }
            need(b, p + 1, len);
            length += len + 1;
            if (length > MAX_NAME) {
                throw new MalformedPacketException("DNS 名字超过 255 字节");
            }
            if (sb.length() > 0) {
                sb.append('.');
            }
            for (int i = 0; i < len; i++) {
                int c = Bytes.u8(b, p + 1 + i);
                if (c <= 0x20 || c >= 0x7F || c == '.') {
                    throw new MalformedPacketException("DNS 名字里有不支持的字符");
                }
                sb.append((char) c);
            }
            p += 1 + len;
        }
        return sb.toString();
    }

    // ---- 编码 ----

    public byte[] encode() {
        return new Writer().message(this, questions, answers, authority, additional, truncated);
    }

    /** UDP 用，问的一方没带 EDNS 时：同 {@link #encodeForUdp(int)}，上限 512 字节 */
    public byte[] encodeForUdp() {
        return encodeForUdp(MAX_UDP);
    }

    /**
     * UDP 用：超过 {@code limit} 字节时只留问题（和 OPT）、置 TC（RFC 1035 第 4.2.1 节、RFC 2181 第 9 节）。
     * {@code limit} 一般是 {@link #udpLimitFor}(查询)
     */
    public byte[] encodeForUdp(int limit) {
        byte[] full = encode();
        if (full.length <= limit) {
            return full;
        }
        return new Writer().message(this, questions, Collections.<Record>emptyList(), Collections.<Record>emptyList(),
                Collections.<Record>emptyList(), true);
    }

    private static final class Writer {
        private final ByteArrayOutputStream out = new ByteArrayOutputStream();
        private final Map<String, Integer> offsets = new HashMap<>();

        byte[] message(DnsMessage m, List<Question> qs, List<Record> an, List<Record> ns, List<Record> ar, boolean tc) {
            u16(m.id);
            int flags = (m.response ? 0x8000 : 0) | (m.opcode << 11) | (m.authoritative ? 0x0400 : 0) | (tc ? 0x0200 : 0)
                    | (m.recursionDesired ? 0x0100 : 0) | (m.recursionAvailable ? 0x0080 : 0) | (m.rcode & 0xF);
            u16(flags);
            u16(qs.size());
            u16(an.size());
            u16(ns.size());
            u16(ar.size() + (m.edns != null ? 1 : 0));
            for (Question q : qs) {
                name(q.name);
                u16(q.type);
                u16(q.qclass);
            }
            for (List<Record> list : java.util.Arrays.asList(an, ns, ar)) {
                for (Record r : list) {
                    record(r);
                }
            }
            if (m.edns != null) {
                Edns e = m.edns;
                long ttl = ((long) e.extendedRcode << 24) | ((long) e.version << 16) | (e.dnssecOk ? 0x8000 : 0);
                record(Record.raw("", TYPE_OPT, ttl, e.options).withClass(e.payloadSize));
            }
            return out.toByteArray();
        }

        private void record(Record r) {
            name(r.name);
            u16(r.type);
            u16(r.rclass);
            u32(r.ttl);
            int lenPos = out.size();
            u16(0);
            int start = out.size();
            switch (r.type) {
                case TYPE_AAAA:
                    out.write(r.address.toBytes(), 0, 16);
                    break;
                case TYPE_NS:
                case TYPE_CNAME:
                    name(r.target);
                    break;
                case TYPE_SOA:
                    name(r.soa.mname);
                    name(r.soa.rname);
                    u32(r.soa.serial);
                    u32(r.soa.refresh);
                    u32(r.soa.retry);
                    u32(r.soa.expire);
                    u32(r.soa.minimum);
                    break;
                default:
                    out.write(r.rdata, 0, r.rdata.length);
                    break;
            }
            int len = out.size() - start;
            byte[] all = out.toByteArray();
            all[lenPos] = (byte) (len >>> 8);
            all[lenPos + 1] = (byte) len;
            out.reset();
            out.write(all, 0, all.length);
        }

        private void name(String name) {
            String n = name.endsWith(".") ? name.substring(0, name.length() - 1) : name;
            if (!isValidName(n)) {
                throw new IllegalArgumentException("DNS 名字不合法：" + name);
            }
            while (!n.isEmpty()) {
                String key = n.toLowerCase(Locale.ROOT);
                Integer off = offsets.get(key);
                if (off != null) {
                    u16(0xC000 | off);
                    return;
                }
                if (out.size() < 0x3FFF) {
                    offsets.put(key, out.size());
                }
                int dot = n.indexOf('.');
                String label = dot < 0 ? n : n.substring(0, dot);
                out.write(label.length());
                for (int i = 0; i < label.length(); i++) {
                    out.write(label.charAt(i));
                }
                n = dot < 0 ? "" : n.substring(dot + 1);
            }
            out.write(0);
        }

        private void u16(int v) {
            out.write(v >>> 8);
            out.write(v);
        }

        private void u32(long v) {
            u16((int) (v >>> 16) & 0xFFFF);
            u16((int) v & 0xFFFF);
        }
    }

    // ---- 问题与记录 ----

    public static final class Question {
        public final String name;
        public final int type;
        public final int qclass;

        public Question(String name, int type, int qclass) {
            this.name = name;
            this.type = type & 0xFFFF;
            this.qclass = qclass & 0xFFFF;
        }

        @Override
        public String toString() {
            return name + " " + type;
        }
    }

    public static final class Soa {
        public final String mname;
        public final String rname;
        public final long serial;
        public final long refresh;
        public final long retry;
        public final long expire;
        /** 否定应答的缓存时间（RFC 2308） */
        public final long minimum;

        public Soa(String mname, String rname, long serial, long refresh, long retry, long expire, long minimum) {
            this.mname = mname;
            this.rname = rname;
            this.serial = serial;
            this.refresh = refresh;
            this.retry = retry;
            this.expire = expire;
            this.minimum = minimum;
        }
    }

    public static final class Record {
        public final String name;
        public final int type;
        public final int rclass;
        public final long ttl;
        /** AAAA 的地址 */
        public final Ipv6Address address;
        /** NS、CNAME 指向的名字 */
        public final String target;
        /** 其他类型的原始字节 */
        public final byte[] rdata;
        public final Soa soa;

        private Record(String name, int type, int rclass, long ttl, Ipv6Address address, String target, byte[] rdata, Soa soa) {
            this.name = name;
            this.type = type & 0xFFFF;
            this.rclass = rclass & 0xFFFF;
            this.ttl = ttl & 0xFFFFFFFFL;
            this.address = address;
            this.target = target;
            this.rdata = rdata;
            this.soa = soa;
        }

        public static Record aaaa(String name, long ttl, Ipv6Address address) {
            return new Record(name, TYPE_AAAA, CLASS_IN, ttl, address, null, null, null);
        }

        public static Record ns(String name, long ttl, String target) {
            return new Record(name, TYPE_NS, CLASS_IN, ttl, null, target, null, null);
        }

        public static Record cname(String name, long ttl, String target) {
            return new Record(name, TYPE_CNAME, CLASS_IN, ttl, null, target, null, null);
        }

        public static Record soa(String name, long ttl, Soa soa) {
            return new Record(name, TYPE_SOA, CLASS_IN, ttl, null, null, null, soa);
        }

        /**
         * TXT 记录：一段文字（UTF-8），超过 255 字节时按 255 字节切成几段字符串（RFC 1035 的 character-string），
         * 读的时候 {@link #text()} 再接起来。
         */
        public static Record txt(String name, long ttl, String text) {
            return new Record(name, TYPE_TXT, CLASS_IN, ttl, null, null, encodeTexts(text), null);
        }

        /** TXT 记录的文字（各段字符串接起来，按 UTF-8 读）；不是 TXT 或格式不对时为 null */
        public String text() {
            if (type != TYPE_TXT || rdata == null) {
                return null;
            }
            java.io.ByteArrayOutputStream out = new java.io.ByteArrayOutputStream();
            int i = 0;
            while (i < rdata.length) {
                int len = rdata[i] & 0xFF;
                if (i + 1 + len > rdata.length) {
                    return null;
                }
                out.write(rdata, i + 1, len);
                i += 1 + len;
            }
            return new String(out.toByteArray(), java.nio.charset.StandardCharsets.UTF_8);
        }

        static byte[] encodeTexts(String text) {
            byte[] b = text.getBytes(java.nio.charset.StandardCharsets.UTF_8);
            java.io.ByteArrayOutputStream out = new java.io.ByteArrayOutputStream();
            int i = 0;
            do {
                int len = Math.min(255, b.length - i);
                out.write(len);
                out.write(b, i, len);
                i += len;
            } while (i < b.length);
            return out.toByteArray();
        }

        public static Record raw(String name, int type, long ttl, byte[] rdata) {
            return new Record(name, type, CLASS_IN, ttl, null, null, rdata, null);
        }

        /** 改 CLASS 的副本（OPT 用 CLASS 放载荷大小） */
        Record withClass(int cls) {
            return new Record(name, type, cls, ttl, address, target, rdata, soa);
        }

        /** 改 TTL 的副本（缓存回答时用剩下的时间） */
        public Record withTtl(long newTtl) {
            return new Record(name, type, rclass, newTtl, address, target, rdata, soa);
        }

        @Override
        public String toString() {
            String v = address != null ? address.toString() : target != null ? target : soa != null ? "SOA " + soa.mname
                    : type == TYPE_TXT ? "\"" + text() + "\"" : "?";
            return name + " " + ttl + " " + type + " " + v;
        }
    }
}
