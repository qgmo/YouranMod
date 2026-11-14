package moe.qingu.youran.api.network.packet;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

/** NTPv4 报文与时间戳 */
class NtpPacketTest {
    @Test
    void timestamps() {
        assertEquals(NtpPacket.UNIX_EPOCH_OFFSET << 32, NtpPacket.fromUnixMillis(0));
        assertEquals((NtpPacket.UNIX_EPOCH_OFFSET << 32) | 0x80000000L, NtpPacket.fromUnixMillis(500));
        long now = 1_794_355_200_123L; // 2026-11-11 前后
        assertEquals(now, NtpPacket.toUnixMillis(NtpPacket.fromUnixMillis(now)));
        // 2036 年以后（第 1 纪元）
        long after2036 = 2_200_000_000_000L;
        assertEquals(after2036, NtpPacket.toUnixMillis(NtpPacket.fromUnixMillis(after2036)));
    }

    @Test
    void roundTrip() throws Exception {
        NtpPacket p = new NtpPacket(0, 4, NtpPacket.MODE_SERVER, 1, 6, -10, 0, 65, 0x4C4F434CL, 1L, 2L, 3L, 4L);
        byte[] b = p.encode();
        assertEquals(48, b.length);
        assertEquals(0x24, b[0] & 0xFF);
        NtpPacket q = NtpPacket.parse(b);
        assertEquals(4, q.version);
        assertEquals(NtpPacket.MODE_SERVER, q.mode);
        assertEquals(-10, q.precision);
        assertEquals(0x4C4F434CL, q.referenceId);
        assertEquals(2L, q.originTime);
        assertEquals(4L, q.transmitTime);
        assertThrows(MalformedPacketException.class, () -> NtpPacket.parse(new byte[47]));
    }
}
