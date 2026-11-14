package moe.qingu.youran.api.network.packet;

/**
 * NTPv4 报文（RFC 5905 第 7.3 节）的 48 字节首部，不带扩展字段和认证。
 * 时间戳是 64 位 NTP 时间（高 32 位为 1900-01-01 起的秒，低 32 位为秒的小数），存在 long 里（按无符号看）。
 */
public final class NtpPacket {
    public static final int PORT = 123;
    public static final int LENGTH = 48;
    public static final int MODE_CLIENT = 3;
    public static final int MODE_SERVER = 4;
    /** 1900-01-01 到 1970-01-01 的秒数 */
    public static final long UNIX_EPOCH_OFFSET = 2208988800L;

    public final int leap;
    public final int version;
    public final int mode;
    public final int stratum;
    public final int poll;
    public final int precision;
    public final long rootDelay;
    public final long rootDispersion;
    public final long referenceId;
    public final long referenceTime;
    public final long originTime;
    public final long receiveTime;
    public final long transmitTime;

    public NtpPacket(int leap, int version, int mode, int stratum, int poll, int precision, long rootDelay,
                     long rootDispersion, long referenceId, long referenceTime, long originTime, long receiveTime, long transmitTime) {
        this.leap = leap & 3;
        this.version = version & 7;
        this.mode = mode & 7;
        this.stratum = stratum & 0xFF;
        this.poll = poll;
        this.precision = precision;
        this.rootDelay = rootDelay & 0xFFFFFFFFL;
        this.rootDispersion = rootDispersion & 0xFFFFFFFFL;
        this.referenceId = referenceId & 0xFFFFFFFFL;
        this.referenceTime = referenceTime;
        this.originTime = originTime;
        this.receiveTime = receiveTime;
        this.transmitTime = transmitTime;
    }

    /** 客户端请求：只填版本 4、模式 3 与发送时间 */
    public static NtpPacket clientRequest(long transmitTime) {
        return new NtpPacket(0, 4, MODE_CLIENT, 0, 0, 0, 0, 0, 0, 0, 0, 0, transmitTime);
    }

    public static NtpPacket parse(byte[] b) throws MalformedPacketException {
        if (b.length < LENGTH) {
            throw new MalformedPacketException("NTP 报文不到 48 字节");
        }
        int first = Bytes.u8(b, 0);
        return new NtpPacket(first >>> 6, (first >>> 3) & 7, first & 7, Bytes.u8(b, 1), b[2], b[3],
                Bytes.u32(b, 4), Bytes.u32(b, 8), Bytes.u32(b, 12),
                Bytes.u64(b, 16), Bytes.u64(b, 24), Bytes.u64(b, 32), Bytes.u64(b, 40));
    }

    public byte[] encode() {
        byte[] b = new byte[LENGTH];
        b[0] = (byte) ((leap << 6) | (version << 3) | mode);
        b[1] = (byte) stratum;
        b[2] = (byte) poll;
        b[3] = (byte) precision;
        Bytes.put32(b, 4, rootDelay);
        Bytes.put32(b, 8, rootDispersion);
        Bytes.put32(b, 12, referenceId);
        Bytes.put64(b, 16, referenceTime);
        Bytes.put64(b, 24, originTime);
        Bytes.put64(b, 32, receiveTime);
        Bytes.put64(b, 40, transmitTime);
        return b;
    }

    /** Unix 毫秒 → NTP 时间戳（秒数按 2^32 取模，2036 年以后自然进入第 1 纪元） */
    public static long fromUnixMillis(long millis) {
        long seconds = Math.floorDiv(millis, 1000L) + UNIX_EPOCH_OFFSET;
        long ms = Math.floorMod(millis, 1000L);
        long fraction = (ms << 32) / 1000L;
        return (seconds << 32) | fraction;
    }

    /** NTP 时间戳 → Unix 毫秒。最高位为 0 的当作 2036 年以后（第 1 纪元，RFC 4330 第 3 节的办法），能表示 1968–2104 年 */
    public static long toUnixMillis(long ntp) {
        long seconds = ntp >>> 32;
        if ((seconds & 0x80000000L) == 0) {
            seconds += 1L << 32;
        }
        long fraction = ntp & 0xFFFFFFFFL;
        return (seconds - UNIX_EPOCH_OFFSET) * 1000L + ((fraction * 1000L + (1L << 31)) >>> 32);
    }
}
