package moe.qingu.youran.api.network.stack;

import moe.qingu.youran.api.network.Ipv6Address;
import moe.qingu.youran.api.network.packet.Icmpv6Message;
import moe.qingu.youran.api.network.packet.Ipv6Packet;
import moe.qingu.youran.api.network.packet.MalformedPacketException;
import moe.qingu.youran.api.network.packet.NdpMessage;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.function.Predicate;

/**
 * 一个网口上的邻居发现（RFC 4861）与重复地址检测（RFC 4862 第 5.4 节；不做 SLAAC）。主机（协议栈的网口、原始主机的代答）
 * 和路由器的网口共用这一份，只在 {@link #isRouter()} 上不同。
 * <ul>
 * <li><b>地址</b>：加上的地址先是“暂定”的，发 {@value #DUP_ADDR_DETECT_TRANSMITS} 个源地址未指定的 NS，等 {@link #retransTimer} 毫秒没人应才算数
 * （{@link Events#addressReady}）；有人应（NA）或别人同时也在检测同一个地址（NS），这个地址不启用（{@link Events#addressDuplicated}）。
 * 网口起来以后发的第一个报文先随机等 0–{@value #MAX_RTR_SOLICITATION_DELAY} 毫秒（RFC 4862 第 5.4.2 节）。暂定的地址不能当源地址、不收发给它的包。</li>
 * <li><b>邻居缓存</b>（第 7.3 节）：要发给链路上的某个地址（下一跳）时先查缓存，没有就发组播 NS 解析，等 NA；包在解析期间排队（每个邻居最多
 * {@value #MAX_QUEUED} 个）；{@value #MAX_MULTICAST_SOLICIT} 次没人应算解析失败，排着的包交给 {@link Events#resolutionFailed}（调用方回“地址不可达”）。
 * 可达状态过 {@link #reachableTime} 毫秒变“陈旧”，陈旧以后再发包先照发、{@value #DELAY_FIRST_PROBE_TIME} 毫秒后单播 NS 探测，
 * {@value #MAX_UNICAST_SOLICIT} 次没人应就删掉。</li>
 * <li><b>主机</b>：收 RA，记默认路由器（按路由器寿命）、在链路上的前缀、MTU、RDNSS；发 RS（第 6.3.7 节：先随机等，最多 {@value #MAX_RTR_SOLICITATIONS} 次）。
 * 选下一跳（{@link #nextHop}）：链路本地、组播、在链路上的前缀里的地址直接发；别的发给默认路由器；没有默认路由器时没有路由（RFC 4861 第 5.2 节，
 * 不假定在链路上）。</li>
 * <li><b>路由器</b>：回 RS、定时发 RA（{@link #advertise}；第 6.2 节的初始通告、间隔随机、两次组播 RA 至少隔 {@value #MIN_DELAY_BETWEEN_RAS} 毫秒）；
 * NA 置 R 位；不处理 RA。</li>
 * </ul>
 * 没有链路层地址的链路（点对点：路由器之间、玩家客户端与服务器之间）上 {@code ownLinkAddress} 为 {@link NdpMessage#NO_LINK_ADDRESS}，报文不带链路层地址选项
 * （RFC 4861 第 4.6.1 节），NS / NA 照样做（确认对方在、检测重复地址）。
 * <p>
 * 线程：只在事件循环线程上用（所有方法都要在那个线程上调用）。时间、随机数都从构造时给的事件循环、随机数来，单元测试用手动的事件循环与固定种子。
 */
public final class NeighborDiscovery {
    // RFC 4861 第 10 节的常量（毫秒）
    public static final long MAX_RTR_SOLICITATION_DELAY = 1000;
    public static final long RTR_SOLICITATION_INTERVAL = 4000;
    public static final int MAX_RTR_SOLICITATIONS = 3;
    public static final int MAX_MULTICAST_SOLICIT = 3;
    public static final int MAX_UNICAST_SOLICIT = 3;
    public static final long REACHABLE_TIME = 30_000;
    public static final long RETRANS_TIMER = 1000;
    public static final long DELAY_FIRST_PROBE_TIME = 5000;
    public static final long MAX_RA_DELAY_TIME = 500;
    public static final long MIN_DELAY_BETWEEN_RAS = 3000;
    public static final int MAX_INITIAL_RTR_ADVERTISEMENTS = 3;
    public static final long MAX_INITIAL_RTR_ADVERT_INTERVAL = 16_000;
    /** RFC 4862 第 5.1 节 DupAddrDetectTransmits 的默认值 */
    public static final int DUP_ADDR_DETECT_TRANSMITS = 1;
    /** 解析中的邻居最多排几个包 */
    public static final int MAX_QUEUED = 8;
    /** 发给链路上所有人（组播）；点对点链路上发的包都用它 */
    public static final long BROADCAST = -1;

    /** 下面的链路：把一个包交给链路层（{@code linkDestination} 为 {@link #BROADCAST} 时发给链路上所有人） */
    @FunctionalInterface
    public interface Link {
        void transmit(byte[] packet, long linkDestination);
    }

    /** 上面要知道的事（都在事件循环线程上调用） */
    public interface Events {
        default void addressReady(Ipv6Address address) {
        }

        default void addressDuplicated(Ipv6Address address) {
        }

        /** 收到一个合格的 RA（主机；路由器不处理 RA） */
        default void routerAdvertisement(Ipv6Address from, NdpMessage ra) {
        }

        /** 解析失败、没发出去的包 */
        default void resolutionFailed(byte[] packet) {
        }
    }

    /** 路由器发的 RA 的内容（RFC 4861 第 6.2.1 节的变量） */
    public static final class Advertising {
        public final boolean managed;
        public final boolean otherConfig;
        /** 秒；0 表示不当默认路由器 */
        public final int routerLifetime;
        public final List<NdpMessage.Prefix> prefixes;
        /** -1 不带 MTU 选项 */
        public final long mtu;
        public final List<NdpMessage.Rdnss> rdnss;
        public final long minInterval;
        public final long maxInterval;

        public Advertising(boolean managed, boolean otherConfig, int routerLifetime, List<NdpMessage.Prefix> prefixes, long mtu,
                           List<NdpMessage.Rdnss> rdnss, long minIntervalMillis, long maxIntervalMillis) {
            this.managed = managed;
            this.otherConfig = otherConfig;
            this.routerLifetime = routerLifetime;
            this.prefixes = Collections.unmodifiableList(new ArrayList<>(prefixes));
            this.mtu = mtu;
            this.rdnss = Collections.unmodifiableList(new ArrayList<>(rdnss));
            this.minInterval = minIntervalMillis;
            this.maxInterval = maxIntervalMillis;
        }

        /** RFC 4861 的默认间隔：最长 600 秒、最短 0.33 倍 */
        public static final long DEFAULT_MAX_INTERVAL = 600_000;
        public static final long DEFAULT_MIN_INTERVAL = 198_000;
    }

    enum AddressState { TENTATIVE, PREFERRED }

    enum NeighborState { INCOMPLETE, REACHABLE, STALE, DELAY, PROBE }

    private final class Address {
        final Ipv6Address address;
        AddressState state = AddressState.TENTATIVE;
        int probesLeft = DUP_ADDR_DETECT_TRANSMITS;
        EventLoop.Timer timer;

        Address(Ipv6Address address) {
            this.address = address;
        }
    }

    private final class Neighbor {
        final Ipv6Address address;
        NeighborState state;
        long linkAddress = NdpMessage.NO_LINK_ADDRESS;
        boolean isRouter;
        int probes;
        final ArrayDeque<byte[]> queue = new ArrayDeque<>();
        EventLoop.Timer timer;

        Neighbor(Ipv6Address address) {
            this.address = address;
        }
    }

    private final class DefaultRouter {
        final Ipv6Address address;
        EventLoop.Timer expiry;

        DefaultRouter(Ipv6Address address) {
            this.address = address;
        }
    }

    private final class OnLinkPrefix {
        final Ipv6Address prefix;
        final int length;
        EventLoop.Timer expiry;

        OnLinkPrefix(Ipv6Address prefix, int length) {
            this.prefix = prefix;
            this.length = length;
        }
    }

    private final EventLoop loop;
    private final Random random;
    private final Link link;
    private final long ownLinkAddress;
    private final boolean router;
    private final Events events;
    private final Map<Ipv6Address, Address> addresses = new LinkedHashMap<>();
    /** 加入的组播组（引用计数：两个地址的请求节点组播地址可能一样） */
    private final Map<Ipv6Address, Integer> groups = new HashMap<>();
    private final Map<Ipv6Address, Neighbor> neighbors = new HashMap<>();
    private final List<DefaultRouter> routers = new ArrayList<>();
    private final List<OnLinkPrefix> onLink = new ArrayList<>();
    private final Map<Ipv6Address, EventLoop.Timer> dnsServers = new LinkedHashMap<>();
    /** 地址解析的 NS 要不要回（原始主机没人收时不回自己的地址；见 {@link #setAnswering}） */
    private Predicate<Ipv6Address> answering = a -> true;
    private long reachableTime;
    private long retransTimer = RETRANS_TIMER;
    private long mtu = Ipv6Packet.MAX_PACKET;
    private int curHopLimit;
    private boolean managed;
    private boolean otherConfig;
    /** 网口起来以后第一个报文最早什么时候发（-1 为还没定；RFC 4862 第 5.4.2 节的随机等待） */
    private long quietUntil = -1;
    private boolean closed;

    // 主机：RS
    private int solicitationsLeft;
    private EventLoop.Timer solicitTimer;
    private boolean solicitWanted;

    // 路由器：RA
    private Advertising advertising;
    private int initialLeft;
    private EventLoop.Timer raTimer;
    private long lastMulticastRa = Long.MIN_VALUE / 2;
    private boolean raResponsePending;

    // 统计
    public long solicitationsSent;
    public long advertisementsSent;
    public long resolutionFailures;
    public long dropped;

    public NeighborDiscovery(EventLoop loop, Random random, Link link, long ownLinkAddress, boolean router, Events events) {
        this.loop = loop;
        this.random = random;
        this.link = link;
        this.ownLinkAddress = ownLinkAddress;
        this.router = router;
        this.events = events == null ? new Events() {
        } : events;
        this.reachableTime = randomReachable(REACHABLE_TIME);
        join(Ipv6Address.ALL_NODES);
        if (router) {
            join(Ipv6Address.ALL_ROUTERS);
        }
    }

    public boolean isRouter() {
        return router;
    }

    public long linkAddress() {
        return ownLinkAddress;
    }

    /** 关掉：计时器都停，以后什么都不发 */
    public void close() {
        if (closed) {
            return;
        }
        closed = true;
        for (Address a : addresses.values()) {
            cancel(a.timer);
        }
        for (Neighbor n : neighbors.values()) {
            cancel(n.timer);
        }
        for (DefaultRouter r : routers) {
            cancel(r.expiry);
        }
        for (OnLinkPrefix p : onLink) {
            cancel(p.expiry);
        }
        for (EventLoop.Timer t : dnsServers.values()) {
            cancel(t);
        }
        cancel(solicitTimer);
        cancel(raTimer);
        neighbors.clear();
    }

    private static void cancel(EventLoop.Timer t) {
        if (t != null) {
            t.cancel();
        }
    }

    private long randomReachable(long base) {
        // RFC 4861 第 6.3.2 节：基准 × [0.5, 1.5) 的随机数
        return (long) (base * (0.5 + random.nextDouble()));
    }

    /** 离第一个报文能发还要等多久（第一次调用时随机定 0–{@value #MAX_RTR_SOLICITATION_DELAY} 毫秒；之后的报文跟着不早于它） */
    private long firstDelay() {
        if (quietUntil < 0) {
            quietUntil = loop.nowMillis() + (long) (random.nextDouble() * MAX_RTR_SOLICITATION_DELAY);
        }
        return Math.max(0, quietUntil - loop.nowMillis());
    }

    // ---- 组播组 ----

    public void join(Ipv6Address group) {
        groups.merge(group, 1, Integer::sum);
    }

    public void leave(Ipv6Address group) {
        groups.computeIfPresent(group, (k, v) -> v <= 1 ? null : v - 1);
    }

    // ---- 地址 ----

    /** 加一个地址：先暂定、做重复地址检测；已经有的不管 */
    public void addAddress(Ipv6Address a) {
        if (closed || a == null || a.isMulticast() || a.isUnspecified() || addresses.containsKey(a)) {
            return;
        }
        Address x = new Address(a);
        addresses.put(a, x);
        join(a.solicitedNode());
        x.timer = loop.schedule(() -> dadProbe(x), firstDelay());
    }

    public void removeAddress(Ipv6Address a) {
        Address x = addresses.remove(a);
        if (x != null) {
            cancel(x.timer);
            leave(a.solicitedNode());
        }
    }

    /** 地址已经过了重复地址检测（可以当源地址、收发给它的包） */
    public boolean isUsable(Ipv6Address a) {
        Address x = addresses.get(a);
        return x != null && x.state == AddressState.PREFERRED;
    }

    public boolean isTentative(Ipv6Address a) {
        Address x = addresses.get(a);
        return x != null && x.state == AddressState.TENTATIVE;
    }

    public boolean hasAddress(Ipv6Address a) {
        return addresses.containsKey(a);
    }

    /** 能用的地址（按加进来的先后） */
    public List<Ipv6Address> usableAddresses() {
        List<Ipv6Address> out = new ArrayList<>();
        for (Address x : addresses.values()) {
            if (x.state == AddressState.PREFERRED) {
                out.add(x.address);
            }
        }
        return out;
    }

    /** 能用的链路本地地址；没有为 null */
    public Ipv6Address linkLocal() {
        for (Address x : addresses.values()) {
            if (x.state == AddressState.PREFERRED && x.address.isLinkLocal()) {
                return x.address;
            }
        }
        return null;
    }

    /** 地址解析的 NS 回不回（{@code answer} 对这个地址返回 false 时不回；重复地址检测照样防守） */
    public void setAnswering(Predicate<Ipv6Address> answer) {
        answering = answer == null ? a -> true : answer;
    }

    private void dadProbe(Address x) {
        if (closed || addresses.get(x.address) != x || x.state != AddressState.TENTATIVE) {
            return;
        }
        if (x.probesLeft > 0) {
            x.probesLeft--;
            // 源地址未指定、发给请求节点组播地址、不带源链路层地址（RFC 4862 第 5.4.2 节）
            transmit(NdpMessage.neighborSolicitation(x.address, NdpMessage.NO_LINK_ADDRESS)
                    .toPacket(Ipv6Address.UNSPECIFIED, x.address.solicitedNode()).encode(), BROADCAST);
            solicitationsSent++;
            x.timer = loop.schedule(() -> dadProbe(x), retransTimer);
            return;
        }
        x.state = AddressState.PREFERRED;
        x.timer = null;
        events.addressReady(x.address);
        if (x.address.isLinkLocal()) {
            onLinkLocalReady();
        }
    }

    private void duplicate(Address x) {
        if (addresses.get(x.address) != x || x.state != AddressState.TENTATIVE) {
            return;
        }
        removeAddress(x.address);
        events.addressDuplicated(x.address);
    }

    private void onLinkLocalReady() {
        if (router && advertising != null && raTimer == null) {
            startAdvertising();
        }
        if (!router && solicitWanted && solicitTimer == null) {
            solicitationsLeft = MAX_RTR_SOLICITATIONS;
            solicitTimer = loop.schedule(this::sendRs, (long) (random.nextDouble() * MAX_RTR_SOLICITATION_DELAY));
        }
    }

    // ---- 收 ----

    /** 发给 {@code dst} 的包这个网口收不收：能用的单播地址、加入了的组播组 */
    public boolean accepts(Ipv6Address dst) {
        if (dst.isMulticast()) {
            return groups.containsKey(dst);
        }
        return isUsable(dst);
    }

    /**
     * 收到一个 ICMPv6 报文；是邻居发现报文时处理掉并返回 true（不合格的也算处理掉，静默丢）。目的地址要先过 {@link #accepts}
     * （请求节点组播地址也算，所以暂定地址的 NS 能收到）
     */
    public boolean handle(Ipv6Packet p, Icmpv6Message m) {
        if (!NdpMessage.isNdpType(m.type)) {
            return false;
        }
        if (closed) {
            return true;
        }
        NdpMessage n;
        try {
            n = NdpMessage.parse(p, m);
        } catch (MalformedPacketException e) {
            dropped++;
            return true;
        }
        switch (n.type) {
            case NdpMessage.NEIGHBOR_SOLICITATION:
                onNs(p, n);
                break;
            case NdpMessage.NEIGHBOR_ADVERTISEMENT:
                onNa(p, n);
                break;
            case NdpMessage.ROUTER_SOLICITATION:
                onRs(p, n);
                break;
            case NdpMessage.ROUTER_ADVERTISEMENT:
                onRa(p, n);
                break;
            default:
                break;
        }
        return true;
    }

    private void onNs(Ipv6Packet p, NdpMessage n) {
        Address x = addresses.get(n.target);
        if (x == null) {
            return;
        }
        boolean dad = p.source.isUnspecified();
        if (x.state == AddressState.TENTATIVE) {
            // 别人也在检测这个地址：重复（RFC 4862 第 5.4.3 节）；源地址不是未指定的（地址解析）不理
            if (dad) {
                duplicate(x);
            }
            return;
        }
        if (dad) {
            // 防守：回给所有节点，S = 0
            transmit(NdpMessage.neighborAdvertisement(n.target, router, false, true, ownLinkAddress)
                    .toPacket(n.target, Ipv6Address.ALL_NODES).encode(), BROADCAST);
            advertisementsSent++;
            return;
        }
        if (!answering.test(n.target)) {
            return;
        }
        // 记下对方（第 7.2.3 节：有源链路层地址的建成或改成“陈旧”）
        Neighbor nb = neighbors.get(p.source);
        if (n.sourceLinkAddress != NdpMessage.NO_LINK_ADDRESS || ownLinkAddress == NdpMessage.NO_LINK_ADDRESS) {
            if (nb == null) {
                nb = new Neighbor(p.source);
                nb.state = NeighborState.STALE;
                nb.linkAddress = n.sourceLinkAddress;
                neighbors.put(p.source, nb);
            } else if (nb.state == NeighborState.INCOMPLETE || nb.linkAddress != n.sourceLinkAddress) {
                nb.linkAddress = n.sourceLinkAddress;
                setState(nb, NeighborState.STALE);
                flush(nb);
            }
        }
        long dst = nb != null ? nb.linkAddress : BROADCAST;
        transmit(NdpMessage.neighborAdvertisement(n.target, router, true, true, ownLinkAddress).toPacket(n.target, p.source).encode(),
                dst == NdpMessage.NO_LINK_ADDRESS ? BROADCAST : dst);
        advertisementsSent++;
    }

    private void onNa(Ipv6Packet p, NdpMessage n) {
        Address x = addresses.get(n.target);
        if (x != null) {
            if (x.state == AddressState.TENTATIVE) {
                duplicate(x);
            }
            // 已经用着的地址被别人通告：RFC 4862 第 5.4.4 节说可以记日志，这里不做别的
            return;
        }
        Neighbor nb = neighbors.get(n.target);
        if (nb == null) {
            return;
        }
        boolean needLla = ownLinkAddress != NdpMessage.NO_LINK_ADDRESS;
        if (nb.state == NeighborState.INCOMPLETE) {
            if (needLla && n.targetLinkAddress == NdpMessage.NO_LINK_ADDRESS) {
                return;
            }
            nb.linkAddress = n.targetLinkAddress;
            nb.isRouter = n.router;
            setState(nb, n.solicited ? NeighborState.REACHABLE : NeighborState.STALE);
            flush(nb);
            return;
        }
        boolean different = needLla && n.targetLinkAddress != NdpMessage.NO_LINK_ADDRESS && n.targetLinkAddress != nb.linkAddress;
        if (!n.override && different) {
            if (nb.state == NeighborState.REACHABLE) {
                setState(nb, NeighborState.STALE);
            }
            return;
        }
        if (different) {
            nb.linkAddress = n.targetLinkAddress;
        }
        if (n.solicited) {
            setState(nb, NeighborState.REACHABLE);
        } else if (different) {
            setState(nb, NeighborState.STALE);
        }
        if (nb.isRouter && !n.router) {
            removeRouter(nb.address);
        }
        nb.isRouter = n.router;
    }

    private void onRs(Ipv6Packet p, NdpMessage n) {
        if (!router || advertising == null) {
            return;
        }
        if (!p.source.isUnspecified() && (n.sourceLinkAddress != NdpMessage.NO_LINK_ADDRESS || ownLinkAddress == NdpMessage.NO_LINK_ADDRESS)) {
            Neighbor nb = neighbors.get(p.source);
            if (nb == null) {
                nb = new Neighbor(p.source);
                nb.state = NeighborState.STALE;
                neighbors.put(p.source, nb);
            }
            nb.linkAddress = n.sourceLinkAddress;
            nb.isRouter = false;
        }
        if (raResponsePending || linkLocal() == null) {
            return;
        }
        // 第 6.2.6 节：随机等 0–MAX_RA_DELAY_TIME，两次组播 RA 至少隔 MIN_DELAY_BETWEEN_RAS
        long delay = (long) (random.nextDouble() * MAX_RA_DELAY_TIME);
        long earliest = lastMulticastRa + MIN_DELAY_BETWEEN_RAS - loop.nowMillis();
        raResponsePending = true;
        loop.schedule(() -> {
            raResponsePending = false;
            sendRa();
        }, Math.max(delay, earliest));
    }

    private void onRa(Ipv6Packet p, NdpMessage ra) {
        if (router) {
            return;
        }
        Neighbor nb = neighbors.get(p.source);
        if (ra.sourceLinkAddress != NdpMessage.NO_LINK_ADDRESS || ownLinkAddress == NdpMessage.NO_LINK_ADDRESS) {
            if (nb == null) {
                nb = new Neighbor(p.source);
                nb.state = NeighborState.STALE;
                neighbors.put(p.source, nb);
                nb.linkAddress = ra.sourceLinkAddress;
            } else if (nb.state == NeighborState.INCOMPLETE || nb.linkAddress != ra.sourceLinkAddress) {
                nb.linkAddress = ra.sourceLinkAddress;
                setState(nb, NeighborState.STALE);
                flush(nb);
            }
        }
        if (nb != null) {
            nb.isRouter = true;
        }
        if (ra.curHopLimit != 0) {
            curHopLimit = ra.curHopLimit;
        }
        if (ra.reachableTime != 0) {
            reachableTime = randomReachable(ra.reachableTime);
        }
        if (ra.retransTimer != 0) {
            retransTimer = ra.retransTimer;
        }
        if (ra.mtu >= Ipv6Packet.MAX_PACKET && ra.mtu <= 65535) {
            mtu = Math.min(ra.mtu, Ipv6Packet.MAX_PACKET);
        }
        managed = ra.managed;
        otherConfig = ra.otherConfig;
        updateRouter(p.source, ra.routerLifetime);
        for (NdpMessage.Prefix x : ra.prefixes) {
            if (x.onLink && !x.prefix.isLinkLocal()) {
                updatePrefix(x.prefix, x.length, x.validLifetime);
            }
        }
        for (NdpMessage.Rdnss r : ra.rdnss) {
            for (Ipv6Address s : r.servers) {
                updateDns(s, r.lifetime);
            }
        }
        // 收到 RA 就不用再请求了
        solicitWanted = false;
        cancel(solicitTimer);
        solicitTimer = null;
        events.routerAdvertisement(p.source, ra);
    }

    private void updateRouter(Ipv6Address a, int lifetimeSeconds) {
        DefaultRouter r = null;
        for (DefaultRouter x : routers) {
            if (x.address.equals(a)) {
                r = x;
            }
        }
        if (lifetimeSeconds == 0) {
            if (r != null) {
                removeRouter(a);
            }
            return;
        }
        if (r == null) {
            r = new DefaultRouter(a);
            routers.add(r);
        }
        cancel(r.expiry);
        DefaultRouter rr = r;
        r.expiry = loop.schedule(() -> removeRouter(rr.address), lifetimeSeconds * 1000L);
    }

    private void removeRouter(Ipv6Address a) {
        for (int i = routers.size() - 1; i >= 0; i--) {
            if (routers.get(i).address.equals(a)) {
                cancel(routers.get(i).expiry);
                routers.remove(i);
            }
        }
    }

    private void updatePrefix(Ipv6Address prefix, int length, long validLifetime) {
        OnLinkPrefix p = null;
        for (OnLinkPrefix x : onLink) {
            if (x.length == length && x.prefix.inPrefix(prefix, length) && prefix.inPrefix(x.prefix, length)) {
                p = x;
            }
        }
        if (validLifetime == 0) {
            if (p != null) {
                cancel(p.expiry);
                onLink.remove(p);
            }
            return;
        }
        if (p == null) {
            p = new OnLinkPrefix(prefix, length);
            onLink.add(p);
        }
        cancel(p.expiry);
        OnLinkPrefix pp = p;
        p.expiry = validLifetime == NdpMessage.INFINITE ? null : loop.schedule(() -> onLink.remove(pp), validLifetime * 1000L);
    }

    private void updateDns(Ipv6Address server, long lifetime) {
        cancel(dnsServers.remove(server));
        if (lifetime == 0) {
            return;
        }
        dnsServers.put(server, lifetime == NdpMessage.INFINITE ? null : loop.schedule(() -> dnsServers.remove(server), lifetime * 1000L));
    }

    // ---- 主机：选下一跳、RS ----

    /** 主机：开始请求路由器（链路本地地址能用以后先随机等一会儿，发 RS，最多三次，收到 RA 为止） */
    public void solicitRouters() {
        if (router || closed) {
            return;
        }
        solicitWanted = true;
        if (linkLocal() != null && solicitTimer == null) {
            onLinkLocalReady();
        }
    }

    private void sendRs() {
        if (closed || !solicitWanted || solicitationsLeft <= 0 || router) {
            solicitTimer = null;
            return;
        }
        solicitationsLeft--;
        Ipv6Address src = linkLocal();
        transmit(NdpMessage.routerSolicitation(src == null ? NdpMessage.NO_LINK_ADDRESS : ownLinkAddress)
                .toPacket(src == null ? Ipv6Address.UNSPECIFIED : src, Ipv6Address.ALL_ROUTERS).encode(), BROADCAST);
        solicitationsSent++;
        solicitTimer = solicitationsLeft > 0 ? loop.schedule(this::sendRs, RTR_SOLICITATION_INTERVAL) : null;
    }

    /** 主机：发往 {@code dst} 的包交给链路上的哪个地址；没有路由为 null */
    public Ipv6Address nextHop(Ipv6Address dst) {
        if (dst.isLinkScoped() || dst.isMulticast()) {
            return dst;
        }
        for (OnLinkPrefix p : onLink) {
            if (dst.inPrefix(p.prefix, p.length)) {
                return dst;
            }
        }
        return defaultRouter();
    }

    /** 默认路由器（RFC 4861 第 6.3.6 节：先挑可达或可能可达的） */
    public Ipv6Address defaultRouter() {
        Ipv6Address fallback = null;
        for (DefaultRouter r : routers) {
            Neighbor n = neighbors.get(r.address);
            if (n != null && n.state != NeighborState.INCOMPLETE) {
                return r.address;
            }
            if (fallback == null) {
                fallback = r.address;
            }
        }
        return fallback;
    }

    public List<Ipv6Address> defaultRouters() {
        List<Ipv6Address> out = new ArrayList<>();
        for (DefaultRouter r : routers) {
            out.add(r.address);
        }
        return out;
    }

    /** 在链路上的前缀（“前缀/长度”） */
    public List<String> onLinkPrefixes() {
        List<String> out = new ArrayList<>();
        for (OnLinkPrefix p : onLink) {
            out.add(p.prefix + "/" + p.length);
        }
        return out;
    }

    /** RA 里 RDNSS 给的 DNS 服务器（没过期的） */
    public List<Ipv6Address> dnsServers() {
        return new ArrayList<>(dnsServers.keySet());
    }

    public long mtu() {
        return mtu;
    }

    /** RA 里的跳数（0 为没说） */
    public int curHopLimit() {
        return curHopLimit;
    }

    public boolean managed() {
        return managed;
    }

    public boolean otherConfig() {
        return otherConfig;
    }

    public long retransTimer() {
        return retransTimer;
    }

    // ---- 路由器：RA ----

    /** 路由器：照 {@code a} 发 RA、回 RS（null 为不发）。链路本地地址能用以后开始 */
    public void advertise(Advertising a) {
        if (!router) {
            throw new IllegalStateException("主机不发 RA");
        }
        advertising = a;
        cancel(raTimer);
        raTimer = null;
        if (a != null && linkLocal() != null) {
            startAdvertising();
        }
    }

    private void startAdvertising() {
        initialLeft = MAX_INITIAL_RTR_ADVERTISEMENTS;
        raTimer = loop.schedule(this::periodicRa, 0);
    }

    private void periodicRa() {
        if (closed || advertising == null) {
            return;
        }
        long earliest = lastMulticastRa + MIN_DELAY_BETWEEN_RAS - loop.nowMillis();
        if (earliest > 0) {
            raTimer = loop.schedule(this::periodicRa, earliest);
            return;
        }
        sendRa();
        long interval = advertising.minInterval + (long) (random.nextDouble() * (advertising.maxInterval - advertising.minInterval));
        if (initialLeft > 0) {
            initialLeft--;
            interval = Math.min(interval, MAX_INITIAL_RTR_ADVERT_INTERVAL);
        }
        raTimer = loop.schedule(this::periodicRa, interval);
    }

    private void sendRa() {
        Ipv6Address src = linkLocal();
        if (closed || advertising == null || src == null) {
            return;
        }
        Advertising a = advertising;
        transmit(NdpMessage.routerAdvertisement(Ipv6Packet.DEFAULT_HOP_LIMIT, a.managed, a.otherConfig, a.routerLifetime, 0, 0,
                ownLinkAddress, a.prefixes, a.mtu, a.rdnss).toPacket(src, Ipv6Address.ALL_NODES).encode(), BROADCAST);
        lastMulticastRa = loop.nowMillis();
        advertisementsSent++;
    }

    // ---- 发 ----

    /**
     * 把一个包发给链路上的 {@code nextHop}（组播直接发；单播先解析）。解析失败时包交给 {@link Events#resolutionFailed}。
     * {@code packet} 交进来以后不要再改
     */
    public void send(byte[] packet, Ipv6Address nextHop) {
        if (closed) {
            return;
        }
        if (nextHop.isMulticast()) {
            transmit(packet, BROADCAST);
            return;
        }
        Neighbor n = neighbors.get(nextHop);
        if (n == null) {
            n = new Neighbor(nextHop);
            n.state = NeighborState.INCOMPLETE;
            neighbors.put(nextHop, n);
            n.queue.add(packet);
            n.probes = 0;
            solicit(n, Ipv6Packet.sourceOf(packet));
            return;
        }
        switch (n.state) {
            case INCOMPLETE:
                if (n.queue.size() >= MAX_QUEUED) {
                    n.queue.poll();
                    dropped++;
                }
                n.queue.add(packet);
                return;
            case STALE:
                setState(n, NeighborState.DELAY);
                transmitTo(n, packet);
                return;
            default:
                transmitTo(n, packet);
        }
    }

    private void transmitTo(Neighbor n, byte[] packet) {
        transmit(packet, n.linkAddress == NdpMessage.NO_LINK_ADDRESS ? BROADCAST : n.linkAddress);
    }

    /** 解析用的组播 NS（第 7.2.2 节：源地址用排着的包的源地址，是这个网口的话；否则随便一个能用的） */
    private void solicit(Neighbor n, Ipv6Address preferredSource) {
        if (closed || neighbors.get(n.address) != n || n.state != NeighborState.INCOMPLETE) {
            return;
        }
        if (n.probes >= MAX_MULTICAST_SOLICIT) {
            neighbors.remove(n.address);
            resolutionFailures++;
            List<byte[]> q = new ArrayList<>(n.queue);
            n.queue.clear();
            for (byte[] p : q) {
                events.resolutionFailed(p);
            }
            return;
        }
        Ipv6Address src = preferredSource != null && isUsable(preferredSource) ? preferredSource : anySource(n.address);
        // 还没有能用的地址（暂定中）时这一轮不发，但照样算次数（地址一直用不了就按解析失败算）
        n.probes++;
        if (src != null) {
            transmit(NdpMessage.neighborSolicitation(n.address, ownLinkAddress).toPacket(src, n.address.solicitedNode()).encode(), BROADCAST);
            solicitationsSent++;
        }
        n.timer = loop.schedule(() -> solicit(n, preferredSource), retransTimer);
    }

    /** 当 NS 源地址用的：目标是链路本地的用链路本地地址，否则先挑同范围的 */
    private Ipv6Address anySource(Ipv6Address target) {
        Ipv6Address ll = linkLocal();
        if (target.isLinkLocal() && ll != null) {
            return ll;
        }
        for (Address x : addresses.values()) {
            if (x.state == AddressState.PREFERRED && !x.address.isLinkLocal()) {
                return x.address;
            }
        }
        return ll;
    }

    private void setState(Neighbor n, NeighborState s) {
        cancel(n.timer);
        n.timer = null;
        n.state = s;
        switch (s) {
            case REACHABLE:
                n.timer = loop.schedule(() -> {
                    if (n.state == NeighborState.REACHABLE) {
                        n.state = NeighborState.STALE;
                        n.timer = null;
                    }
                }, reachableTime);
                break;
            case DELAY:
                n.timer = loop.schedule(() -> {
                    if (n.state == NeighborState.DELAY) {
                        n.probes = 0;
                        n.state = NeighborState.PROBE;
                        probe(n);
                    }
                }, DELAY_FIRST_PROBE_TIME);
                break;
            default:
                break;
        }
    }

    /** 单播 NS 探测（第 7.3.3 节） */
    private void probe(Neighbor n) {
        if (closed || neighbors.get(n.address) != n || n.state != NeighborState.PROBE) {
            return;
        }
        if (n.probes >= MAX_UNICAST_SOLICIT) {
            // 不可达了：删掉（是默认路由器的话表里还留着，下次选的时候排在可达的后面，见 defaultRouter）
            neighbors.remove(n.address);
            return;
        }
        Ipv6Address src = anySource(n.address);
        if (src != null) {
            n.probes++;
            transmit(NdpMessage.neighborSolicitation(n.address, ownLinkAddress).toPacket(src, n.address).encode(),
                    n.linkAddress == NdpMessage.NO_LINK_ADDRESS ? BROADCAST : n.linkAddress);
            solicitationsSent++;
        }
        n.timer = loop.schedule(() -> probe(n), retransTimer);
    }

    private void flush(Neighbor n) {
        while (!n.queue.isEmpty()) {
            transmitTo(n, n.queue.poll());
        }
    }

    private void transmit(byte[] packet, long linkDst) {
        if (!closed) {
            link.transmit(packet, linkDst);
        }
    }

    // ---- 给 /ynet、测试看 ----

    /** 邻居缓存（“地址 状态 [链路层地址] [路由器]”） */
    public List<String> describeNeighbors() {
        List<String> out = new ArrayList<>();
        for (Neighbor n : neighbors.values()) {
            out.add(n.address + " " + n.state + (n.linkAddress == NdpMessage.NO_LINK_ADDRESS ? "" : " " + Long.toHexString(n.linkAddress))
                    + (n.isRouter ? " 路由器" : ""));
        }
        return out;
    }

    /** 邻居的状态名；不在缓存里为 null（测试用） */
    public String neighborState(Ipv6Address a) {
        Neighbor n = neighbors.get(a);
        return n == null ? null : n.state.name();
    }

    /** 修饰过的 EUI-64 接口标识（RFC 4291 附录 A）：48 位链路层地址中间插 fffe、翻转 U/L 位 */
    public static long eui64(long linkAddress48) {
        long hi = (linkAddress48 >>> 24) & 0xFFFFFF;
        long lo = linkAddress48 & 0xFFFFFF;
        return ((hi ^ 0x020000L) << 40) | (0xFFFEL << 24) | lo;
    }
}
