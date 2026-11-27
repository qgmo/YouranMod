package moe.qingu.youran.network;

import moe.qingu.youran.api.network.ClientHost;
import moe.qingu.youran.api.network.Ipv6Address;
import moe.qingu.youran.api.network.packet.Ipv6Packet;
import moe.qingu.youran.api.network.stack.DnsResolver;
import moe.qingu.youran.api.network.stack.EventLoop;
import moe.qingu.youran.api.network.stack.NetStack;
import moe.qingu.youran.net.NicMessages;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.concurrent.ConcurrentSkipListMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.Consumer;

/**
 * {@link ClientHost} 背后的状态：一次连着服务器的会话里客户端唯一的协议栈与它的网口（由 {@code client.network.ClientNetwork} 建、在客户端网络线程上改）。
 * 不碰 Minecraft，单元测试直接用：服务器发来的网卡消息交给 {@link #onMessage}，发给服务器的交给构造时给的 {@code toServer}。
 * <p>
 * 玩家自己的网口（服主 11-27）是一段点对点链路，做邻居发现：链路本地地址用随机的接口标识，过了重复地址检测以后请求路由器，
 * 收到 RA（M 位）以后用 DHCPv6 要地址、DNS、NTP（{@link moe.qingu.youran.api.network.stack.Dhcpv6Client}）；地址过了重复地址检测才能用，
 * 这时通知 {@link ClientHost.Listener#onPortConfigured}。接着的网卡（坐标层）照旧用服务器给的地址，马上能用。
 */
public final class ClientHostState {
    private static final Logger LOG = LogManager.getLogger("YouranMod");
    private static volatile ClientHostState current;
    private static final List<ClientHost.Listener> listeners = new CopyOnWriteArrayList<>();

    private final EventLoop loop;
    private final Random random;
    private final NetStack stack;
    private final Consumer<byte[]> toServer;
    /** 网口号 → 网口；只在事件循环线程上改，任意线程读 */
    private final Map<Integer, PortImpl> ports = new ConcurrentSkipListMap<>();
    private boolean closed;
    /** 丢掉的包（没有这个网口号的、太短的；统计用） */
    private long dropped;

    public ClientHostState(EventLoop loop, Random random, Consumer<byte[]> toServer) {
        this.loop = loop;
        this.random = random;
        this.stack = new NetStack(loop, random);
        this.toServer = toServer;
    }

    // ---- 全局（ClientHost 用） ----

    public static ClientHostState current() {
        return current;
    }

    /** 连上服务器时装上、断开时拿下（null） */
    public static void install(ClientHostState s) {
        current = s;
    }

    public static List<ClientHost.Port> ports() {
        ClientHostState s = current;
        return s == null ? new ArrayList<>() : new ArrayList<ClientHost.Port>(s.ports.values());
    }

    public static ClientHost.Port first(ClientHost.Kind kind) {
        ClientHostState s = current;
        if (s == null) {
            return null;
        }
        for (PortImpl p : s.ports.values()) {
            if (p.kind == kind) {
                return p;
            }
        }
        return null;
    }

    public static void addListener(ClientHost.Listener l) {
        listeners.add(l);
    }

    public static void removeListener(ClientHost.Listener l) {
        listeners.remove(l);
    }

    // ---- 这一次会话 ----

    public NetStack stack() {
        return stack;
    }

    public ClientHost.Port port(int id) {
        return ports.get(id);
    }

    public long droppedCount() {
        return dropped;
    }

    /** 服务器发来的网卡消息（事件循环线程上） */
    public void onMessage(byte[] b) {
        if (closed || b == null || b.length < 2) {
            return;
        }
        int id = b[1] & 0xFF;
        switch (b[0]) {
            case NicMessages.PACKET: {
                PortImpl p = ports.get(id);
                if (p == null || b.length < NicMessages.PACKET_HEADER + Ipv6Packet.HEADER) {
                    // 已经去掉的（或从来没有的）网口号：丢掉
                    dropped++;
                    return;
                }
                p.iface.receive(NicMessages.packetBody(b));
                break;
            }
            case NicMessages.PORT_UP: {
                if (b.length < 3 + 48) {
                    return;
                }
                        int kind = b[2] & 0xFF;
                Ipv6Address address = orNull(Ipv6Address.read(b, 3));
                Ipv6Address resolver = orNull(Ipv6Address.read(b, 19));
                Ipv6Address ntp = orNull(Ipv6Address.read(b, 35));
                if (kind != NicMessages.KIND_PLAYER && kind != NicMessages.KIND_RELAY) {
                    return;
                }
                addPort(id, kind == NicMessages.KIND_PLAYER ? ClientHost.Kind.PLAYER : ClientHost.Kind.RELAY, address, resolver, ntp);
                break;
            }
            case NicMessages.PORT_DOWN:
                removePort(id);
                break;
            default:
                break;
        }
    }

    private static Ipv6Address orNull(Ipv6Address a) {
        return Ipv6Address.UNSPECIFIED.equals(a) ? null : a;
    }

    private void addPort(int id, ClientHost.Kind kind, Ipv6Address address, Ipv6Address resolver, Ipv6Address ntp) {
        removePort(id);
        String name = kind == ClientHost.Kind.PLAYER ? "玩家" : "中转 " + id;
        NetStack.Interface iface;
        if (kind == ClientHost.Kind.PLAYER) {
            // 点对点链路，没有链路层地址；链路本地地址的接口标识随机（RFC 7217 / 8981 的做法，不从硬件地址来）
            long iid = random.nextLong();
            iface = stack.addNdInterface(id, name, (raw, dst) -> toServer.accept(NicMessages.packet(id, raw)),
                    moe.qingu.youran.api.network.packet.NdpMessage.NO_LINK_ADDRESS, Ipv6Address.linkLocal(iid == 0 ? 1 : iid));
            stack.setDefaultInterface(iface);
        } else {
            iface = stack.addInterface(id, name, raw -> toServer.accept(NicMessages.packet(id, raw)));
            if (address != null) {
                iface.addAddress(address);
            }
            iface.setDnsServer(resolver);
            iface.setNtpServer(ntp);
        }
        PortImpl p = new PortImpl(id, name, kind, iface);
        iface.addListener(new NetStack.Interface.Listener() {
            @Override
            public void onConfigured(NetStack.Interface i) {
                if (ports.get(id) != p || i.address() == null) {
                    return;
                }
                LOG.info("游戏内网络：网口 {}（{}）配好了：地址 {}，递归解析器 {}，NTP {}{}", id, name, i.address(), i.dnsServer(), i.ntpServer(),
                        i.dhcp() != null && i.dhcp().lease() != null ? "（DHCPv6：" + i.dhcp().lease() + "）" : "");
                for (ClientHost.Listener l : listeners) {
                    try {
                        l.onPortConfigured(p);
                    } catch (Throwable t) {
                        LOG.error("网口回调出错", t);
                    }
                }
            }
        });
        ports.put(id, p);
        LOG.info("游戏内网络：多了网口 {}（{}）{}", id, name, kind == ClientHost.Kind.PLAYER ? "，地址用 DHCPv6 要" : "，地址 " + address + "，递归解析器 " + resolver);
        for (ClientHost.Listener l : listeners) {
            try {
                l.onPortAdded(p);
            } catch (Throwable t) {
                LOG.error("网口回调出错", t);
            }
        }
    }

    private void removePort(int id) {
        PortImpl p = ports.remove(id);
        if (p == null) {
            return;
        }
        stack.removeInterface(p.iface);
        LOG.info("游戏内网络：去掉了网口 {}（{}，地址 {}）", id, p.name, p.address());
        for (ClientHost.Listener l : listeners) {
            try {
                l.onPortRemoved(p);
            } catch (Throwable t) {
                LOG.error("网口回调出错", t);
            }
        }
    }

    /** 断开服务器（事件循环线程上）：网口都去掉，协议栈关掉 */
    public void close() {
        if (closed) {
            return;
        }
        for (Integer id : new ArrayList<>(ports.keySet())) {
            removePort(id);
        }
        closed = true;
        stack.close();
    }

    private static final class PortImpl implements ClientHost.Port {
        final int id;
        final String name;
        final ClientHost.Kind kind;
        final NetStack.Interface iface;

        PortImpl(int id, String name, ClientHost.Kind kind, NetStack.Interface iface) {
            this.id = id;
            this.name = name;
            this.kind = kind;
            this.iface = iface;
        }

        @Override
        public int id() {
            return id;
        }

        @Override
        public String name() {
            return name;
        }

        @Override
        public ClientHost.Kind kind() {
            return kind;
        }

        @Override
        public Ipv6Address address() {
            return iface.address();
        }

        @Override
        public Ipv6Address resolverAddress() {
            return iface.dnsServer();
        }

        @Override
        public Ipv6Address ntpAddress() {
            return iface.ntpServer();
        }

        @Override
        public NetStack stack() {
            return iface.stack();
        }

        @Override
        public NetStack.Interface iface() {
            return iface;
        }

        @Override
        public DnsResolver dns() {
            return new DnsResolver(iface.stack(), iface, iface.dnsServer());
        }

        @Override
        public boolean isRemoved() {
            return iface.isRemoved();
        }

        @Override
        public String toString() {
            return id + " " + name + " " + address();
        }
    }
}
