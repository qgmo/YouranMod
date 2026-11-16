package moe.qingu.youran.api.pcb;

import net.minecraft.nbt.NBTTagCompound;

import javax.annotation.Nullable;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;

/**
 * ROM 里装的是什么（服主 11-16：只比内容，不把 ROM 真接进模拟器执行）。一份 NBT：
 * <pre>
 * 掩模 ROM：{Mask:"monitor_rom"}           程序做在掩模里，出厂就是那份正版程序（芯片型号的 id）
 * EPROM：  {Len:4096, Sha256:"…", Sum:41503}  烧进去的字节数、SHA-256（小写十六进制）、16 位校验和（逐字节相加，烧录器上常见的那种）
 * 空白的 EPROM：没有这份 NBT（null）
 * </pre>
 * 正版的内容是 079 服务现在用的那份监控程序 / BIOS 镜像（服务认证时报给游戏服务器）：EPROM 的字节数、SHA-256 都一样才算。
 */
public final class RomContent {
    public static final String TAG_MASK = "Mask";
    public static final String TAG_LEN = "Len";
    public static final String TAG_SHA = "Sha256";
    public static final String TAG_SUM = "Sum";
    /** 掩模 ROM 的内容 id：普通电脑的监控程序、个人电脑的 BIOS（同芯片型号 id） */
    public static final String MONITOR = "monitor_rom";
    public static final String BIOS = "bios_rom";

    private RomContent() {
    }

    /** 掩模 ROM 的内容 */
    public static NBTTagCompound mask(String id) {
        NBTTagCompound t = new NBTTagCompound();
        t.setString(TAG_MASK, id);
        return t;
    }

    /** 烧进去这些字节以后的内容 */
    public static NBTTagCompound image(byte[] data) {
        NBTTagCompound t = new NBTTagCompound();
        t.setInteger(TAG_LEN, data.length);
        t.setString(TAG_SHA, sha256(data));
        t.setInteger(TAG_SUM, checksum(data));
        return t;
    }

    /** 16 位校验和：逐字节无符号相加取低 16 位 */
    public static int checksum(byte[] data) {
        int s = 0;
        for (byte b : data) {
            s += b & 0xFF;
        }
        return s & 0xFFFF;
    }

    public static String sha256(byte[] data) {
        try {
            byte[] d = MessageDigest.getInstance("SHA-256").digest(data);
            StringBuilder sb = new StringBuilder(64);
            for (byte b : d) {
                sb.append(Character.forDigit((b >> 4) & 0xF, 16)).append(Character.forDigit(b & 0xF, 16));
            }
            return sb.toString();
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }

    /**
     * 这份内容是不是正版：掩模 ROM 看是不是 {@code mask} 那一种；EPROM 看字节数与 SHA-256 是不是正版镜像的（{@code genuineLen}、
     * {@code genuineSha}；不知道正版时传 -1 / null，EPROM 一律不算）。空白（null）不算。
     */
    public static boolean genuine(@Nullable NBTTagCompound rom, String mask, int genuineLen, @Nullable String genuineSha) {
        if (rom == null || rom.isEmpty()) {
            return false;
        }
        if (rom.hasKey(TAG_MASK, 8)) {
            return mask.equals(rom.getString(TAG_MASK));
        }
        return genuineSha != null && genuineLen >= 0 && rom.getInteger(TAG_LEN) == genuineLen
                && genuineSha.equalsIgnoreCase(rom.getString(TAG_SHA));
    }

    /** 给人看的一行（悬停）：掩模的写内容 id，烧的写字节数与校验和 */
    public static String describe(NBTTagCompound rom) {
        if (rom.hasKey(TAG_MASK, 8)) {
            return rom.getString(TAG_MASK);
        }
        return String.format(java.util.Locale.ROOT, "%d B, %04X", rom.getInteger(TAG_LEN), rom.getInteger(TAG_SUM));
    }
}
