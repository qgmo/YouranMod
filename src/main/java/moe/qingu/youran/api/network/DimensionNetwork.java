package moe.qingu.youran.api.network;

/**
 * 一个维度的 /72 怎么划分（剩下 56 位）：插网卡时按坐标算出这一格的地址（坐标层照旧私有分配，服主 11-27）。
 * 默认实现是 {@link DefaultDimensionNetwork}；其他模组可以给自己的维度换一种划分（{@link YouranNetworkApi#registerDimensionNetwork}）。
 * <p>
 * 只管“分地址”：网络（维度路由器）不从地址解码坐标，按插着的网卡表里的地址交包（以后可能有不按坐标编址的网络）。
 * {@link #decode} 只是划分方案自己的反函数，网络不用它。方法可能在任意线程上调用，实现要无状态或线程安全；不要在这里读写世界。
 */
public abstract class DimensionNetwork {
    /**
     * 坐标与槽位（0 号给这一格上的设备，1–255 留给以后这一格的路由器）→ 低 56 位；没有地址（超出范围）返回 -1。
     */
    public abstract long encode(int x, int y, int z, int slot);

    /** 低 56 位 → 坐标与槽位；不是这个方案能编出来的值返回 null */
    public abstract BlockSlot decode(long bits56);

    /** 一格里的一个地址 */
    public static final class BlockSlot {
        public final int x;
        public final int y;
        public final int z;
        public final int slot;

        public BlockSlot(int x, int y, int z, int slot) {
            this.x = x;
            this.y = y;
            this.z = z;
            this.slot = slot;
        }

        @Override
        public boolean equals(Object o) {
            if (!(o instanceof BlockSlot)) {
                return false;
            }
            BlockSlot b = (BlockSlot) o;
            return b.x == x && b.y == y && b.z == z && b.slot == slot;
        }

        @Override
        public int hashCode() {
            return ((x * 31 + y) * 31 + z) * 31 + slot;
        }

        @Override
        public String toString() {
            return "(" + x + ", " + y + ", " + z + ")#" + slot;
        }
    }
}
