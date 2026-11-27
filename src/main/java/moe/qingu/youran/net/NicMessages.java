package moe.qingu.youran.net;

import moe.qingu.youran.api.network.Ipv6Address;
import moe.qingu.youran.api.network.packet.Ipv6Packet;

/**
 * 网卡频道 {@code youranmod_net} 上的消息格式（第一个字节是类型）。与同步配置的频道分开：这个频道的包由网络线程直接发，
 * 不经主线程。客户端一个协议栈、多个网口（服主 11-27）：每个网口在服务端对应一张网卡（玩家自己的那张带防火墙，接着的电脑那张是
 * {@code ClientRelayNic}），网口的增减由服务器发起，规则只在服务端执行。
 * <pre>
 *   0x01 PACKET     双向   [网口号 1][原始 IPv6 包，40–1280 字节]
 *   0x02 PORT_UP    服→客  [网口号 1][种类 1][地址 16][递归解析器地址 16][NTP 地址 16]   多了一个网口（同一个号已有时先去掉旧的）
 *   0x03 PORT_DOWN  服→客  [网口号 1]                                                 去掉这个网口
 * </pre>
 * 网口号：玩家自己的网口固定为 0（{@link #PLAYER_PORT}）；接着的电脑等由服务器按顺序分 1–255，去掉以后过一阵才会再用同一个号。
 * 种类：{@link #KIND_PLAYER}、{@link #KIND_RELAY}。地址为全 0（{@code ::}）表示没有。玩家自己的网口（服主 11-27）三个地址都是 {@code ::}：
 * 那是一段点对点链路，客户端在上面做邻居发现、收 RA、用 DHCPv6 要地址、DNS 与 NTP；接着的网卡（坐标层）照旧由服务器给地址。
 * 收到已经去掉的（或从来没有的）网口号的包，两边都丢掉。
 */
public final class NicMessages {
    public static final String CHANNEL = "youranmod_net";
    public static final int PACKET = 1;
    public static final int PORT_UP = 2;
    public static final int PORT_DOWN = 3;
    /** 玩家自己的网口 */
    public static final int PLAYER_PORT = 0;
    public static final int MAX_PORT = 255;
    /** 网口种类：玩家自己的（玩家临时地址，受服务器的防火墙管） */
    public static final int KIND_PLAYER = 0;
    /** 网口种类：接着的一张网卡（{@code ClientRelayNic}，如家具电脑） */
    public static final int KIND_RELAY = 1;
    /** PACKET 的头：类型 + 网口号 */
    public static final int PACKET_HEADER = 2;
    /** 消息最长：PACKET 的 2 + 1280 */
    public static final int MAX_MESSAGE = PACKET_HEADER + Ipv6Packet.MAX_PACKET;

    private NicMessages() {
    }

    public static byte[] packet(int port, byte[] raw) {
        byte[] b = new byte[PACKET_HEADER + raw.length];
        b[0] = PACKET;
        b[1] = (byte) port;
        System.arraycopy(raw, 0, b, PACKET_HEADER, raw.length);
        return b;
    }

    public static byte[] portUp(int port, int kind, Ipv6Address address, Ipv6Address resolver, Ipv6Address ntp) {
        byte[] b = new byte[3 + 48];
        b[0] = PORT_UP;
        b[1] = (byte) port;
        b[2] = (byte) kind;
        address.write(b, 3);
        (resolver != null ? resolver : Ipv6Address.UNSPECIFIED).write(b, 19);
        (ntp != null ? ntp : Ipv6Address.UNSPECIFIED).write(b, 35);
        return b;
    }

    public static byte[] portDown(int port) {
        return new byte[]{PORT_DOWN, (byte) port};
    }

    /** 消息是 PACKET 时它的网口号，否则 -1 */
    public static int packetPort(byte[] message) {
        return message != null && message.length >= PACKET_HEADER + Ipv6Packet.HEADER && message[0] == PACKET ? message[1] & 0xFF : -1;
    }

    /** PACKET 里的原始包（复制一份） */
    public static byte[] packetBody(byte[] message) {
        return java.util.Arrays.copyOfRange(message, PACKET_HEADER, message.length);
    }
}
