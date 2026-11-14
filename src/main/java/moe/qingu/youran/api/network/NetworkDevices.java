package moe.qingu.youran.api.network;

/**
 * 设备表（只在服务端、只在主线程上用）。设备的网卡：在不在、通没通电、接到谁那里，都由主线程在变化时告诉这张表，
 * 网络线程的路由器只查表、不读世界。区块加载、卸载由表自己跟着 Forge 的事件更新。
 */
public interface NetworkDevices {
    /**
     * 在维度 {@code dimension} 的 (x, y, z) 登记一台设备（用这一格的 0 号地址）。
     * 这个维度没有全局 Wi-Fi、坐标超出范围时返回 null（没有地址，连不上）。
     * 同一格已有设备时，旧的被拿掉（它的句柄 {@link DeviceHandle#isRemoved()} 变 true）。
     * 新登记的设备默认通电、没接网卡。
     */
    DeviceHandle register(int dimension, int x, int y, int z);

    /** 这一格上登记的设备；没有为 null */
    DeviceHandle at(int dimension, int x, int y, int z);

    /** 这一格（0 号）的地址；没有地址为 null。不要求有设备 */
    Ipv6Address addressAt(int dimension, int x, int y, int z);
}
