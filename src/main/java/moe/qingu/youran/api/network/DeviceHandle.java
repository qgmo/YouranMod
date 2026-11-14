package moe.qingu.youran.api.network;

import net.minecraft.entity.player.EntityPlayerMP;

/**
 * 设备表里的一台设备（家具电脑、以后 SCP-079 的模拟机器等）。除 {@link #send} 外只在服务端主线程上调用。
 * <p>
 * 能收发包的条件：通电、所在区块已加载、网卡接到了某处（玩家客户端或服务端端点）。不满足时它发的包丢掉、
 * 发给它的包由路由器回“目标不可达”。
 */
public interface DeviceHandle {
    Ipv6Address address();

    int dimension();

    int x();

    int y();

    int z();

    void setPowered(boolean powered);

    boolean isPowered();

    /**
     * 网卡接到这个玩家的客户端（方案 A：协议栈跑在操作者的客户端上，原始包装进 Minecraft 网络包收发）。
     * 一个玩家同时只接一台设备：接新的时旧的自动断开。服务器会告诉客户端设备地址与递归解析器地址。
     */
    void attachPlayer(EntityPlayerMP player);

    /**
     * 网卡接到服务端的一个端点（例如服务端模拟的机器）：发给设备的包在网络线程上交给 {@link DeviceEndpoint#receive}；
     * 端点发包用 {@link #send}。
     */
    void attachEndpoint(DeviceEndpoint endpoint);

    /** 断开网卡（没人操作了） */
    void detach();

    /** 现在接着的玩家；没有为 null */
    EntityPlayerMP attachedPlayer();

    /**
     * 设备发一个原始 IPv6 包（任意线程；在网络线程上校验）：源地址必须是设备地址、不超过 1280 字节、不超过速率上限，
     * 设备要能收发；不合格的丢掉并记日志。接着玩家客户端时，客户端发来的包走同样的校验。
     */
    void send(byte[] packet);

    /** 从设备表里拿掉 */
    void remove();

    boolean isRemoved();
}
