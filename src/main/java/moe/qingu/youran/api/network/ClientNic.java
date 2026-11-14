package moe.qingu.youran.api.network;

import moe.qingu.youran.api.network.stack.DnsResolver;
import moe.qingu.youran.api.network.stack.NetStack;
import moe.qingu.youran.network.ClientNicState;

/**
 * 客户端上的“网卡”（方案 A：协议栈跑在操作者的客户端上）。服务器把这个玩家接到某台设备上时（{@link DeviceHandle#attachPlayer}），
 * 客户端在自己的网络线程上建一个协议栈（地址是设备地址），原始 IPv6 包装进 Minecraft 网络包与服务器往来；断开时协议栈关掉。
 * 只在客户端上用。浏览器等程序用 {@link #current()} 拿到当前的会话，在它的 {@link Session#stack()} 上收发；
 * 回调都在客户端网络线程上，要改界面时自己转到客户端主线程。
 */
public final class ClientNic {
    private ClientNic() {
    }

    /** 一次接上（设备地址、递归解析器、协议栈）。断开以后它的协议栈就关了 */
    public interface Session {
        Ipv6Address address();

        Ipv6Address resolverAddress();

        Ipv6Address ntpAddress();

        NetStack stack();

        /** 问递归解析器的 DNS 客户端 */
        DnsResolver dns();

        boolean isClosed();
    }

    /** 接上、断开时在客户端网络线程上调用 */
    public interface Listener {
        void onAttached(Session session);

        void onDetached(Session session);
    }

    /** 现在接着的会话；没接为 null */
    public static Session current() {
        return ClientNicState.current();
    }

    public static void addListener(Listener listener) {
        ClientNicState.addListener(listener);
    }

    public static void removeListener(Listener listener) {
        ClientNicState.removeListener(listener);
    }
}
