package moe.qingu.youran.server.net;

import moe.qingu.youran.api.network.Ipv6Address;
import moe.qingu.youran.api.network.packet.Dhcpv6Message;
import moe.qingu.youran.api.network.packet.MalformedPacketException;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * 玩家链路上的 DHCPv6 服务器（RFC 8415；服主 11-27：玩家自己的地址用 DHCPv6 分）。每条玩家链路上只有一个客户端，绑定按链路记
 * （{@link Binding}，同真实网络里按接口分配），客户端标识只用来核对报文是不是同一个客户端的。
 * <ul>
 * <li>Solicit → Advertise：从地址池（{@link Pool}，玩家 /96 里随机、同一时刻不重复）给这条链路预留一个地址，优先级 255；</li>
 * <li>Request → Reply：绑定。寿命、T1、T2 都是“无限”：租约到下线为止，下线时收回，客户端不用续（服主 11-27）；</li>
 * <li>Renew / Rebind：照现在的绑定回；没有绑定回 NoBinding。Confirm：地址是这条链路的回 Success，否则 NotOnLink。
 * Release：收回。Decline：这个地址这次在线期间不再给他，收回绑定。Information-request：只回 DNS、NTP；</li>
 * <li>DNS 服务器（选项 23）、NTP 服务器（选项 56）只在客户端的 ORO 里要了才给（RFC 3646、RFC 5908）。</li>
 * </ul>
 * 坏报文、标识对不上的报文只丢不回（第 16 节）。网络线程上用。
 */
final class Dhcpv6Server {
    /** 地址池 */
    interface Pool {
        /** 分一个没人用的地址给 {@code owner}；分不出为 null */
        Ipv6Address allocate(Object owner, Set<Ipv6Address> avoid);

        void release(Ipv6Address address, Object owner);
    }

    /** 一条链路上的绑定 */
    static final class Binding {
        final Object owner;
        /** 预留或绑定的地址；没有为 null */
        Ipv6Address address;
        boolean bound;
        byte[] clientId;
        long iaid;
        final Set<Ipv6Address> declined = new HashSet<>();

        Binding(Object owner) {
            this.owner = owner;
        }
    }

    private final byte[] duid;
    private final Pool pool;
    private final List<Ipv6Address> dns;
    private final List<Ipv6Address> ntp;
    volatile long handled;
    volatile long dropped;

    Dhcpv6Server(byte[] duid, Pool pool, List<Ipv6Address> dns, List<Ipv6Address> ntp) {
        this.duid = duid.clone();
        this.pool = pool;
        this.dns = Collections.unmodifiableList(new ArrayList<>(dns));
        this.ntp = Collections.unmodifiableList(new ArrayList<>(ntp));
    }

    byte[] duid() {
        return duid.clone();
    }

    /** 处理一个报文；返回要回的报文（不回为 null）。绑定的变化直接改在 {@code b} 上 */
    byte[] handle(Binding b, byte[] data) {
        Dhcpv6Message m;
        try {
            m = Dhcpv6Message.parse(data);
        } catch (MalformedPacketException e) {
            dropped++;
            return null;
        }
        Dhcpv6Message reply = handle(b, m);
        if (reply == null) {
            dropped++;
            return null;
        }
        handled++;
        return reply.encode();
    }

    private Dhcpv6Message handle(Binding b, Dhcpv6Message m) {
        // 第 16 节：各类报文要带 / 不能带的标识
        switch (m.type) {
            case Dhcpv6Message.SOLICIT:
            case Dhcpv6Message.CONFIRM:
            case Dhcpv6Message.REBIND:
                if (m.clientId == null || m.serverId != null) {
                    return null;
                }
                break;
            case Dhcpv6Message.REQUEST:
            case Dhcpv6Message.RENEW:
            case Dhcpv6Message.RELEASE:
            case Dhcpv6Message.DECLINE:
                if (m.clientId == null || m.serverId == null || !Arrays.equals(m.serverId, duid)) {
                    return null;
                }
                break;
            case Dhcpv6Message.INFORMATION_REQUEST:
                if (m.serverId != null && !Arrays.equals(m.serverId, duid)) {
                    return null;
                }
                return reply(Dhcpv6Message.REPLY, m, Collections.<Dhcpv6Message.IaNa>emptyList(), -1, -1);
            default:
                return null;
        }
        Dhcpv6Message.IaNa ia = m.iaNa.isEmpty() ? null : m.iaNa.get(0);
        switch (m.type) {
            case Dhcpv6Message.SOLICIT: {
                if (ia == null) {
                    return reply(Dhcpv6Message.ADVERTISE, m, Collections.<Dhcpv6Message.IaNa>emptyList(), Dhcpv6Message.STATUS_NO_ADDRS_AVAIL, 255);
                }
                if (!sameClient(b, m) && b.bound) {
                    // 换了一个客户端（客户端重开了）：旧的绑定收回
                    unbind(b);
                }
                b.clientId = m.clientId;
                b.iaid = ia.iaid;
                if (b.address == null) {
                    b.address = pool.allocate(b.owner, b.declined);
                }
                if (b.address == null) {
                    return reply(Dhcpv6Message.ADVERTISE, m, Collections.singletonList(
                            new Dhcpv6Message.IaNa(ia.iaid, 0, 0, Collections.<Dhcpv6Message.IaAddress>emptyList(), Dhcpv6Message.STATUS_NO_ADDRS_AVAIL)), -1, 255);
                }
                return reply(Dhcpv6Message.ADVERTISE, m, Collections.singletonList(granted(ia.iaid, b.address)), -1, 255);
            }
            case Dhcpv6Message.REQUEST: {
                if (ia == null) {
                    return reply(Dhcpv6Message.REPLY, m, Collections.<Dhcpv6Message.IaNa>emptyList(), Dhcpv6Message.STATUS_NO_ADDRS_AVAIL, -1);
                }
                if (!sameClient(b, m) && b.bound) {
                    unbind(b);
                }
                b.clientId = m.clientId;
                b.iaid = ia.iaid;
                if (b.address == null) {
                    b.address = pool.allocate(b.owner, b.declined);
                }
                if (b.address == null) {
                    return reply(Dhcpv6Message.REPLY, m, Collections.singletonList(
                            new Dhcpv6Message.IaNa(ia.iaid, 0, 0, Collections.<Dhcpv6Message.IaAddress>emptyList(), Dhcpv6Message.STATUS_NO_ADDRS_AVAIL)), -1, -1);
                }
                b.bound = true;
                return reply(Dhcpv6Message.REPLY, m, Collections.singletonList(granted(ia.iaid, b.address)), -1, -1);
            }
            case Dhcpv6Message.RENEW:
            case Dhcpv6Message.REBIND: {
                if (ia == null) {
                    return reply(Dhcpv6Message.REPLY, m, Collections.<Dhcpv6Message.IaNa>emptyList(), Dhcpv6Message.STATUS_NO_BINDING, -1);
                }
                if (!b.bound || !sameClient(b, m) || b.iaid != ia.iaid) {
                    return reply(Dhcpv6Message.REPLY, m, Collections.singletonList(
                            new Dhcpv6Message.IaNa(ia.iaid, 0, 0, Collections.<Dhcpv6Message.IaAddress>emptyList(), Dhcpv6Message.STATUS_NO_BINDING)), -1, -1);
                }
                return reply(Dhcpv6Message.REPLY, m, Collections.singletonList(granted(ia.iaid, b.address)), -1, -1);
            }
            case Dhcpv6Message.CONFIRM: {
                boolean ok = ia != null && !ia.addresses.isEmpty();
                if (ia != null) {
                    for (Dhcpv6Message.IaAddress a : ia.addresses) {
                        ok &= b.bound && a.address.equals(b.address);
                    }
                }
                return reply(Dhcpv6Message.REPLY, m, Collections.<Dhcpv6Message.IaNa>emptyList(),
                        ok ? Dhcpv6Message.STATUS_SUCCESS : Dhcpv6Message.STATUS_NOT_ON_LINK, -1);
            }
            case Dhcpv6Message.RELEASE:
                if (sameClient(b, m) && b.bound) {
                    unbind(b);
                }
                return reply(Dhcpv6Message.REPLY, m, Collections.<Dhcpv6Message.IaNa>emptyList(), Dhcpv6Message.STATUS_SUCCESS, -1);
            case Dhcpv6Message.DECLINE:
                if (sameClient(b, m) && ia != null) {
                    for (Dhcpv6Message.IaAddress a : ia.addresses) {
                        if (a.address.equals(b.address)) {
                            b.declined.add(a.address);
                            unbind(b);
                        }
                    }
                }
                return reply(Dhcpv6Message.REPLY, m, Collections.<Dhcpv6Message.IaNa>emptyList(), Dhcpv6Message.STATUS_SUCCESS, -1);
            default:
                return null;
        }
    }

    private static boolean sameClient(Binding b, Dhcpv6Message m) {
        return b.clientId != null && Arrays.equals(b.clientId, m.clientId);
    }

    /** 收回这条链路的绑定（地址放回池里） */
    void unbind(Binding b) {
        if (b.address != null) {
            pool.release(b.address, b.owner);
        }
        b.address = null;
        b.bound = false;
    }

    private static Dhcpv6Message.IaNa granted(long iaid, Ipv6Address a) {
        return new Dhcpv6Message.IaNa(iaid, Dhcpv6Message.INFINITY, Dhcpv6Message.INFINITY,
                Collections.singletonList(new Dhcpv6Message.IaAddress(a, Dhcpv6Message.INFINITY, Dhcpv6Message.INFINITY, -1)), -1);
    }

    private Dhcpv6Message reply(int type, Dhcpv6Message q, List<Dhcpv6Message.IaNa> ia, int status, int preference) {
        boolean wantDns = q.requestedOptions.contains(Dhcpv6Message.OPT_DNS_SERVERS);
        boolean wantNtp = q.requestedOptions.contains(Dhcpv6Message.OPT_NTP_SERVER);
        return new Dhcpv6Message(type, q.transactionId, q.clientId, duid, ia, Collections.<Integer>emptyList(), preference, -1, status, null,
                wantDns ? dns : Collections.<Ipv6Address>emptyList(), wantNtp ? ntp : Collections.<Ipv6Address>emptyList());
    }
}
