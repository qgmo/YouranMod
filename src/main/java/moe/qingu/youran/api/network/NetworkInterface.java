package moe.qingu.youran.api.network;

import moe.qingu.youran.network.NicPort;

/**
 * 网卡：设备接进服务器内网的唯一途径。家具电脑、SCP 电脑等各自继承它，写自己的网卡。
 * <p>
 * 对外只有<b>收</b>：网络把发给这张网卡的包交给 {@link #receive}。<b>发</b>是网卡自己的事，用继承来的 {@link #send}，外面拿不到。
 * <p>
 * 网卡不知道自己在哪、通没通电、背后是什么机器：插进网络（{@link YouranNetworkApi#plug}）时网络按插的位置给它地址，
 * 拔掉（{@link YouranNetworkApi#unplug}）以后 {@link #send} 一律不收、也不会再有包交给 {@link #receive}。
 * 断电、没人操作、机器坏了等，由子类自己决定：{@link #receive} 返回 false、不调 {@link #send} 即可，网络不管。
 * 谁在操作、有没有定位等都不是网卡的事：要认人、要定位的，在网络上用各自的服务做（id.yr、定位服务）。
 */
public abstract class NetworkInterface {
    /** 插着时是网络给的端口，拔掉时为 null；只由 {@link YouranNetworkApi} 改 */
    volatile NicPort port;

    protected NetworkInterface() {
    }

    /**
     * 收到一个发给这张网卡的完整 IPv6 包（跳数已减一）。在网络线程上调用，不能阻塞、不要读写世界（排进自己的队列就返回）。
     * {@code packet} 是给你的副本，可以留着。返回 false 表示现在收不了，路由器回“目标不可达（地址不可达）”；抛出异常按 false 算。
     */
    public abstract boolean receive(byte[] packet);

    /**
     * 从这张网卡发一个完整的 IPv6 包。任意线程，不阻塞，排进网络线程就返回；调用以后 {@code packet} 不能再改。
     * 返回 false 表示没收下：没插着、不是合格的 IPv6 包、超过 1280 字节、源地址不是 {@link #address()}、超过速率上限、网络已停。
     */
    protected final boolean send(byte[] packet) {
        NicPort p = port;
        return p != null && p.send(packet);
    }

    /** 这张网卡的地址；没插着为 null */
    public final Ipv6Address address() {
        NicPort p = port;
        return p == null ? null : p.address();
    }

    public final boolean isPlugged() {
        return port != null;
    }
}
