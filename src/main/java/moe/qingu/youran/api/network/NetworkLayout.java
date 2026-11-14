package moe.qingu.youran.api.network;

/**
 * 服务器内网的地址划分（网络系统设计，服主 11-14 定）。整个服务器是一个 /48（ULA，{@code fd} 开头，首次启动随机生成）：
 * <pre>
 *   /48 前缀
 *   ├─ 第 0 号 /64：坐标路由
 *   │    └─ 8 位维度序号 → 每个维度一个 /72，剩下 56 位由维度的 {@link DimensionNetwork} 划分
 *   └─ 第 1 号 /64：玩法（“虚拟机房”）
 *        ├─ 32 位机房序号 → 每个机房一个 /96，剩下 32 位是机房里的主机（/128）
 *        └─ 最后一个 /96（机房序号 {@value #PLAYERS_DATACENTER_HEX}）：玩家临时地址。玩家上线时分一个随机的 /128，
 *           下线收回，不进存档；只能收发服务器放行的数据（见 {@link PlayerAddresses}）
 * </pre>
 * 这个类只做地址的拼与拆，不管分配。
 */
public final class NetworkLayout {
    public static final int SUBNET_COORDINATES = 0;
    public static final int SUBNET_GAMEPLAY = 1;
    /** 维度序号最多 256 个 */
    public static final int MAX_DIMENSIONS = 256;
    /** 玩家临时地址所在的 /96：玩法 /64 里的最后一个机房序号（服主 11-14：留一个 /96 给玩家） */
    public static final long PLAYERS_DATACENTER = 0xFFFFFFFFL;
    static final String PLAYERS_DATACENTER_HEX = "0xFFFFFFFF";

    /** /48 前缀的高 48 位（放在 long 的高 48 位，低 16 位为 0） */
    private final long prefixHigh;

    public NetworkLayout(long prefixHigh) {
        this.prefixHigh = prefixHigh & 0xFFFFFFFFFFFF0000L;
    }

    /** 由 40 位全局 ID（RFC 4193）得到 {@code fdXX:XXXX:XXXX::/48} */
    public static NetworkLayout fromGlobalId(long globalId40) {
        return new NetworkLayout((0xFDL << 56) | ((globalId40 & 0xFFFFFFFFFFL) << 16));
    }

    public long globalId() {
        return (prefixHigh >>> 16) & 0xFFFFFFFFFFL;
    }

    public Ipv6Address prefix() {
        return new Ipv6Address(prefixHigh, 0);
    }

    public boolean contains(Ipv6Address a) {
        return a.inPrefix(prefix(), 48);
    }

    /** 地址在 /48 里时的子网号（16 位），否则 -1 */
    public int subnetOf(Ipv6Address a) {
        return contains(a) ? (int) (a.high() & 0xFFFF) : -1;
    }

    // ---- 坐标路由 ----

    public Ipv6Address dimensionPrefix(int dimensionIndex) {
        return new Ipv6Address(prefixHigh | SUBNET_COORDINATES, ((long) (dimensionIndex & 0xFF)) << 56);
    }

    /** 维度 /72 里的地址：{@code bits56} 是维度方案给的低 56 位 */
    public Ipv6Address coordinateAddress(int dimensionIndex, long bits56) {
        return new Ipv6Address(prefixHigh | SUBNET_COORDINATES, (((long) (dimensionIndex & 0xFF)) << 56) | (bits56 & 0x00FFFFFFFFFFFFFFL));
    }

    public static int dimensionIndexOf(Ipv6Address a) {
        return (int) (a.low() >>> 56);
    }

    public static long dimensionBitsOf(Ipv6Address a) {
        return a.low() & 0x00FFFFFFFFFFFFFFL;
    }

    // ---- 玩法 ----

    public Ipv6Address datacenterPrefix(long datacenterIndex) {
        return new Ipv6Address(prefixHigh | SUBNET_GAMEPLAY, (datacenterIndex & 0xFFFFFFFFL) << 32);
    }

    public Ipv6Address hostAddress(long datacenterIndex, long hostPart) {
        return new Ipv6Address(prefixHigh | SUBNET_GAMEPLAY, ((datacenterIndex & 0xFFFFFFFFL) << 32) | (hostPart & 0xFFFFFFFFL));
    }

    public static long datacenterIndexOf(Ipv6Address a) {
        return a.low() >>> 32;
    }

    public static long hostPartOf(Ipv6Address a) {
        return a.low() & 0xFFFFFFFFL;
    }

    // ---- 玩家临时地址 ----

    /** 玩家 /96 的前缀 */
    public Ipv6Address playersPrefix() {
        return datacenterPrefix(PLAYERS_DATACENTER);
    }

    /** 玩家 /96 里的地址（{@code hostPart} 是低 32 位） */
    public Ipv6Address playerAddress(long hostPart) {
        return hostAddress(PLAYERS_DATACENTER, hostPart);
    }

    /** 是不是玩家临时地址（在这个 /48 的玩家 /96 里） */
    public boolean isPlayerAddress(Ipv6Address a) {
        return subnetOf(a) == SUBNET_GAMEPLAY && datacenterIndexOf(a) == PLAYERS_DATACENTER;
    }
}
