package moe.qingu.youran.net;

import moe.qingu.youran.api.network.Ipv6Address;
import moe.qingu.youran.api.network.packet.Ipv6Packet;

/**
 * 网卡频道 {@code youranmod_net} 上的消息格式（第一个字节是类型）。与同步配置的频道分开：这个频道的包由网络线程直接发，
 * 不经主线程。
 * <pre>
 *   0x01 PACKET  双向   [原始 IPv6 包，40–1280 字节]
 *   0x02 ATTACH  服→客  [代号 int][设备地址 16][递归解析器地址 16][NTP 地址 16]   网卡接上了这台设备
 *   0x03 DETACH  服→客  [代号 int]                                         网卡断开
 * </pre>
 * 代号每次接上时加一，客户端用它丢掉过时的 DETACH。
 */
public final class NicMessages {
    public static final String CHANNEL = "youranmod_net";
    public static final int PACKET = 1;
    public static final int ATTACH = 2;
    public static final int DETACH = 3;
    /** 消息最长：PACKET 的 1 + 1280 */
    public static final int MAX_MESSAGE = 1 + Ipv6Packet.MAX_PACKET;

    private NicMessages() {
    }

    public static byte[] packet(byte[] raw) {
        byte[] b = new byte[1 + raw.length];
        b[0] = PACKET;
        System.arraycopy(raw, 0, b, 1, raw.length);
        return b;
    }

    public static byte[] attach(int generation, Ipv6Address device, Ipv6Address resolver, Ipv6Address ntp) {
        byte[] b = new byte[1 + 4 + 48];
        b[0] = ATTACH;
        putInt(b, 1, generation);
        device.write(b, 5);
        (resolver != null ? resolver : Ipv6Address.UNSPECIFIED).write(b, 21);
        (ntp != null ? ntp : Ipv6Address.UNSPECIFIED).write(b, 37);
        return b;
    }

    public static byte[] detach(int generation) {
        byte[] b = new byte[5];
        b[0] = DETACH;
        putInt(b, 1, generation);
        return b;
    }

    public static int getInt(byte[] b, int i) {
        return ((b[i] & 0xFF) << 24) | ((b[i + 1] & 0xFF) << 16) | ((b[i + 2] & 0xFF) << 8) | (b[i + 3] & 0xFF);
    }

    private static void putInt(byte[] b, int i, int v) {
        b[i] = (byte) (v >>> 24);
        b[i + 1] = (byte) (v >>> 16);
        b[i + 2] = (byte) (v >>> 8);
        b[i + 3] = (byte) v;
    }
}
