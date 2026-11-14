package moe.qingu.youran.api.network;

/**
 * 默认的维度划分：X 20 位（有符号，−524288 到 524287）+ Y 8 位（0–255）+ Z 20 位（有符号）+ 槽位 8 位，从高到低排列。
 * 超出范围的坐标没有地址。
 */
public class DefaultDimensionNetwork extends DimensionNetwork {
    public static final int XZ_MIN = -(1 << 19);
    public static final int XZ_MAX = (1 << 19) - 1;
    public static final int Y_MIN = 0;
    public static final int Y_MAX = 255;

    public static final DefaultDimensionNetwork INSTANCE = new DefaultDimensionNetwork();

    @Override
    public long encode(int x, int y, int z, int slot) {
        if (x < XZ_MIN || x > XZ_MAX || z < XZ_MIN || z > XZ_MAX || y < Y_MIN || y > Y_MAX || slot < 0 || slot > 255) {
            return -1;
        }
        return ((long) (x & 0xFFFFF) << 36) | ((long) y << 28) | ((long) (z & 0xFFFFF) << 8) | slot;
    }

    @Override
    public BlockSlot decode(long bits56) {
        long v = bits56 & 0x00FFFFFFFFFFFFFFL;
        int x = signExtend20((int) (v >>> 36) & 0xFFFFF);
        int y = (int) (v >>> 28) & 0xFF;
        int z = signExtend20((int) (v >>> 8) & 0xFFFFF);
        int slot = (int) v & 0xFF;
        return new BlockSlot(x, y, z, slot);
    }

    private static int signExtend20(int v) {
        return (v << 12) >> 12;
    }
}
