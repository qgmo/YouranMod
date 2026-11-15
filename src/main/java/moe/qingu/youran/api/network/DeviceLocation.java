package moe.qingu.youran.api.network;

/** 一台设备在哪（维度 ID 与方块坐标）。由 {@link YouranNetworkApi#deviceLocation} 按地址查出来。 */
public final class DeviceLocation {
    /** Minecraft 的维度 ID（不是网络里的维度序号） */
    public final int dimension;
    public final int x;
    public final int y;
    public final int z;

    public DeviceLocation(int dimension, int x, int y, int z) {
        this.dimension = dimension;
        this.x = x;
        this.y = y;
        this.z = z;
    }

    /** {@code 维度 x y z}（空格分隔，如 {@code 0 120 64 -35}） */
    @Override
    public String toString() {
        return dimension + " " + x + " " + y + " " + z;
    }

    @Override
    public boolean equals(Object o) {
        if (!(o instanceof DeviceLocation)) {
            return false;
        }
        DeviceLocation l = (DeviceLocation) o;
        return l.dimension == dimension && l.x == x && l.y == y && l.z == z;
    }

    @Override
    public int hashCode() {
        return ((dimension * 31 + x) * 31 + y) * 31 + z;
    }
}
