package moe.qingu.youran.server.net;

/**
 * 令牌桶：每秒补 {@code packetsPerSecond} 个包、{@code bytesPerSecond} 字节，桶最多存一秒的量。纯逻辑，时间由调用方给。
 * 只在网络线程上用（不加锁）。
 */
public final class RateLimiter {
    private final double packetsPerMs;
    private final double bytesPerMs;
    private final double maxPackets;
    private final double maxBytes;
    private double packets;
    private double bytes;
    private long last = Long.MIN_VALUE;

    public RateLimiter(int packetsPerSecond, int bytesPerSecond) {
        this.maxPackets = packetsPerSecond;
        this.maxBytes = bytesPerSecond;
        this.packetsPerMs = packetsPerSecond / 1000.0;
        this.bytesPerMs = bytesPerSecond / 1000.0;
        this.packets = maxPackets;
        this.bytes = maxBytes;
    }

    /** 能放行一个 {@code size} 字节的包就扣掉并返回 true */
    public boolean tryAcquire(int size, long nowMillis) {
        if (last != Long.MIN_VALUE && nowMillis > last) {
            long dt = nowMillis - last;
            packets = Math.min(maxPackets, packets + dt * packetsPerMs);
            bytes = Math.min(maxBytes, bytes + dt * bytesPerMs);
        }
        if (last == Long.MIN_VALUE || nowMillis > last) {
            last = nowMillis;
        }
        if (packets < 1 || bytes < size) {
            return false;
        }
        packets -= 1;
        bytes -= size;
        return true;
    }
}
