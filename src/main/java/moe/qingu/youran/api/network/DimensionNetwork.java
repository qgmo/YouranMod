package moe.qingu.youran.api.network;

/**
 * 一个维度的 /72 怎么划分（剩下 56 位），以及这个维度的路由器。默认实现是 {@link DefaultDimensionNetwork}；
 * 其他模组可以给自己的维度换一种划分（{@link YouranNetworkApi#registerDimensionNetwork}）。
 * <p>
 * 方法在网络线程上调用（{@link #route}），也可能在主线程上调用（{@link #encode}），实现要无状态或线程安全；
 * 不要在这里读写世界：那一格插没插着网卡、能不能收由 {@link Routing} 查插着的网卡表。
 */
public abstract class DimensionNetwork {
    /**
     * 坐标与槽位（0 号给这一格上的设备，1–255 留给以后这一格的路由器）→ 低 56 位；没有地址（超出范围）返回 -1。
     */
    public abstract long encode(int x, int y, int z, int slot);

    /** 低 56 位 → 坐标与槽位；不是这个方案能编出来的值返回 null */
    public abstract BlockSlot decode(long bits56);

    /**
     * 路由器：把发往本维度 {@code bits56} 的包交给那一格插着的网卡。默认实现：按本维度的划分解出是哪一格，交给那一格的网卡；
     * 那里没插着网卡、网卡收不了（{@link NetworkInterface#receive} 返回 false）时回“目标不可达（地址不可达）”。
     * 1–255 号槽位现在都没有路由器，也回地址不可达。
     */
    public void route(Routing routing, byte[] packet, long bits56) {
        BlockSlot b = decode(bits56);
        if (b == null || b.slot != 0 || !routing.deliverToDevice(b.x, b.y, b.z, packet)) {
            routing.unreachable(packet, Routing.UNREACH_ADDRESS);
        }
    }

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

    /** 维度路由器能做的事（由 YouranMod 的 /48 路由器提供；在网络线程上调用） */
    public interface Routing {
        /** ICMPv6 目标不可达的代码：地址不可达 */
        int UNREACH_ADDRESS = 3;
        /** ICMPv6 目标不可达的代码：没有路由 */
        int UNREACH_NO_ROUTE = 0;

        /** 本维度的维度 ID（Minecraft 的） */
        int dimension();

        /** 交给本维度 (x, y, z) 上插着的网卡；没插着、网卡收不了时返回 false */
        boolean deliverToDevice(int x, int y, int z, byte[] packet);

        /** 回 ICMPv6 目标不可达给发包的人 */
        void unreachable(byte[] packet, int code);
    }
}
