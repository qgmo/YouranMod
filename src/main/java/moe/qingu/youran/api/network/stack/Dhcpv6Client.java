package moe.qingu.youran.api.network.stack;

import moe.qingu.youran.api.network.Ipv6Address;
import moe.qingu.youran.api.network.packet.Dhcpv6Message;
import moe.qingu.youran.api.network.packet.MalformedPacketException;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;

/**
 * 一个网口上的 DHCPv6 客户端（RFC 8415，只要非临时地址 IA_NA；DNS 选项 RFC 3646、NTP 选项 RFC 5908）。
 * 网口收到置了 M 位的 RA 时自己开（{@link NetStack.Interface}），从网口的链路本地地址、UDP 546 发给 {@code ff02::1:2} 的 547。
 * <ul>
 * <li>四步：Solicit → Advertise → Request → Reply。Solicit 前随机等 0–{@value #SOL_MAX_DELAY} 毫秒；第一轮重传时间内收 Advertise，
 * 优先级 255 的马上用（第 18.2.1 节）；重传照第 15 节（初始时间、加倍、±10% 随机、上限、次数）。</li>
 * <li>拿到的地址加到网口上，先做重复地址检测；重复时发 Decline（第 18.2.8 节），再从 Solicit 重来。</li>
 * <li>T1 / T2 是有限值时到点 Renew、Rebind，有效期到了地址去掉、重来；服务器给的是“无限”（{@link Dhcpv6Message#INFINITY}）时不续。</li>
 * <li>DNS、NTP 服务器设到网口上（{@link NetStack.Interface#setDnsServer}、{@link NetStack.Interface#setNtpServer}）。</li>
 * </ul>
 * 客户端标识是 DUID-UUID（RFC 6355），UUID 用协议栈的随机数生成，这个客户端活着的时候不变。只在事件循环线程上用。
 */
public final class Dhcpv6Client {
    // RFC 8415 第 7.6 节（毫秒）
    static final long SOL_MAX_DELAY = 1000;
    static final long SOL_TIMEOUT = 1000;
    static final long SOL_MAX_RT = 3_600_000;
    static final long REQ_TIMEOUT = 1000;
    static final long REQ_MAX_RT = 30_000;
    static final int REQ_MAX_RC = 10;
    static final long REN_TIMEOUT = 10_000;
    static final long REN_MAX_RT = 600_000;
    static final long REB_TIMEOUT = 10_000;
    static final long REB_MAX_RT = 600_000;
    static final long DEC_TIMEOUT = 1000;
    static final int DEC_MAX_RC = 4;
    static final long IAID = 1;
    /** 要哪些选项：DNS 服务器、NTP 服务器 */
    static final List<Integer> ORO = Collections.unmodifiableList(Arrays.asList(Dhcpv6Message.OPT_DNS_SERVERS, Dhcpv6Message.OPT_NTP_SERVER));

    public enum State { WAITING, SOLICITING, REQUESTING, BOUND, RENEWING, REBINDING, DECLINING, STOPPED }

    /** 拿到的租约 */
    public static final class Lease {
        public final Ipv6Address address;
        /** 秒；{@link Dhcpv6Message#INFINITY} 为无限 */
        public final long preferredLifetime;
        public final long validLifetime;
        public final long t1;
        public final long t2;
        public final List<Ipv6Address> dnsServers;
        public final List<Ipv6Address> ntpServers;
        /** 服务器的 DUID（十六进制） */
        public final String server;
        /** 拿到的时间（事件循环的时钟） */
        public final long obtainedAt;

        Lease(Ipv6Address address, long preferredLifetime, long validLifetime, long t1, long t2, List<Ipv6Address> dnsServers,
              List<Ipv6Address> ntpServers, String server, long obtainedAt) {
            this.address = address;
            this.preferredLifetime = preferredLifetime;
            this.validLifetime = validLifetime;
            this.t1 = t1;
            this.t2 = t2;
            this.dnsServers = Collections.unmodifiableList(new ArrayList<>(dnsServers));
            this.ntpServers = Collections.unmodifiableList(new ArrayList<>(ntpServers));
            this.server = server;
            this.obtainedAt = obtainedAt;
        }

        static String seconds(long s) {
            return s == Dhcpv6Message.INFINITY ? "无限" : s + " 秒";
        }

        @Override
        public String toString() {
            return address + "（DHCPv6，有效期 " + seconds(validLifetime) + "，T1 " + seconds(t1) + "，T2 " + seconds(t2) + "；DNS " + dnsServers
                    + "，NTP " + ntpServers + "）";
        }
    }

    private final NetStack stack;
    private final NetStack.Interface iface;
    private final byte[] duid;
    private UdpSocket socket;
    private State state = State.WAITING;
    private int xid;
    private long exchangeStart;
    private long rt;
    private int count;
    private long deadline;
    private EventLoop.Timer timer;
    private EventLoop.Timer lifetimeTimer;
    private Dhcpv6Message best;
    private boolean firstRtOver;
    private byte[] serverId;
    private Lease lease;
    /** 正在做重复地址检测的地址（拿到了、还不能用） */
    private Ipv6Address pending;
    private Lease pendingLease;
    private Ipv6Address declining;

    // 统计
    public long sent;
    public long received;
    public long dropped;

    Dhcpv6Client(NetStack stack, NetStack.Interface iface) {
        this.stack = stack;
        this.iface = iface;
        this.duid = Dhcpv6Message.uuidDuid(stack.random.nextLong(), stack.random.nextLong());
    }

    public State state() {
        return state;
    }

    /** 现在的租约（地址过了重复地址检测以后才有）；没有为 null */
    public Lease lease() {
        return lease;
    }

    /** 客户端标识（DUID） */
    public byte[] duid() {
        return duid.clone();
    }

    void start() {
        Ipv6Address ll = iface.linkLocalSource();
        if (ll == null || state != State.WAITING) {
            return;
        }
        stack.openUdp(ll, Dhcpv6Message.CLIENT_PORT, (s, from, fromPort, to, data) -> onDatagram(from, fromPort, data)).whenComplete((s, err) -> {
            if (err != null) {
                NetStack.LOG.warn("游戏内网络：网口 {} 开不了 DHCPv6 客户端：{}", iface.name(), err.toString());
                state = State.STOPPED;
                return;
            }
            socket = s;
            if (state == State.STOPPED) {
                s.close();
                return;
            }
            solicitLater();
        });
    }

    void stop() {
        state = State.STOPPED;
        cancelTimer();
        cancel(lifetimeTimer);
        if (socket != null) {
            socket.close();
        }
    }

    private void cancelTimer() {
        cancel(timer);
        timer = null;
    }

    private static void cancel(EventLoop.Timer t) {
        if (t != null) {
            t.cancel();
        }
    }

    /** −0.1 到 0.1 的随机数（第 15 节的 RAND）；{@code positive} 时大于 0 */
    private double rand(boolean positive) {
        double r = stack.random.nextDouble() * 0.1;
        return positive ? Math.max(r, 1e-6) : r * 2 - 0.1;
    }

    // ---- 各种交换 ----

    private void solicitLater() {
        state = State.SOLICITING;
        best = null;
        firstRtOver = false;
        serverId = null;
        long delay = (long) (stack.random.nextDouble() * SOL_MAX_DELAY);
        cancelTimer();
        timer = stack.loop.schedule(this::beginSolicit, delay);
    }

    private void beginSolicit() {
        if (state != State.SOLICITING) {
            return;
        }
        newExchange();
        rt = (long) (SOL_TIMEOUT + rand(true) * SOL_TIMEOUT);
        transmit();
    }

    private void newExchange() {
        xid = stack.random.nextInt(0x1000000);
        exchangeStart = stack.loop.nowMillis();
        count = 0;
    }

    private void beginRequest(Dhcpv6Message adv) {
        state = State.REQUESTING;
        serverId = adv.serverId;
        best = adv;
        newExchange();
        rt = (long) (REQ_TIMEOUT + rand(false) * REQ_TIMEOUT);
        transmit();
    }

    private void beginRenew() {
        if (state != State.BOUND || lease == null) {
            return;
        }
        state = State.RENEWING;
        newExchange();
        rt = (long) (REN_TIMEOUT + rand(false) * REN_TIMEOUT);
        deadline = lease.t2 == Dhcpv6Message.INFINITY ? Long.MAX_VALUE : lease.obtainedAt + lease.t2 * 1000;
        transmit();
    }

    private void beginRebind() {
        if ((state != State.BOUND && state != State.RENEWING) || lease == null) {
            return;
        }
        state = State.REBINDING;
        newExchange();
        rt = (long) (REB_TIMEOUT + rand(false) * REB_TIMEOUT);
        deadline = lease.validLifetime == Dhcpv6Message.INFINITY ? Long.MAX_VALUE : lease.obtainedAt + lease.validLifetime * 1000;
        transmit();
    }

    private void beginDecline(Ipv6Address a) {
        state = State.DECLINING;
        declining = a;
        newExchange();
        rt = (long) (DEC_TIMEOUT + rand(false) * DEC_TIMEOUT);
        transmit();
    }

    /** 发这一轮的报文，排下一次重传 */
    private void transmit() {
        if (socket == null || state == State.STOPPED) {
            return;
        }
        count++;
        int elapsed = (int) Math.min(0xFFFF, (stack.loop.nowMillis() - exchangeStart) / 10);
        Dhcpv6Message m;
        switch (state) {
            case SOLICITING:
                m = message(Dhcpv6Message.SOLICIT, null, Collections.<Dhcpv6Message.IaAddress>emptyList(), elapsed, true);
                break;
            case REQUESTING:
                m = message(Dhcpv6Message.REQUEST, serverId, best.iaNa.get(0).addresses, elapsed, true);
                break;
            case RENEWING:
                m = message(Dhcpv6Message.RENEW, serverId, addressOf(lease), elapsed, true);
                break;
            case REBINDING:
                m = message(Dhcpv6Message.REBIND, null, addressOf(lease), elapsed, true);
                break;
            case DECLINING:
                m = message(Dhcpv6Message.DECLINE, serverId, Collections.singletonList(new Dhcpv6Message.IaAddress(declining, 0, 0, -1)), elapsed, false);
                break;
            default:
                return;
        }
        socket.send(Ipv6Address.ALL_DHCP_SERVERS, Dhcpv6Message.SERVER_PORT, m.encode());
        sent++;
        long wait = rt;
        cancelTimer();
        timer = stack.loop.schedule(this::retransmitTimeout, wait);
    }

    private static List<Dhcpv6Message.IaAddress> addressOf(Lease l) {
        return Collections.singletonList(new Dhcpv6Message.IaAddress(l.address, l.preferredLifetime, l.validLifetime, -1));
    }

    private Dhcpv6Message message(int type, byte[] server, List<Dhcpv6Message.IaAddress> addrs, int elapsed, boolean oro) {
        Dhcpv6Message.IaNa ia = new Dhcpv6Message.IaNa(IAID, 0, 0, addrs, -1);
        return new Dhcpv6Message(type, xid, duid, server, Collections.singletonList(ia), oro ? ORO : Collections.<Integer>emptyList(),
                -1, elapsed, -1, null, Collections.<Ipv6Address>emptyList(), Collections.<Ipv6Address>emptyList());
    }

    private void retransmitTimeout() {
        timer = null;
        switch (state) {
            case SOLICITING:
                firstRtOver = true;
                if (best != null) {
                    beginRequest(best);
                    return;
                }
                rt = next(SOL_MAX_RT);
                transmit();
                return;
            case REQUESTING:
                if (count >= REQ_MAX_RC) {
                    solicitLater();
                    return;
                }
                rt = next(REQ_MAX_RT);
                transmit();
                return;
            case RENEWING:
                if (stack.loop.nowMillis() >= deadline) {
                    beginRebind();
                    return;
                }
                rt = Math.min(next(REN_MAX_RT), Math.max(1, deadline - stack.loop.nowMillis()));
                transmit();
                return;
            case REBINDING:
                if (stack.loop.nowMillis() >= deadline) {
                    expire();
                    return;
                }
                rt = Math.min(next(REB_MAX_RT), Math.max(1, deadline - stack.loop.nowMillis()));
                transmit();
                return;
            case DECLINING:
                if (count >= DEC_MAX_RC) {
                    solicitLater();
                    return;
                }
                rt = next(0);
                transmit();
                return;
            default:
        }
    }

    /** 下一次的重传时间（第 15 节：2 × RT + RAND × RT，超过上限时取上限 + RAND × 上限） */
    private long next(long mrt) {
        long r = (long) (2 * rt + rand(false) * rt);
        if (mrt > 0 && r > mrt) {
            r = (long) (mrt + rand(false) * mrt);
        }
        return r;
    }

    // ---- 收 ----

    private void onDatagram(Ipv6Address from, int fromPort, byte[] data) {
        if (fromPort != Dhcpv6Message.SERVER_PORT || state == State.STOPPED) {
            return;
        }
        Dhcpv6Message m;
        try {
            m = Dhcpv6Message.parse(data);
        } catch (MalformedPacketException e) {
            dropped++;
            return;
        }
        // 第 16.3、16.10 节：事务号对得上、带服务器标识、客户端标识是自己的
        if (m.transactionId != xid || m.serverId == null || m.clientId == null || !Arrays.equals(m.clientId, duid)) {
            dropped++;
            return;
        }
        received++;
        if (m.type == Dhcpv6Message.ADVERTISE && state == State.SOLICITING) {
            onAdvertise(m);
        } else if (m.type == Dhcpv6Message.REPLY) {
            onReply(m);
        } else {
            dropped++;
        }
    }

    private static Dhcpv6Message.IaAddress usableAddress(Dhcpv6Message m) {
        for (Dhcpv6Message.IaNa ia : m.iaNa) {
            if (ia.iaid != IAID || (ia.status >= 0 && ia.status != Dhcpv6Message.STATUS_SUCCESS)) {
                continue;
            }
            // T1 > T2 > 0 的 IA 不要（第 21.4 节）
            if (ia.t1 > 0 && ia.t2 > 0 && ia.t1 > ia.t2) {
                continue;
            }
            for (Dhcpv6Message.IaAddress a : ia.addresses) {
                if (a.validLifetime > 0 && a.preferredLifetime <= a.validLifetime && (a.status < 0 || a.status == Dhcpv6Message.STATUS_SUCCESS)
                        && !a.address.isMulticast() && !a.address.isLinkLocal() && !a.address.isUnspecified()) {
                    return a;
                }
            }
        }
        return null;
    }

    private void onAdvertise(Dhcpv6Message m) {
        // 第 18.2.9 节：带 NoAddrsAvail 状态码的、没有能用的地址的不要
        if (m.status == Dhcpv6Message.STATUS_NO_ADDRS_AVAIL || usableAddress(m) == null) {
            return;
        }
        int pref = Math.max(0, m.preference);
        if (pref == 255 || firstRtOver) {
            cancelTimer();
            beginRequest(m);
            return;
        }
        if (best == null || pref > Math.max(0, best.preference)) {
            best = m;
        }
    }

    private void onReply(Dhcpv6Message m) {
        switch (state) {
            case REQUESTING:
            case RENEWING:
            case REBINDING: {
                if (m.status == Dhcpv6Message.STATUS_NOT_ON_LINK || m.status == Dhcpv6Message.STATUS_NO_ADDRS_AVAIL) {
                    solicitLater();
                    return;
                }
                Dhcpv6Message.IaAddress a = usableAddress(m);
                if (a == null) {
                    // NoBinding、NoAddrsAvail 等：从头再来
                    solicitLater();
                    return;
                }
                cancelTimer();
                serverId = m.serverId;
                bind(m, a);
                return;
            }
            case DECLINING:
                cancelTimer();
                solicitLater();
                return;
            default:
                dropped++;
        }
    }

    private void bind(Dhcpv6Message m, Dhcpv6Message.IaAddress a) {
        Dhcpv6Message.IaNa ia = m.iaNa.get(0);
        for (Dhcpv6Message.IaNa x : m.iaNa) {
            if (x.iaid == IAID) {
                ia = x;
            }
        }
        long t1 = ia.t1;
        long t2 = ia.t2;
        // T1 / T2 为 0 时由客户端定：首选寿命的 0.5、0.8（第 21.4 节建议）
        if (t1 == 0) {
            t1 = a.preferredLifetime == Dhcpv6Message.INFINITY ? Dhcpv6Message.INFINITY : a.preferredLifetime / 2;
        }
        if (t2 == 0) {
            t2 = a.preferredLifetime == Dhcpv6Message.INFINITY ? Dhcpv6Message.INFINITY : a.preferredLifetime * 4 / 5;
        }
        Lease l = new Lease(a.address, a.preferredLifetime, a.validLifetime, t1, t2, m.dnsServers, m.ntpServers, hex(m.serverId),
                stack.loop.nowMillis());
        if (lease != null && lease.address.equals(a.address)) {
            // 续上了：只换时间
            lease = l;
            state = State.BOUND;
            applyOptions(l);
            scheduleLifetimes(l);
            return;
        }
        if (lease != null) {
            iface.removeAddress(lease.address);
            lease = null;
        }
        pending = a.address;
        pendingLease = l;
        state = State.BOUND;
        scheduleLifetimes(l);
        iface.addAddress(a.address);
    }

    private void applyOptions(Lease l) {
        iface.setDnsServer(l.dnsServers.isEmpty() ? null : l.dnsServers.get(0));
        iface.setNtpServer(l.ntpServers.isEmpty() ? null : l.ntpServers.get(0));
    }

    private void scheduleLifetimes(Lease l) {
        cancel(lifetimeTimer);
        lifetimeTimer = null;
        if (l.t1 != Dhcpv6Message.INFINITY) {
            lifetimeTimer = stack.loop.schedule(this::beginRenew, l.t1 * 1000);
        } else if (l.t2 != Dhcpv6Message.INFINITY) {
            lifetimeTimer = stack.loop.schedule(this::beginRebind, l.t2 * 1000);
        } else if (l.validLifetime != Dhcpv6Message.INFINITY) {
            lifetimeTimer = stack.loop.schedule(this::expire, l.validLifetime * 1000);
        }
    }

    private void expire() {
        if (lease != null) {
            iface.removeAddress(lease.address);
            lease = null;
        }
        solicitLater();
    }

    /** 网口告诉它：地址过了重复地址检测 */
    void addressReady(Ipv6Address a) {
        if (a.equals(pending)) {
            lease = pendingLease;
            pending = null;
            pendingLease = null;
            applyOptions(lease);
        }
    }

    /** 网口告诉它：地址重复，没启用 */
    void addressDuplicated(Ipv6Address a) {
        if (a.equals(pending)) {
            pending = null;
            pendingLease = null;
            cancel(lifetimeTimer);
            beginDecline(a);
        }
    }

    private static String hex(byte[] b) {
        StringBuilder sb = new StringBuilder();
        for (byte x : b) {
            sb.append(String.format("%02x", x & 0xFF));
        }
        return sb.toString();
    }
}
