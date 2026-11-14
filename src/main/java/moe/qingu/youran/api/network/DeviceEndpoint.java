package moe.qingu.youran.api.network;

/** 设备网卡在服务端这一侧的另一头（{@link DeviceHandle#attachEndpoint}） */
@FunctionalInterface
public interface DeviceEndpoint {
    /** 发给设备的原始 IPv6 包（在网络线程上调用，不要阻塞，不要读写世界） */
    void receive(byte[] packet);
}
