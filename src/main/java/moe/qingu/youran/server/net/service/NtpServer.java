package moe.qingu.youran.server.net.service;

import moe.qingu.youran.api.network.Ipv6Address;
import moe.qingu.youran.api.network.packet.MalformedPacketException;
import moe.qingu.youran.api.network.packet.NtpPacket;
import moe.qingu.youran.api.network.stack.NetStack;
import moe.qingu.youran.api.network.stack.UdpSocket;

import java.util.function.LongSupplier;

/**
 * NTP 时间服务器（NTPv4，UDP 123）：给现实 UTC 时间（服务器操作系统的时钟）。只回客户端模式（3）的请求，
 * 回服务器模式（4），版本照请求（3 或 4）。层级 1、参考标识 {@code LOCL}（直接用本机时钟，不再往上同步）。
 */
public final class NtpServer implements UdpSocket.Handler {
    /** “LOCL” */
    static final long REF_ID = ('L' << 24) | ('O' << 16) | ('C' << 8) | 'L';
    /** 精度：2^-10 秒（约 1 毫秒，Java 时钟的粒度） */
    static final int PRECISION = -10;

    private final LongSupplier clock;

    public NtpServer(LongSupplier unixMillisClock) {
        this.clock = unixMillisClock;
    }

    public void start(NetStack stack) {
        stack.openUdp(null, NtpPacket.PORT, this);
    }

    @Override
    public void onDatagram(UdpSocket socket, Ipv6Address from, int fromPort, Ipv6Address to, byte[] data) {
        byte[] reply = handle(data);
        if (reply != null) {
            socket.send(to, from, fromPort, reply);
        }
    }

    /** 算应答（纯逻辑，测试用）；不回的返回 null */
    byte[] handle(byte[] data) {
        long received = NtpPacket.fromUnixMillis(clock.getAsLong());
        NtpPacket q;
        try {
            q = NtpPacket.parse(data);
        } catch (MalformedPacketException e) {
            return null;
        }
        if (q.mode != NtpPacket.MODE_CLIENT || q.version < 1 || q.version > 4) {
            return null;
        }
        long now = NtpPacket.fromUnixMillis(clock.getAsLong());
        // 根延迟 0、根离差约 1 毫秒（16.16 定点数：65 / 65536 秒）
        return new NtpPacket(0, q.version, NtpPacket.MODE_SERVER, 1, Math.max(q.poll, 4), PRECISION, 0, 65, REF_ID,
                now, q.transmitTime, received, now).encode();
    }
}
