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
 * 不带 EDNS 时 UDP 报文最多 512 字节，超过的由 {@link #encodeForUdp()} 截断并置 TC。
 */
public final class DnsMessage {
    public static final int PORT = 53;
    public static final int MAX_UDP = 512;

    public static final int TYPE_A = 1;
    public static final int TYPE_NS = 2;
    public static final int TYPE_CNAME = 5;
    public static final int TYPE_SOA = 6;
    public static final int TYPE_AAAA = 28;
    public static final int TYPE_ANY = 255;
    public static final int CLASS_IN = 1;

    public static final int RCODE_OK = 0;
    public static final int RCODE_FORMERR = 1;
    public static final int RCODE_SERVFAIL = 2;
    public static final int RCODE_NXDOMAIN = 3;
    public static final int RCODE_NOTIMP = 4;
    public static final int RCODE_REFUSED = 5;

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

    public DnsMessage(int id, boolean response, int opcode, boolean authoritative, boolean truncated,
                      boolean recursionDesired, boolean recursionAvailable, int rcode,
                      List<Question> questions, List<Record> answers, List<Record> authority, List<Record> additional) {
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
        List<Record> additional = readRecords(b, pos, ar);
        return new DnsMessage(id, (flags & 0x8000) != 0, (flags >>> 11) & 0xF, (flags & 0x0400) != 0,
                (flags & 0x0200) != 0, (flags & 0x0100) != 0, (flags & 0x0080) != 0, flags & 0xF,
                questions, answers, authority, additional);
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

    /** UDP 用：超过 512 字节时只留问题、置 TC（RFC 1035 第 4.2.1 节） */
    public byte[] encodeForUdp() {
        byte[] full = encode();
        if (full.length <= MAX_UDP) {
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
                    | (m.recursionDesired ? 0x0100 : 0) | (m.recursionAvailable ? 0x0080 : 0) | m.rcode;
            u16(flags);
            u16(qs.size());
            u16(an.size());
            u16(ns.size());
            u16(ar.size());
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

        public static Record raw(String name, int type, long ttl, byte[] rdata) {
            return new Record(name, type, CLASS_IN, ttl, null, null, rdata, null);
        }

        /** 改 TTL 的副本（缓存回答时用剩下的时间） */
        public Record withTtl(long newTtl) {
            return new Record(name, type, rclass, newTtl, address, target, rdata, soa);
        }

        @Override
        public String toString() {
            String v = address != null ? address.toString() : target != null ? target : soa != null ? "SOA " + soa.mname : "?";
            return name + " " + ttl + " " + type + " " + v;
        }
    }
}
