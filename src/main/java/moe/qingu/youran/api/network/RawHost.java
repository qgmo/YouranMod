package moe.qingu.youran.api.network;

/**
 * 原始主机（服主 11-16）：机房里一个 /128，不跑 YouranMod 的协议栈，进出的都是完整的 IPv6 包。
 * 用来把游戏外的一台机器（例如经隧道连进来的 TUN 设备）接进服务器内网。用 {@link YouranNetworkApi#registerRawHost} 登记。
 * <p>
 * 它接在所在机房的局域网上（服主 11-27）：邻居发现、重复地址检测由 YouranMod 代答，邻居发现报文（RS、RA、NS、NA）不交给接收方；
 * 发出去的包由 YouranMod 按机房路由器的 RA 选下一跳。/128 和机房里别人的重复时这台原始主机不启用（记日志）。
 * <p>
 * <b>收</b>：发往这个 /128 的包原样交给 {@link #setReceiver} 设的接收方（经过路由器的跳数已减），在网络线程上调用。
 * 没设接收方时这个地址不回邻居请求，发来的包解析不到、回“目标不可达（地址不可达）”；接收方返回 false 的包丢掉（包已经交到了这台主机的链路上）。
 * <p>
 * <b>发</b>：{@link #send} 把一个完整的 IPv6 包从这个 /128 发进路由器，路由器照常转发，不看内容。
 * 只要求它是一个 IPv6 包（版本 6、不超过 1280 字节、长度字段对得上）且<b>源地址就是这个 /128</b>：
 * 设备、主机发出的包源地址都不能伪造（按来源认人、定位等都靠这一点），原始主机也一样。
 */
public interface RawHost {
    /** 登记名（如 {@code yrplugin:qing}） */
    String key();

    /** 这台主机的 /128 */
    Ipv6Address address();

    /** 换接收方；null 表示现在没人收（发来的包回“目标不可达”）。任意线程 */
    void setReceiver(RawPacketReceiver receiver);

    /**
     * 从这个 /128 往网络里发一个完整的 IPv6 包。任意线程，不阻塞：包排进网络线程后马上返回。
     * 返回 false 表示没收下：不是合格的 IPv6 包、超过 1280 字节、源地址不是 {@link #address()}、
     * 排着还没转发的包太多（{@link #MAX_QUEUED}）、或网络已经停了。调用以后 {@code packet} 不能再改。
     */
    boolean send(byte[] packet);

    /** 网络是不是还在运行（关服以后为 false，{@link #send} 一律返回 false） */
    boolean isOpen();

    /** 一台原始主机交给网络线程、还没转发的包最多这么多，再多的 {@link #send} 返回 false */
    int MAX_QUEUED = 1024;
}
