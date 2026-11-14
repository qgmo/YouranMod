package moe.qingu.youran.server.net;

import moe.qingu.youran.api.network.packet.NtpPacket;

import java.net.NetworkInterface;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.util.Enumeration;

/**
 * 按 RFC 4193 第 3.2.2 节生成 ULA 的 40 位全局 ID：取当前时间（64 位 NTP 格式）与一个 EUI-64，
 * 拼起来算 SHA-1，取最低 40 位。EUI-64 由本机某块网卡的 MAC 地址按 RFC 4291 附录 A 换算；
 * 找不到网卡时用 64 位安全随机数代替（RFC 允许用“本机唯一的标识”）。
 */
public final class UlaPrefix {
    private UlaPrefix() {
    }

    public static long generateGlobalId() {
        return globalId(NtpPacket.fromUnixMillis(System.currentTimeMillis()), eui64());
    }

    /** 由时间与 EUI-64 算全局 ID（纯函数，测试用） */
    static long globalId(long ntpTime, long eui64) {
        byte[] in = new byte[16];
        for (int i = 0; i < 8; i++) {
            in[i] = (byte) (ntpTime >>> (56 - 8 * i));
            in[8 + i] = (byte) (eui64 >>> (56 - 8 * i));
        }
        byte[] digest;
        try {
            digest = MessageDigest.getInstance("SHA-1").digest(in);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("没有 SHA-1", e);
        }
        long id = 0;
        for (int i = 15; i < 20; i++) {
            id = (id << 8) | (digest[i] & 0xFF);
        }
        return id;
    }

    static long eui64() {
        try {
            Enumeration<NetworkInterface> nics = NetworkInterface.getNetworkInterfaces();
            while (nics != null && nics.hasMoreElements()) {
                byte[] mac = nics.nextElement().getHardwareAddress();
                if (mac != null && mac.length == 6 && (mac[0] | mac[1] | mac[2] | mac[3] | mac[4] | mac[5]) != 0) {
                    return fromMac(mac);
                }
            }
        } catch (Exception | LinkageError e) {
            // 拿不到网卡：用随机数
        }
        return new SecureRandom().nextLong();
    }

    /** MAC（48 位）→ 修改过的 EUI-64：中间插 FFFE，翻转 U/L 位 */
    static long fromMac(byte[] mac) {
        long v = 0;
        int[] b = {mac[0] & 0xFF ^ 0x02, mac[1] & 0xFF, mac[2] & 0xFF, 0xFF, 0xFE, mac[3] & 0xFF, mac[4] & 0xFF, mac[5] & 0xFF};
        for (int x : b) {
            v = (v << 8) | x;
        }
        return v;
    }
}
