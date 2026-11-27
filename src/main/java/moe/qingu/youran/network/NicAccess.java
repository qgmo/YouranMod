package moe.qingu.youran.network;

import moe.qingu.youran.api.network.NetworkInterface;

/**
 * 服务端网络给网卡装上、拿下端口（YouranMod 内部）。{@code NetworkInterface} 的端口字段只在 {@code api.network} 包里能改，
 * 那个包在类初始化时把这个入口交给 {@link NetworkHooks}；其他模组用 {@code YouranNetworkApi.plug} / {@code unplug}，用不着这里。
 * 服务端自己的网卡（玩家网口）、关服时拔掉所有网卡用它。
 */
public interface NicAccess {
    /** 网卡没插着时装上 {@code port}，返回 true；已经插着返回 false */
    boolean attach(NetworkInterface nic, NicPort port);

    /** 网卡插着的正是 {@code port} 时拿下，返回 true（插着别的、没插着时不动） */
    boolean detach(NetworkInterface nic, NicPort port);

    /** 网卡现在的端口；没插着为 null */
    NicPort portOf(NetworkInterface nic);
}
