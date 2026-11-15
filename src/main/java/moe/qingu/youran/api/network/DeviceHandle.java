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
     * 声明（或去掉）一项网卡附带的可选硬件能力（{@link DeviceCapability}），如游戏角色一键登录、定位模块。
     * 新登记的设备什么能力都没有。随时可以改（例如玩家把定位模块放进、拿出电脑的卡槽）。
     */
    void setCapability(DeviceCapability capability, boolean present);

    boolean hasCapability(DeviceCapability capability);

    /**
     * 告诉 YouranMod 现在是谁在操作这台设备（{@code null} 清空）。给网卡接服务端端点（{@link #attachEndpoint}）或还没接网卡的设备用，
     * 例如服务端模拟的 SCP 电脑：玩家打开界面时设成他，关界面时清空。
     * 网卡接着玩家客户端（{@link #attachPlayer}）时操作者就是那个玩家，这里设的不算。
     * 设备拿掉时一起清空。玩家下线时由 YouranMod 自己清掉。
     */
    void setOperator(EntityPlayerMP player);

    /** 现在的操作者（接着玩家客户端时是那个玩家，否则是 {@link #setOperator} 设的）；没有为 null */
    java.util.UUID operatorId();

    /** 操作者的名字；没有为 null */
    String operatorName();

    /**
     * 设备发一个原始 IPv6 包（任意线程；在网络线程上校验）：源地址必须是设备地址、不超过 1280 字节、不超过速率上限，
     * 设备要能收发；不合格的丢掉并记日志。接着玩家客户端时，客户端发来的包走同样的校验。
     */
    void send(byte[] packet);

    /** 从设备表里拿掉 */
    void remove();

    boolean isRemoved();
}
