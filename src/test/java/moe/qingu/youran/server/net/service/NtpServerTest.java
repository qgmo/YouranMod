package moe.qingu.youran.server.net.service;

import moe.qingu.youran.api.network.packet.NtpPacket;
import org.junit.jupiter.api.Test;

import java.net.InetAddress;
import java.security.MessageDigest;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** NTP 层级（服主 11-27）：层级、参考标识、根延迟、根离差照配置报；参考标识按 RFC 5905 由上游算 */
class NtpServerTest {
    @Test
    void reportsTheConfiguredStratumAndRootValues() throws Exception {
        NtpServer s = new NtpServer(() -> 1_000_000L, new NtpServer.Config(3, 0xC0000201L, 30, 50));
        NtpPacket p = NtpPacket.parse(s.handle(NtpPacket.clientRequest(42L).encode()));
        assertEquals(3, p.stratum);
        assertEquals(0xC0000201L, p.referenceId);
        assertEquals((30L << 16) / 1000, p.rootDelay, "根延迟 30 毫秒（16.16 定点数）");
        assertEquals((50L << 16) / 1000, p.rootDispersion, "根离差 50 毫秒");
        assertEquals(42L, p.originTime);
        // 默认：3 层
        assertEquals(3, NtpPacket.parse(new NtpServer(() -> 1000L).handle(NtpPacket.clientRequest(1L).encode())).stratum);
        // 换配置：马上按新的报
        s.setConfig(new NtpServer.Config(4, 7, 0, 0));
        p = NtpPacket.parse(s.handle(NtpPacket.clientRequest(43L).encode()));
        assertEquals(4, p.stratum);
        assertEquals(7, p.referenceId);
        // 层级只收 2–15（不能报 1 层，这台不是参考时钟）
        assertEquals(2, new NtpServer.Config(1, 0, 0, 0).stratum);
        assertEquals(15, new NtpServer.Config(99, 0, 0, 0).stratum);
    }

    @Test
    void referenceIdComesFromTheUpstream() throws Exception {
        // IPv4：地址本身
        InetAddress v4 = NtpServer.literal("192.0.2.1");
        assertEquals(0xC0000201L, NtpServer.referenceId(v4));
        // IPv6：地址 MD5 的前 4 字节
        InetAddress v6 = NtpServer.literal("2001:db8::1");
        byte[] d = MessageDigest.getInstance("MD5").digest(v6.getAddress());
        long want = ((d[0] & 0xFFL) << 24) | ((d[1] & 0xFFL) << 16) | ((d[2] & 0xFFL) << 8) | (d[3] & 0xFFL);
        assertEquals(want, NtpServer.referenceId(v6));
        assertEquals(want, NtpServer.referenceId(NtpServer.literal("[2001:db8::1]")), "带方括号也认");
        // 名字不是字面地址：不在这里查（开服时在后台查）
        assertNull(NtpServer.literal("pool.ntp.org"));
        assertNull(NtpServer.literal(""));
        assertTrue(NtpServer.referenceId(null) == 0);
    }
}
