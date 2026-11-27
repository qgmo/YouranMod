package moe.qingu.youran.api.network.packet;

import moe.qingu.youran.api.network.Ipv6Address;

import java.io.ByteArrayOutputStream;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * 邻居发现报文（RFC 4861）：路由器请求（RS，133）、路由器通告（RA，134）、邻居请求（NS，135）、邻居通告（NA，136）。
 * 重定向（137）不认（这张网里没有发重定向的路由器，收到的按不认识的 ICMPv6 丢掉）。
 * <p>
 * 选项：源 / 目标链路层地址（1 / 2）、前缀信息（3）、MTU（5）、递归 DNS 服务器（RDNSS，25，RFC 8106）；别的选项跳过。
 * 链路层地址按 6 字节（48 位）编码；没有链路层地址的链路（点对点）上不带这两个选项（RFC 4861 第 4.6.1 节）。
 * <p>
 * {@link #parse} 按 RFC 4861 第 6.1、7.1 节验证：跳数必须是 255（防止从别的链路转发进来的伪造报文）、代码 0、长度够、
 * 选项长度不为 0；RA 的源地址必须是链路本地地址；NS / NA 的目标不能是组播；源地址未指定的 NS 必须发给请求节点组播地址、不带源链路层地址；
 * 发给组播地址的 NA 不能置 S 位。不合格的抛 {@link MalformedPacketException}（调用方静默丢掉）。
 */
public final class NdpMessage {
    public static final int ROUTER_SOLICITATION = 133;
    public static final int ROUTER_ADVERTISEMENT = 134;
    public static final int NEIGHBOR_SOLICITATION = 135;
    public static final int NEIGHBOR_ADVERTISEMENT = 136;
    /** 邻居发现报文的跳数：发的时候填 255，收的时候不是 255 的丢掉 */
    public static final int HOP_LIMIT = 255;

    static final int OPT_SOURCE_LLA = 1;
    static final int OPT_TARGET_LLA = 2;
    static final int OPT_PREFIX = 3;
    static final int OPT_MTU = 5;
    static final int OPT_RDNSS = 25;
    /** 表示“没有链路层地址” */
    public static final long NO_LINK_ADDRESS = -1;
    /** 寿命“无限”（前缀、RDNSS 的 0xFFFFFFFF） */
    public static final long INFINITE = 0xFFFFFFFFL;

    public final int type;

    // ---- RA ----
    public final int curHopLimit;
    /** M 位：地址用 DHCPv6 拿（RFC 4861 第 4.2 节） */
    public final boolean managed;
    /** O 位：别的配置（DNS 等）用 DHCPv6 拿 */
    public final boolean otherConfig;
    /** 当默认路由器的时长（秒），0 表示不是默认路由器 */
    public final int routerLifetime;
    public final long reachableTime;
    public final long retransTimer;

    // ---- NS / NA ----
    public final Ipv6Address target;
    public final boolean router;
    public final boolean solicited;
    public final boolean override;

    // ---- 选项 ----
    public final long sourceLinkAddress;
    public final long targetLinkAddress;
    public final List<Prefix> prefixes;
    /** MTU 选项；没有为 -1 */
    public final long mtu;
    public final List<Rdnss> rdnss;

    /** 前缀信息选项（RFC 4861 第 4.6.2 节） */
    public static final class Prefix {
        public final Ipv6Address prefix;
        public final int length;
        /** L 位：在链路上 */
        public final boolean onLink;
        /** A 位：可以用来自动配置地址（这张网不做 SLAAC，发的都是 0） */
        public final boolean autonomous;
        public final long validLifetime;
        public final long preferredLifetime;

        public Prefix(Ipv6Address prefix, int length, boolean onLink, boolean autonomous, long validLifetime, long preferredLifetime) {
            this.prefix = prefix;
            this.length = length;
            this.onLink = onLink;
            this.autonomous = autonomous;
            this.validLifetime = validLifetime & 0xFFFFFFFFL;
            this.preferredLifetime = preferredLifetime & 0xFFFFFFFFL;
        }
    }

    /** 递归 DNS 服务器选项（RFC 8106 第 5.1 节） */
    public static final class Rdnss {
        public final long lifetime;
        public final List<Ipv6Address> servers;

        public Rdnss(long lifetime, List<Ipv6Address> servers) {
            this.lifetime = lifetime & 0xFFFFFFFFL;
            this.servers = Collections.unmodifiableList(new ArrayList<>(servers));
        }
    }

    private NdpMessage(int type, int curHopLimit, boolean managed, boolean otherConfig, int routerLifetime, long reachableTime, long retransTimer,
                       Ipv6Address target, boolean router, boolean solicited, boolean override,
                       long sourceLinkAddress, long targetLinkAddress, List<Prefix> prefixes, long mtu, List<Rdnss> rdnss) {
        this.type = type;
        this.curHopLimit = curHopLimit;
        this.managed = managed;
        this.otherConfig = otherConfig;
        this.routerLifetime = routerLifetime;
        this.reachableTime = reachableTime;
        this.retransTimer = retransTimer;
        this.target = target;
        this.router = router;
        this.solicited = solicited;
        this.override = override;
        this.sourceLinkAddress = sourceLinkAddress;
        this.targetLinkAddress = targetLinkAddress;
        this.prefixes = Collections.unmodifiableList(new ArrayList<>(prefixes));
        this.mtu = mtu;
        this.rdnss = Collections.unmodifiableList(new ArrayList<>(rdnss));
    }

    // ---- 造报文 ----

    public static NdpMessage routerSolicitation(long sourceLinkAddress) {
        return new NdpMessage(ROUTER_SOLICITATION, 0, false, false, 0, 0, 0, null, false, false, false,
                sourceLinkAddress, NO_LINK_ADDRESS, Collections.<Prefix>emptyList(), -1, Collections.<Rdnss>emptyList());
    }

    public static NdpMessage routerAdvertisement(int curHopLimit, boolean managed, boolean otherConfig, int routerLifetime, long reachableTime,
                                                 long retransTimer, long sourceLinkAddress, List<Prefix> prefixes, long mtu, List<Rdnss> rdnss) {
        return new NdpMessage(ROUTER_ADVERTISEMENT, curHopLimit, managed, otherConfig, routerLifetime, reachableTime, retransTimer, null,
                false, false, false, sourceLinkAddress, NO_LINK_ADDRESS, prefixes, mtu, rdnss);
    }

    public static NdpMessage neighborSolicitation(Ipv6Address target, long sourceLinkAddress) {
        return new NdpMessage(NEIGHBOR_SOLICITATION, 0, false, false, 0, 0, 0, target, false, false, false,
                sourceLinkAddress, NO_LINK_ADDRESS, Collections.<Prefix>emptyList(), -1, Collections.<Rdnss>emptyList());
    }

    public static NdpMessage neighborAdvertisement(Ipv6Address target, boolean router, boolean solicited, boolean override, long targetLinkAddress) {
        return new NdpMessage(NEIGHBOR_ADVERTISEMENT, 0, false, false, 0, 0, 0, target, router, solicited, override,
                NO_LINK_ADDRESS, targetLinkAddress, Collections.<Prefix>emptyList(), -1, Collections.<Rdnss>emptyList());
    }

    /** 编成 IPv6 包（跳数 255） */
    public Ipv6Packet toPacket(Ipv6Address src, Ipv6Address dst) {
        ByteArrayOutputStream b = new ByteArrayOutputStream();
        b.write(type);
        b.write(0);
        b.write(0);
        b.write(0);
        switch (type) {
            case ROUTER_SOLICITATION:
                put32(b, 0);
                break;
            case ROUTER_ADVERTISEMENT:
                b.write(curHopLimit);
                b.write((managed ? 0x80 : 0) | (otherConfig ? 0x40 : 0));
                put16(b, routerLifetime);
                put32(b, reachableTime);
                put32(b, retransTimer);
                break;
            case NEIGHBOR_SOLICITATION:
                put32(b, 0);
                b.write(target.toBytes(), 0, 16);
                break;
            default:
                b.write((router ? 0x80 : 0) | (solicited ? 0x40 : 0) | (override ? 0x20 : 0));
                b.write(0);
                b.write(0);
                b.write(0);
                b.write(target.toBytes(), 0, 16);
                break;
        }
        if (sourceLinkAddress != NO_LINK_ADDRESS) {
            linkAddressOption(b, OPT_SOURCE_LLA, sourceLinkAddress);
        }
        if (targetLinkAddress != NO_LINK_ADDRESS) {
            linkAddressOption(b, OPT_TARGET_LLA, targetLinkAddress);
        }
        for (Prefix p : prefixes) {
            b.write(OPT_PREFIX);
            b.write(4);
            b.write(p.length);
            b.write((p.onLink ? 0x80 : 0) | (p.autonomous ? 0x40 : 0));
            put32(b, p.validLifetime);
            put32(b, p.preferredLifetime);
            put32(b, 0);
            b.write(p.prefix.toBytes(), 0, 16);
        }
        if (mtu >= 0) {
            b.write(OPT_MTU);
            b.write(1);
            put16(b, 0);
            put32(b, mtu);
        }
        for (Rdnss r : rdnss) {
            b.write(OPT_RDNSS);
            b.write(1 + 2 * r.servers.size());
            put16(b, 0);
            put32(b, r.lifetime);
            for (Ipv6Address a : r.servers) {
                b.write(a.toBytes(), 0, 16);
            }
        }
        byte[] body = b.toByteArray();
        Icmpv6Message m = new Icmpv6Message(type, 0, java.util.Arrays.copyOfRange(body, 4, body.length));
        return new Ipv6Packet(0, 0, Ipv6Packet.PROTO_ICMPV6, HOP_LIMIT, src, dst, m.encode(src, dst));
    }

    private static void linkAddressOption(ByteArrayOutputStream b, int kind, long lla) {
        b.write(kind);
        b.write(1);
        for (int i = 5; i >= 0; i--) {
            b.write((int) (lla >>> (8 * i)));
        }
    }

    private static void put16(ByteArrayOutputStream b, int v) {
        b.write(v >>> 8);
        b.write(v);
    }

    private static void put32(ByteArrayOutputStream b, long v) {
        put16(b, (int) (v >>> 16) & 0xFFFF);
        put16(b, (int) v & 0xFFFF);
    }

    // ---- 解析 ----

    /** 是不是邻居发现报文的类型（133–137） */
    public static boolean isNdpType(int icmpType) {
        return icmpType >= ROUTER_SOLICITATION && icmpType <= 137;
    }

    /** 解析并验证（{@code m} 是已经过校验和检查的 ICMPv6 报文）；不合格抛异常 */
    public static NdpMessage parse(Ipv6Packet p, Icmpv6Message m) throws MalformedPacketException {
        if (p.hopLimit != HOP_LIMIT) {
            throw new MalformedPacketException("邻居发现报文的跳数不是 255（" + p.hopLimit + "）");
        }
        if (m.code != 0) {
            throw new MalformedPacketException("邻居发现报文的代码不是 0");
        }
        byte[] b = m.body;
        int fixed;
        switch (m.type) {
            case ROUTER_SOLICITATION:
                fixed = 4;
                break;
            case ROUTER_ADVERTISEMENT:
                fixed = 12;
                break;
            case NEIGHBOR_SOLICITATION:
            case NEIGHBOR_ADVERTISEMENT:
                fixed = 20;
                break;
            default:
                throw new MalformedPacketException("不认识的邻居发现报文（类型 " + m.type + "）");
        }
        if (b.length < fixed) {
            throw new MalformedPacketException("邻居发现报文太短");
        }
        long sll = NO_LINK_ADDRESS;
        long tll = NO_LINK_ADDRESS;
        long mtu = -1;
        List<Prefix> prefixes = new ArrayList<>();
        List<Rdnss> rdnss = new ArrayList<>();
        int i = fixed;
        while (i < b.length) {
            if (i + 2 > b.length) {
                throw new MalformedPacketException("邻居发现选项被截断");
            }
            int kind = b[i] & 0xFF;
            int len = (b[i + 1] & 0xFF) * 8;
            if (len == 0) {
                throw new MalformedPacketException("邻居发现选项长度为 0");
            }
            if (i + len > b.length) {
                throw new MalformedPacketException("邻居发现选项超出报文");
            }
            switch (kind) {
                case OPT_SOURCE_LLA:
                case OPT_TARGET_LLA:
                    if (len == 8) {
                        long v = 0;
                        for (int k = 0; k < 6; k++) {
                            v = (v << 8) | (b[i + 2 + k] & 0xFF);
                        }
                        if (kind == OPT_SOURCE_LLA) {
                            sll = v;
                        } else {
                            tll = v;
                        }
                    }
                    break;
                case OPT_PREFIX:
                    if (len != 32) {
                        throw new MalformedPacketException("前缀信息选项长度不对");
                    }
                    int plen = b[i + 2] & 0xFF;
                    if (plen > 128) {
                        throw new MalformedPacketException("前缀长度超过 128");
                    }
                    int flags = b[i + 3] & 0xFF;
                    prefixes.add(new Prefix(Ipv6Address.read(b, i + 16), plen, (flags & 0x80) != 0, (flags & 0x40) != 0,
                            Bytes.u32(b, i + 4), Bytes.u32(b, i + 8)));
                    break;
                case OPT_MTU:
                    if (len != 8) {
                        throw new MalformedPacketException("MTU 选项长度不对");
                    }
                    mtu = Bytes.u32(b, i + 4);
                    break;
                case OPT_RDNSS: {
                    // 长度至少 3（一个地址），且是奇数（RFC 8106 第 5.1 节）；不对的这个选项不要
                    int units = len / 8;
                    if (units >= 3 && (units & 1) == 1) {
                        List<Ipv6Address> servers = new ArrayList<>();
                        for (int k = i + 8; k + 16 <= i + len; k += 16) {
                            servers.add(Ipv6Address.read(b, k));
                        }
                        rdnss.add(new Rdnss(Bytes.u32(b, i + 4), servers));
                    }
                    break;
                }
                default:
                    break;
            }
            i += len;
        }
        switch (m.type) {
            case ROUTER_SOLICITATION:
                if (p.source.isUnspecified() && sll != NO_LINK_ADDRESS) {
                    throw new MalformedPacketException("源地址未指定的 RS 不能带源链路层地址");
                }
                return routerSolicitation(sll);
            case ROUTER_ADVERTISEMENT:
                if (!p.source.isLinkLocal()) {
                    throw new MalformedPacketException("RA 的源地址不是链路本地地址");
                }
                return routerAdvertisement(b[0] & 0xFF, (b[1] & 0x80) != 0, (b[1] & 0x40) != 0, Bytes.u16(b, 2), Bytes.u32(b, 4),
                        Bytes.u32(b, 8), sll, prefixes, mtu, rdnss);
            case NEIGHBOR_SOLICITATION: {
                Ipv6Address target = Ipv6Address.read(b, 4);
                if (target.isMulticast()) {
                    throw new MalformedPacketException("NS 的目标是组播地址");
                }
                if (p.source.isUnspecified()) {
                    if (!p.destination.equals(target.solicitedNode())) {
                        throw new MalformedPacketException("源地址未指定的 NS 没有发给请求节点组播地址");
                    }
                    if (sll != NO_LINK_ADDRESS) {
                        throw new MalformedPacketException("源地址未指定的 NS 不能带源链路层地址");
                    }
                }
                return neighborSolicitation(target, sll);
            }
            default: {
                Ipv6Address target = Ipv6Address.read(b, 4);
                if (target.isMulticast()) {
                    throw new MalformedPacketException("NA 的目标是组播地址");
                }
                boolean s = (b[0] & 0x40) != 0;
                if (s && p.destination.isMulticast()) {
                    throw new MalformedPacketException("发给组播地址的 NA 置了 S 位");
                }
                return neighborAdvertisement(target, (b[0] & 0x80) != 0, s, (b[0] & 0x20) != 0, tll);
            }
        }
    }

    @Override
    public String toString() {
        switch (type) {
            case ROUTER_SOLICITATION:
                return "RS";
            case ROUTER_ADVERTISEMENT:
                return "RA[M=" + managed + " O=" + otherConfig + " 寿命 " + routerLifetime + " 前缀 " + prefixes.size() + " MTU " + mtu + "]";
            case NEIGHBOR_SOLICITATION:
                return "NS[" + target + "]";
            default:
                return "NA[" + target + (router ? " R" : "") + (solicited ? " S" : "") + (override ? " O" : "") + "]";
        }
    }
}
