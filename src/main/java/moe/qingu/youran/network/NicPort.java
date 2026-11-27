package moe.qingu.youran.network;

import moe.qingu.youran.api.network.Ipv6Address;

/** 网络给一张插着的网卡的端口（YouranMod 内部；其他模组看不到，也用不着） */
public interface NicPort {
    Ipv6Address address();

    /** 校验（源地址、长度、速率）后排进网络线程；见 {@code NetworkInterface#send} */
    boolean send(byte[] packet);
}
