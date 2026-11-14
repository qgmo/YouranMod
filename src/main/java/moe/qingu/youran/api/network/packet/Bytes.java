package moe.qingu.youran.api.network.packet;

/** 大端读写 */
public final class Bytes {
    private Bytes() {
    }

    public static int u8(byte[] b, int i) {
        return b[i] & 0xFF;
    }

    public static int u16(byte[] b, int i) {
        return ((b[i] & 0xFF) << 8) | (b[i + 1] & 0xFF);
    }

    public static long u32(byte[] b, int i) {
        return ((long) u16(b, i) << 16) | u16(b, i + 2);
    }

    public static long u64(byte[] b, int i) {
        return (u32(b, i) << 32) | u32(b, i + 4);
    }

    public static void put16(byte[] b, int i, int v) {
        b[i] = (byte) (v >>> 8);
        b[i + 1] = (byte) v;
    }

    public static void put32(byte[] b, int i, long v) {
        b[i] = (byte) (v >>> 24);
        b[i + 1] = (byte) (v >>> 16);
        b[i + 2] = (byte) (v >>> 8);
        b[i + 3] = (byte) v;
    }

    public static void put64(byte[] b, int i, long v) {
        put32(b, i, v >>> 32);
        put32(b, i + 4, v);
    }
}
