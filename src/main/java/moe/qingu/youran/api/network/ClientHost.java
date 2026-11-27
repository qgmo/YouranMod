package moe.qingu.youran.api.network;

import moe.qingu.youran.api.network.stack.DnsResolver;
import moe.qingu.youran.api.network.stack.NetStack;
import moe.qingu.youran.network.ClientHostState;

import java.util.List;

/**
 * 玩家客户端这台“主机”（服主 11-27：按接近真实的网络端口做）：<b>一个协议栈、多个网口</b>。只在客户端上用。
 * <ul>
 * <li>玩家自己的网口（{@link Kind#PLAYER}，网口号 0）：上线时服务器分到玩家临时地址（{@link PlayerAddresses}）后加上，下线去掉。
 * 受服务器的防火墙管：只能问递归解析器、连服务器按用途放行的主机的 TCP 80 / 443；没有 NTP（{@link Port#ntpAddress()} 为 null）。</li>
 * <li>接着的网卡（{@link Kind#RELAY}，网口号 1–255）：服务器把这个玩家接到一张 {@link ClientRelayNic}（如家具电脑）上时加上，地址是那张网卡的地址；
 * 断开时去掉。同一时间最多一个。</li>
 * </ul>
 * 网口的增减都由服务器发起，客户端不能自己加；增减时通知 {@link Listener}。程序用哪个网口：套接字绑网口（{@code NetStack} 的 {@code …Via}、
 * {@link Port#dns()}），或绑源地址；不绑的走默认网口，也就是玩家自己的网口（RFC 6724 的最简版）。网口去掉时，绑在它上面的连接报错。
 * <p>
 * 协议栈跑在客户端自己的网络线程上；回调都在那个线程上，要改界面时自己转到客户端主线程。没连着服务器时 {@link #stack()} 为 null、没有网口。
 */
public final class ClientHost {
    private ClientHost() {
    }

    /** 网口的种类 */
    public enum Kind {
        /** 玩家自己的（玩家临时地址） */
        PLAYER,
        /** 接着的一张网卡（{@link ClientRelayNic}） */
        RELAY
    }

    /** 一个网口。去掉以后（{@link #isRemoved()}）它的地址、绑在它上面的连接都没了 */
    public interface Port {
        /** 网口号（客户端与服务器之间用；玩家自己的是 0） */
        int id();

        /** 给人看的名字 */
        String name();

        Kind kind();

        Ipv6Address address();

        /** 这个网口用的递归解析器 */
        Ipv6Address resolverAddress();

        /** 这个网口用的 NTP 服务器；不许连时为 null */
        Ipv6Address ntpAddress();

        /** 客户端唯一的协议栈 */
        NetStack stack();

        /** 协议栈上对应的网口（绑网口时用） */
        NetStack.Interface iface();

        /** 从这个网口问它的递归解析器的 DNS 客户端 */
        DnsResolver dns();

        boolean isRemoved();
    }

    /** 网口增减时在客户端网络线程上调用 */
    public interface Listener {
        default void onPortAdded(Port port) {
        }

        default void onPortRemoved(Port port) {
        }
    }

    /** 客户端唯一的协议栈；没连着服务器时为 null */
    public static NetStack stack() {
        ClientHostState s = ClientHostState.current();
        return s == null ? null : s.stack();
    }

    /** 现在的网口（按网口号排） */
    public static List<Port> ports() {
        return ClientHostState.ports();
    }

    /** 这个网口号的网口；没有为 null */
    public static Port port(int id) {
        ClientHostState s = ClientHostState.current();
        return s == null ? null : s.port(id);
    }

    /** 玩家自己的网口；没有（没上线、服务器没开玩家临时地址）为 null */
    public static Port playerPort() {
        return ClientHostState.first(Kind.PLAYER);
    }

    /** 接着的网卡的网口（如正在操作的家具电脑）；没接为 null */
    public static Port relayPort() {
        return ClientHostState.first(Kind.RELAY);
    }

    public static void addListener(Listener listener) {
        ClientHostState.addListener(listener);
    }

    public static void removeListener(Listener listener) {
        ClientHostState.removeListener(listener);
    }
}
