package moe.qingu.youran.api.network;

import moe.qingu.youran.api.network.ca.CertificateAuthority;
import moe.qingu.youran.network.NetworkBackend;
import moe.qingu.youran.network.NetworkHooks;
import moe.qingu.youran.network.NetworkRegistry;
import moe.qingu.youran.network.NicAccess;
import moe.qingu.youran.network.NicPort;

/**
 * 服务器内网（游戏内 IPv6 网络）给其他模组的入口。
 * <p>
 * <b>登记</b>（{@code register*}）：在模组加载阶段（预初始化到初始化）调用。只是记下来，开服时按存档里的分配表分地址：
 * 用“模组 ID + 名字”登记的机房、主机，第一次按顺序分一个空闲的，以后同一个名字拿到的地址不变；
 * 指定了具体地址、又和别的名字已分到的冲突时拒绝并记日志。开服以后再登记的要到下次开服才生效。
 * <p>
 * <b>运行时</b>（{@link #plug} 等）：只在服务端、网络运行时有用；客户端上、关服后、YouranMod 客户端包里返回 null。
 * <p>
 * <b>网卡</b>：设备接进网络的唯一途径是网卡（{@link NetworkInterface}）。电脑模组写自己的网卡子类，方块加载时 {@link #plug} 到它那一格、
 * 卸载或拆掉时 {@link #unplug}；网络不跟区块加载。协议栈在玩家客户端上的电脑用 YouranMod 自带的 {@link ClientRelayNic}。
 * 客户端上的协议栈见 {@link ClientHost}。
 * <p>
 * 域名不在这里登记：网络运行后向注册局注册（见 {@code api.network.dns}），自己跑 DNS（{@code api.network.dns.DnsServer}）。
 */
public final class YouranNetworkApi {
    /** YouranMod 自己的机房（玩法 /64 里的第 0 号 /96） */
    public static final String YOURANMOD_DATACENTER = "youranmod:main";
    /** 玩家临时地址的 /96（玩法 /64 的最后一个，序号 {@link NetworkLayout#PLAYERS_DATACENTER}）；不能在里面登记主机 */
    public static final String PLAYERS_DATACENTER = "youranmod:players";

    static {
        // 服务端网络内部给网卡装、拿端口的入口（玩家网口、关服时拔掉所有网卡）；端口字段只在这个包里能改
        NetworkHooks.setNicAccess(new NicAccess() {
            @Override
            public boolean attach(NetworkInterface nic, NicPort port) {
                synchronized (nic) {
                    if (nic.port != null) {
                        return false;
                    }
                    nic.port = port;
                    return true;
                }
            }

            @Override
            public boolean detach(NetworkInterface nic, NicPort port) {
                synchronized (nic) {
                    if (nic.port != port || port == null) {
                        return false;
                    }
                    nic.port = null;
                    return true;
                }
            }

            @Override
            public NicPort portOf(NetworkInterface nic) {
                return nic.port;
            }
        });
    }

    private YouranNetworkApi() {
    }

    // ---- 登记 ----

    /** 登记一个机房（分一个 /96），按顺序分序号 */
    public static void registerDatacenter(String key) {
        NetworkRegistry.addDatacenter(checkKey(key), -1);
    }

    /** 登记一个机房，指定序号（0–4294967294；0 是 YouranMod 的，最后一个 4294967295 留给玩家临时地址） */
    public static void registerDatacenter(String key, long index) {
        if (index < 0 || index >= NetworkLayout.PLAYERS_DATACENTER) {
            throw new IllegalArgumentException("机房序号超出范围：" + index);
        }
        NetworkRegistry.addDatacenter(checkKey(key), index);
    }

    /**
     * 在机房 {@code datacenter} 里登记一个 /128 主机，按顺序分地址。网络启动时在网络线程上调用 {@code service}。
     */
    public static void registerHost(String key, String datacenter, HostService service) {
        NetworkRegistry.addHost(checkKey(key), checkHostDatacenter(datacenter), -1, service);
    }

    /** 同上，指定主机号（/96 里的低 32 位，如 0x53 得到 {@code …::53}；0 不能用） */
    public static void registerHost(String key, String datacenter, long hostPart, HostService service) {
        if (hostPart <= 0 || hostPart > 0xFFFFFFFFL) {
            throw new IllegalArgumentException("主机号超出范围：" + hostPart);
        }
        NetworkRegistry.addHost(checkKey(key), checkHostDatacenter(datacenter), hostPart, service);
    }

    /**
     * 在机房 {@code datacenter} 里登记一个<b>原始主机</b>（{@link RawHost}）：一个 /128，进出的都是完整的 IPv6 包，
     * 不跑协议栈。地址和普通主机一样按“模组 ID:名字”分，第一次分到以后不变。
     * 网络启动时在网络线程上调用 {@code service}（每次开服一次）；运行时也可以用 {@link #rawHost} 拿到它。
     * 原始主机上没有 YouranMod 的协议栈，跑不了默认 DNS 服务（{@code api.network.dns.DnsServer}），要给它起名字时另登记一台普通主机跑 DNS；
     * 要 HTTPS 时也要在普通主机上自己申请证书（{@code api.network.ca.CertificateClient}）。
     */
    public static void registerRawHost(String key, String datacenter, RawHostService service) {
        if (service == null) {
            throw new IllegalArgumentException("service 不能为 null");
        }
        NetworkRegistry.addRawHost(checkKey(key), checkHostDatacenter(datacenter), -1, service);
    }

    /**
     * 给一个域名登记导航信息（{@code 180.yr} 上的名字、分类、一句话）。和域名注册分开：域名由网站自己向注册局注册；
     * 显示导航时这个名字解析不到地址（180.yr 经递归解析器查，按 TTL 缓存）就不显示。同一个名字登记了两次时先登记的算。
     * 模组加载阶段调用。
     */
    public static void registerNavigation(String name, SiteInfo nav) {
        if (nav == null) {
            throw new IllegalArgumentException("nav 不能为 null");
        }
        String n = DomainNames.toAscii(name);
        if (!(n.endsWith(".yr") || n.endsWith(".com")) || !n.matches("[a-z0-9]([a-z0-9.-]*[a-z0-9])?") || n.contains("..")) {
            throw new IllegalArgumentException("只能给 .yr 或 .com 下的名字登记导航信息：" + name);
        }
        NetworkRegistry.addNav(n, nav, false);
    }

    /** 给维度换一种 /72 划分与路由器（不登记就用 {@link DefaultDimensionNetwork}）。维度要在配置的全局 Wi-Fi 清单里才有地址 */
    public static void registerDimensionNetwork(int dimension, DimensionNetwork network) {
        if (network == null) {
            throw new IllegalArgumentException("network 不能为 null");
        }
        NetworkRegistry.putDimensionNetwork(dimension, network);
    }

    // ---- 运行时 ----

    /** 网络是否在运行（服务端开着、不是客户端包） */
    public static boolean isRunning() {
        return NetworkHooks.backend() != null;
    }

    /**
     * 把网卡插到维度 {@code dimension} 的 (x, y, z)，用这一格的 0 号地址。任意线程。
     * 返回分到的地址；这个维度没有全局 Wi-Fi、坐标超出范围、这一格已经插着别的网卡、这张网卡已经插着、网络没在运行时返回 null。
     */
    public static Ipv6Address plug(NetworkInterface nic, int dimension, int x, int y, int z) {
        synchronized (nic) {
            NetworkBackend b = NetworkHooks.backend();
            if (b == null || nic.port != null) {
                return null;
            }
            NicPort p = b.plug(nic, dimension, x, y, z);
            nic.port = p;
            return p == null ? null : p.address();
        }
    }

    /** 拔掉；没插着时什么都不做。任意线程 */
    public static void unplug(NetworkInterface nic) {
        NicPort p;
        synchronized (nic) {
            p = nic.port;
            nic.port = null;
        }
        NetworkBackend b = NetworkHooks.backend();
        if (p != null && b != null) {
            b.unplug(p);
        }
    }

    /** 这一格（0 号）的地址；没有地址为 null。不要求插着网卡 */
    public static Ipv6Address addressAt(int dimension, int x, int y, int z) {
        NetworkBackend b = NetworkHooks.backend();
        return b == null ? null : b.addressAt(dimension, x, y, z);
    }

    /** 登记过的主机的地址；没有或网络没在运行时为 null */
    public static Ipv6Address hostAddress(String key) {
        NetworkBackend b = NetworkHooks.backend();
        return b == null ? null : b.hostAddress(key);
    }

    /** 原始主机（{@link #registerRawHost}）；没登记、没分到地址、网络没在运行时为 null。任意线程 */
    public static RawHost rawHost(String key) {
        NetworkBackend b = NetworkHooks.backend();
        return b == null ? null : b.rawHost(key);
    }

    /**
     * 服务端：世界存档的目录（主世界存档的根，如 {@code world/}）；网络没在运行时为 null。
     * 只给出位置：要不要往里存、存在哪个子目录由调用方自己定（例如网站把证书存进自己模组的子目录，
     * 见 {@link moe.qingu.youran.api.network.ca.CertificateStore#directory}）。
     */
    public static java.nio.file.Path worldDirectory() {
        NetworkBackend b = NetworkHooks.backend();
        java.io.File f = b == null ? null : b.worldDirectory();
        return f == null ? null : f.toPath();
    }

    /** 服务器内网前缀的长度（整个服务器是一个 /48） */
    public static final int NETWORK_PREFIX_LENGTH = 48;

    /**
     * 整个服务器内网的前缀（长度 {@link #NETWORK_PREFIX_LENGTH}，如 {@code fd12:3456:789a::}）：机房、设备、玩家临时地址都在里面。
     * 游戏外接进来的机器（原始主机）把这一段路由进来就能到网络里的所有地址。网络没在运行时为 null
     */
    public static Ipv6Address networkPrefix() {
        NetworkBackend b = NetworkHooks.backend();
        return b == null ? null : b.networkPrefix();
    }

    /** 递归解析器的地址（设备上的协议栈只问它） */
    public static Ipv6Address resolverAddress() {
        NetworkBackend b = NetworkHooks.backend();
        return b == null ? null : b.resolverAddress();
    }

    /**
     * 服务端：证书签发服务（{@code ca.yr}）的 Java 接口。网站要 HTTPS 时自己申请证书，一般用
     * {@link moe.qingu.youran.api.network.ca.CertificateClient}（它自己找这个服务）。服务器没配中间 CA 时也返回它（申请都失败，
     * {@link CertificateAuthority#unavailableReason()} 说明原因）；网络没在运行时返回 null。
     */
    public static CertificateAuthority certificateAuthority() {
        NetworkBackend b = NetworkHooks.backend();
        return b == null ? null : b.certificateAuthority();
    }

    /** NTP 服务器的地址 */
    public static Ipv6Address ntpAddress() {
        NetworkBackend b = NetworkHooks.backend();
        return b == null ? null : b.ntpAddress();
    }

    private static String checkHostDatacenter(String datacenter) {
        if (PLAYERS_DATACENTER.equals(datacenter)) {
            throw new IllegalArgumentException("玩家临时地址的 /96 里不能登记主机");
        }
        return checkKey(datacenter);
    }

    private static String checkKey(String key) {
        if (key == null || !NetworkRegistry.KEY.matcher(key).matches()) {
            throw new IllegalArgumentException("登记名要写成“模组 ID:名字”（小写字母、数字、_ . - /）：" + key);
        }
        return key;
    }
}
