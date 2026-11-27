package moe.qingu.youran.server.net.service;

import moe.qingu.youran.api.network.Ipv6Address;
import moe.qingu.youran.api.network.packet.MalformedPacketException;
import moe.qingu.youran.api.network.packet.NtpPacket;
import moe.qingu.youran.api.network.stack.NetStack;
import moe.qingu.youran.api.network.stack.UdpSocket;

import java.net.Inet4Address;
import java.net.Inet6Address;
import java.net.InetAddress;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.function.LongSupplier;

/**
 * NTP 时间服务器（NTPv4，RFC 5905，UDP 123）：给现实 UTC 时间（服务器操作系统的时钟）。只回客户端模式（3）的请求，
 * 回服务器模式（4），版本照请求（1–4）。
 * <p>
 * 层级、参考标识、根延迟、根离差照配置报（服主 11-27：这台只是转手操作系统的时钟，系统时钟一般跟公共 NTP 池的 2 层服务器同步，转手一次是 3 层）。
 * 参考标识按 RFC 5905 第 7.3 节由上游算（{@link #referenceId}）：上游是 IPv4 地址时就是地址本身，IPv6 地址时是地址 MD5 的前 4 字节。
 */
public final class NtpServer implements UdpSocket.Handler {
    /** 精度：2^-10 秒（约 1 毫秒，Java 时钟的粒度） */
    static final int PRECISION = -10;

    /** 报给客户端的同步信息 */
    public static final class Config {
        /** 层级（2–15） */
        public final int stratum;
        /** 参考标识（32 位） */
        public final long referenceId;
        /** 根延迟、根离差（毫秒） */
        public final long rootDelayMillis;
        public final long rootDispersionMillis;

        public Config(int stratum, long referenceId, long rootDelayMillis, long rootDispersionMillis) {
            this.stratum = Math.max(2, Math.min(15, stratum));
            this.referenceId = referenceId & 0xFFFFFFFFL;
            this.rootDelayMillis = Math.max(0, rootDelayMillis);
            this.rootDispersionMillis = Math.max(0, rootDispersionMillis);
        }

        /** 默认：3 层、参考标识 0（上游没说）、根延迟 30 毫秒、根离差 50 毫秒 */
        public static final Config DEFAULT = new Config(3, 0, 30, 50);
    }

    private final LongSupplier clock;
    private volatile Config config;

    public NtpServer(LongSupplier unixMillisClock) {
        this(unixMillisClock, Config.DEFAULT);
    }

    public NtpServer(LongSupplier unixMillisClock, Config config) {
        this.clock = unixMillisClock;
        this.config = config;
    }

    /** 换配置（任意线程；如开服后在后台查到了上游的地址） */
    public void setConfig(Config c) {
        config = c;
    }

    public Config config() {
        return config;
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
        Config c = config;
        long now = NtpPacket.fromUnixMillis(clock.getAsLong());
        return new NtpPacket(0, q.version, NtpPacket.MODE_SERVER, c.stratum, Math.max(q.poll, 4), PRECISION, shortFormat(c.rootDelayMillis),
                shortFormat(c.rootDispersionMillis), c.referenceId, now, q.transmitTime, received, now).encode();
    }

    /** 毫秒 → NTP 短格式（16.16 定点数的秒） */
    static long shortFormat(long millis) {
        return Math.min(0xFFFFFFFFL, (millis << 16) / 1000);
    }

    /** 参考标识（RFC 5905 第 7.3 节，层级 2 以上）：IPv4 地址是地址本身的 4 字节，IPv6 地址是地址的 MD5 的前 4 字节 */
    public static long referenceId(InetAddress upstream) {
        if (upstream instanceof Inet4Address) {
            byte[] b = upstream.getAddress();
            return ((b[0] & 0xFFL) << 24) | ((b[1] & 0xFFL) << 16) | ((b[2] & 0xFFL) << 8) | (b[3] & 0xFFL);
        }
        if (upstream instanceof Inet6Address) {
            try {
                byte[] d = MessageDigest.getInstance("MD5").digest(upstream.getAddress());
                return ((d[0] & 0xFFL) << 24) | ((d[1] & 0xFFL) << 16) | ((d[2] & 0xFFL) << 8) | (d[3] & 0xFFL);
            } catch (NoSuchAlgorithmException e) {
                throw new IllegalStateException(e);
            }
        }
        return 0;
    }

    /** 文字形式的地址（IPv4 点分、IPv6）；不是地址（是名字）为 null，不查 DNS */
    public static InetAddress literal(String text) {
        String t = text == null ? "" : text.trim();
        if (t.startsWith("[") && t.endsWith("]")) {
            t = t.substring(1, t.length() - 1);
        }
        if (t.matches("\\d{1,3}(\\.\\d{1,3}){3}") || (t.contains(":") && t.matches("[0-9A-Fa-f:.]+"))) {
            try {
                return InetAddress.getByName(t);
            } catch (java.net.UnknownHostException e) {
                return null;
            }
        }
        return null;
    }
}
