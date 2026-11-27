package moe.qingu.youran.api.network.packet;

import moe.qingu.youran.api.network.Ipv6Address;

import java.io.ByteArrayOutputStream;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;

/**
 * DHCPv6 报文（RFC 8415）：客户端与服务器之间的报文（不认中继报文）。
 * <pre>
 *   消息类型 1 | 事务号 3 | 选项……
 * </pre>
 * 认的选项：客户端标识 CLIENTID（1）、服务器标识 SERVERID（2）、非临时地址 IA_NA（3，里面的 IAADDR 5 与状态码）、
 * 要哪些选项 ORO（6）、优先级 PREFERENCE（7）、已用时间 ELAPSED_TIME（8）、状态码 STATUS_CODE（13）、
 * DNS 服务器（23，RFC 3646）、NTP 服务器（56，RFC 5908；只认子选项 1 服务器地址）。别的选项跳过（RFC 8415 第 16 节：不认识的忽略）。
 * <p>
 * {@link #parse} 对长度逐个检查，不合格抛 {@link MalformedPacketException}（调用方丢掉，不回）。
 */
public final class Dhcpv6Message {
    public static final int CLIENT_PORT = 546;
    public static final int SERVER_PORT = 547;

    public static final int SOLICIT = 1;
    public static final int ADVERTISE = 2;
    public static final int REQUEST = 3;
    public static final int CONFIRM = 4;
    public static final int RENEW = 5;
    public static final int REBIND = 6;
    public static final int REPLY = 7;
    public static final int RELEASE = 8;
    public static final int DECLINE = 9;
    public static final int RECONFIGURE = 10;
    public static final int INFORMATION_REQUEST = 11;

    public static final int OPT_CLIENTID = 1;
    public static final int OPT_SERVERID = 2;
    public static final int OPT_IA_NA = 3;
    public static final int OPT_IAADDR = 5;
    public static final int OPT_ORO = 6;
    public static final int OPT_PREFERENCE = 7;
    public static final int OPT_ELAPSED_TIME = 8;
    public static final int OPT_STATUS_CODE = 13;
    public static final int OPT_DNS_SERVERS = 23;
    public static final int OPT_NTP_SERVER = 56;
    static final int NTP_SUBOPTION_SRV_ADDR = 1;

    public static final int STATUS_SUCCESS = 0;
    public static final int STATUS_UNSPEC_FAIL = 1;
    public static final int STATUS_NO_ADDRS_AVAIL = 2;
    public static final int STATUS_NO_BINDING = 3;
    public static final int STATUS_NOT_ON_LINK = 4;
    public static final int STATUS_USE_MULTICAST = 5;

    /** 寿命、T1 / T2 的“无限”（RFC 8415 第 7.7 节） */
    public static final long INFINITY = 0xFFFFFFFFL;
    /** DUID 类型 4：UUID（RFC 6355） */
    public static final int DUID_UUID = 4;

    public final int type;
    /** 24 位事务号 */
    public final int transactionId;
    /** 没有为 null */
    public final byte[] clientId;
    public final byte[] serverId;
    public final List<IaNa> iaNa;
    public final List<Integer> requestedOptions;
    /** 没有为 -1 */
    public final int preference;
    /** 单位 1/100 秒；没有为 -1 */
    public final int elapsedTime;
    /** 报文级的状态码；没有为 -1 */
    public final int status;
    public final String statusMessage;
    public final List<Ipv6Address> dnsServers;
    public final List<Ipv6Address> ntpServers;

    /** 一个 IA_NA（RFC 8415 第 21.4 节） */
    public static final class IaNa {
        public final long iaid;
        public final long t1;
        public final long t2;
        public final List<IaAddress> addresses;
        /** IA 里的状态码；没有为 -1 */
        public final int status;

        public IaNa(long iaid, long t1, long t2, List<IaAddress> addresses, int status) {
            this.iaid = iaid & 0xFFFFFFFFL;
            this.t1 = t1 & 0xFFFFFFFFL;
            this.t2 = t2 & 0xFFFFFFFFL;
            this.addresses = Collections.unmodifiableList(new ArrayList<>(addresses));
            this.status = status;
        }
    }

    /** IA_NA 里的一个地址（IAADDR，RFC 8415 第 21.6 节） */
    public static final class IaAddress {
        public final Ipv6Address address;
        public final long preferredLifetime;
        public final long validLifetime;
        public final int status;

        public IaAddress(Ipv6Address address, long preferredLifetime, long validLifetime, int status) {
            this.address = address;
            this.preferredLifetime = preferredLifetime & 0xFFFFFFFFL;
            this.validLifetime = validLifetime & 0xFFFFFFFFL;
            this.status = status;
        }
    }

    public Dhcpv6Message(int type, int transactionId, byte[] clientId, byte[] serverId, List<IaNa> iaNa, List<Integer> requestedOptions,
                         int preference, int elapsedTime, int status, String statusMessage, List<Ipv6Address> dnsServers, List<Ipv6Address> ntpServers) {
        this.type = type & 0xFF;
        this.transactionId = transactionId & 0xFFFFFF;
        this.clientId = clientId == null ? null : clientId.clone();
        this.serverId = serverId == null ? null : serverId.clone();
        this.iaNa = Collections.unmodifiableList(new ArrayList<>(iaNa));
        this.requestedOptions = Collections.unmodifiableList(new ArrayList<>(requestedOptions));
        this.preference = preference;
        this.elapsedTime = elapsedTime;
        this.status = status;
        this.statusMessage = statusMessage;
        this.dnsServers = Collections.unmodifiableList(new ArrayList<>(dnsServers));
        this.ntpServers = Collections.unmodifiableList(new ArrayList<>(ntpServers));
    }

    /** DUID-UUID：类型 4 + 16 字节 UUID */
    public static byte[] uuidDuid(long most, long least) {
        byte[] d = new byte[18];
        Bytes.put16(d, 0, DUID_UUID);
        Bytes.put64(d, 2, most);
        Bytes.put64(d, 10, least);
        return d;
    }

    // ---- 编码 ----

    public byte[] encode() {
        ByteArrayOutputStream b = new ByteArrayOutputStream();
        b.write(type);
        b.write(transactionId >>> 16);
        b.write(transactionId >>> 8);
        b.write(transactionId);
        if (clientId != null) {
            option(b, OPT_CLIENTID, clientId);
        }
        if (serverId != null) {
            option(b, OPT_SERVERID, serverId);
        }
        for (IaNa ia : iaNa) {
            ByteArrayOutputStream o = new ByteArrayOutputStream();
            put32(o, ia.iaid);
            put32(o, ia.t1);
            put32(o, ia.t2);
            for (IaAddress a : ia.addresses) {
                ByteArrayOutputStream x = new ByteArrayOutputStream();
                x.write(a.address.toBytes(), 0, 16);
                put32(x, a.preferredLifetime);
                put32(x, a.validLifetime);
                if (a.status >= 0) {
                    option(x, OPT_STATUS_CODE, statusBody(a.status, null));
                }
                option(o, OPT_IAADDR, x.toByteArray());
            }
            if (ia.status >= 0) {
                option(o, OPT_STATUS_CODE, statusBody(ia.status, null));
            }
            option(b, OPT_IA_NA, o.toByteArray());
        }
        if (!requestedOptions.isEmpty()) {
            byte[] oro = new byte[requestedOptions.size() * 2];
            for (int i = 0; i < requestedOptions.size(); i++) {
                Bytes.put16(oro, 2 * i, requestedOptions.get(i));
            }
            option(b, OPT_ORO, oro);
        }
        if (preference >= 0) {
            option(b, OPT_PREFERENCE, new byte[]{(byte) preference});
        }
        if (elapsedTime >= 0) {
            byte[] e = new byte[2];
            Bytes.put16(e, 0, elapsedTime);
            option(b, OPT_ELAPSED_TIME, e);
        }
        if (status >= 0) {
            option(b, OPT_STATUS_CODE, statusBody(status, statusMessage));
        }
        if (!dnsServers.isEmpty()) {
            option(b, OPT_DNS_SERVERS, addresses(dnsServers));
        }
        if (!ntpServers.isEmpty()) {
            ByteArrayOutputStream o = new ByteArrayOutputStream();
            for (Ipv6Address a : ntpServers) {
                option(o, NTP_SUBOPTION_SRV_ADDR, a.toBytes());
            }
            option(b, OPT_NTP_SERVER, o.toByteArray());
        }
        return b.toByteArray();
    }

    private static byte[] addresses(List<Ipv6Address> list) {
        byte[] out = new byte[16 * list.size()];
        for (int i = 0; i < list.size(); i++) {
            list.get(i).write(out, 16 * i);
        }
        return out;
    }

    private static byte[] statusBody(int code, String message) {
        byte[] m = message == null ? new byte[0] : message.getBytes(java.nio.charset.StandardCharsets.UTF_8);
        byte[] out = new byte[2 + m.length];
        Bytes.put16(out, 0, code);
        System.arraycopy(m, 0, out, 2, m.length);
        return out;
    }

    private static void option(ByteArrayOutputStream b, int code, byte[] data) {
        b.write(code >>> 8);
        b.write(code);
        b.write(data.length >>> 8);
        b.write(data.length);
        b.write(data, 0, data.length);
    }

    private static void put32(ByteArrayOutputStream b, long v) {
        b.write((int) (v >>> 24));
        b.write((int) (v >>> 16));
        b.write((int) (v >>> 8));
        b.write((int) v);
    }

    // ---- 解析 ----

    public static Dhcpv6Message parse(byte[] b) throws MalformedPacketException {
        if (b.length < 4) {
            throw new MalformedPacketException("DHCPv6 报文不到 4 字节");
        }
        int type = b[0] & 0xFF;
        if (type == 12 || type == 13) {
            throw new MalformedPacketException("中继报文不认");
        }
        int xid = ((b[1] & 0xFF) << 16) | ((b[2] & 0xFF) << 8) | (b[3] & 0xFF);
        byte[] clientId = null;
        byte[] serverId = null;
        List<IaNa> ia = new ArrayList<>();
        List<Integer> oro = new ArrayList<>();
        int preference = -1;
        int elapsed = -1;
        int status = -1;
        String statusMessage = null;
        List<Ipv6Address> dns = new ArrayList<>();
        List<Ipv6Address> ntp = new ArrayList<>();
        for (int[] o : options(b, 4, b.length)) {
            int code = o[0];
            int start = o[1];
            int len = o[2];
            switch (code) {
                case OPT_CLIENTID:
                case OPT_SERVERID:
                    if (len < 2 || len > 130) {
                        throw new MalformedPacketException("DUID 长度不对");
                    }
                    if (code == OPT_CLIENTID) {
                        clientId = Arrays.copyOfRange(b, start, start + len);
                    } else {
                        serverId = Arrays.copyOfRange(b, start, start + len);
                    }
                    break;
                case OPT_IA_NA:
                    ia.add(parseIaNa(b, start, len));
                    break;
                case OPT_ORO:
                    if ((len & 1) != 0) {
                        throw new MalformedPacketException("ORO 长度不是偶数");
                    }
                    for (int i = 0; i < len; i += 2) {
                        oro.add(Bytes.u16(b, start + i));
                    }
                    break;
                case OPT_PREFERENCE:
                    if (len != 1) {
                        throw new MalformedPacketException("PREFERENCE 长度不对");
                    }
                    preference = b[start] & 0xFF;
                    break;
                case OPT_ELAPSED_TIME:
                    if (len != 2) {
                        throw new MalformedPacketException("ELAPSED_TIME 长度不对");
                    }
                    elapsed = Bytes.u16(b, start);
                    break;
                case OPT_STATUS_CODE:
                    if (len < 2) {
                        throw new MalformedPacketException("STATUS_CODE 太短");
                    }
                    status = Bytes.u16(b, start);
                    statusMessage = new String(b, start + 2, len - 2, java.nio.charset.StandardCharsets.UTF_8);
                    break;
                case OPT_DNS_SERVERS:
                    if (len % 16 != 0) {
                        throw new MalformedPacketException("DNS 服务器选项长度不是 16 的倍数");
                    }
                    for (int i = 0; i < len; i += 16) {
                        dns.add(Ipv6Address.read(b, start + i));
                    }
                    break;
                case OPT_NTP_SERVER:
                    for (int[] s : options(b, start, start + len)) {
                        if (s[0] == NTP_SUBOPTION_SRV_ADDR) {
                            if (s[2] != 16) {
                                throw new MalformedPacketException("NTP 服务器地址子选项不是 16 字节");
                            }
                            ntp.add(Ipv6Address.read(b, s[1]));
                        }
                    }
                    break;
                default:
                    break;
            }
        }
        return new Dhcpv6Message(type, xid, clientId, serverId, ia, oro, preference, elapsed, status, statusMessage, dns, ntp);
    }

    private static IaNa parseIaNa(byte[] b, int start, int len) throws MalformedPacketException {
        if (len < 12) {
            throw new MalformedPacketException("IA_NA 太短");
        }
        List<IaAddress> addrs = new ArrayList<>();
        int status = -1;
        for (int[] o : options(b, start + 12, start + len)) {
            if (o[0] == OPT_IAADDR) {
                if (o[2] < 24) {
                    throw new MalformedPacketException("IAADDR 太短");
                }
                int st = -1;
                for (int[] s : options(b, o[1] + 24, o[1] + o[2])) {
                    if (s[0] == OPT_STATUS_CODE && s[2] >= 2) {
                        st = Bytes.u16(b, s[1]);
                    }
                }
                addrs.add(new IaAddress(Ipv6Address.read(b, o[1]), Bytes.u32(b, o[1] + 16), Bytes.u32(b, o[1] + 20), st));
            } else if (o[0] == OPT_STATUS_CODE) {
                if (o[2] < 2) {
                    throw new MalformedPacketException("STATUS_CODE 太短");
                }
                status = Bytes.u16(b, o[1]);
            }
        }
        return new IaNa(Bytes.u32(b, start), Bytes.u32(b, start + 4), Bytes.u32(b, start + 8), addrs, status);
    }

    /** 把 [from, to) 切成选项：每个是 {代码, 数据起点, 长度}；越界抛异常 */
    private static List<int[]> options(byte[] b, int from, int to) throws MalformedPacketException {
        List<int[]> out = new ArrayList<>();
        int i = from;
        while (i < to) {
            if (i + 4 > to) {
                throw new MalformedPacketException("DHCPv6 选项首部被截断");
            }
            int code = Bytes.u16(b, i);
            int len = Bytes.u16(b, i + 2);
            if (i + 4 + len > to) {
                throw new MalformedPacketException("DHCPv6 选项超出范围");
            }
            out.add(new int[]{code, i + 4, len});
            i += 4 + len;
            if (out.size() > 256) {
                throw new MalformedPacketException("DHCPv6 选项太多");
            }
        }
        return out;
    }

    public static String typeName(int type) {
        switch (type) {
            case SOLICIT:
                return "Solicit";
            case ADVERTISE:
                return "Advertise";
            case REQUEST:
                return "Request";
            case CONFIRM:
                return "Confirm";
            case RENEW:
                return "Renew";
            case REBIND:
                return "Rebind";
            case REPLY:
                return "Reply";
            case RELEASE:
                return "Release";
            case DECLINE:
                return "Decline";
            case INFORMATION_REQUEST:
                return "Information-request";
            default:
                return "类型 " + type;
        }
    }

    @Override
    public String toString() {
        return "DHCPv6[" + typeName(type) + " xid=" + Integer.toHexString(transactionId) + " IA_NA " + iaNa.size() + "]";
    }
}
