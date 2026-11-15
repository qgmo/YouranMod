package moe.qingu.youran.api.network;

import moe.qingu.youran.network.NetworkBackend;
import moe.qingu.youran.network.NetworkHooks;
import moe.qingu.youran.network.NetworkRegistry;
import moe.qingu.youran.api.network.tls.TlsServerContext;

import java.util.Collection;
import java.util.function.Supplier;


/**
 * 服务器内网（游戏内 IPv6 网络）给其他模组的入口。
 * <p>
 * <b>登记</b>（{@code register*}）：在模组加载阶段（预初始化到初始化）调用。只是记下来，开服时按存档里的分配表分地址：
 * 用“模组 ID + 名字”登记的机房、主机，第一次按顺序分一个空闲的，以后同一个名字拿到的地址不变；
 * 指定了具体地址、又和别的名字已分到的冲突时拒绝并记日志。开服以后再登记的要到下次开服才生效。
 * <p>
 * <b>运行时</b>（{@link #devices()} 等）：只在服务端、网络运行时有用；客户端上、关服后、YouranMod 客户端包里返回 null。
 * <p>
 * 设备上的协议栈（客户端）见 {@code api.network.stack.NetStack} 与 {@code client.network.ClientNetwork}。
 */
public final class YouranNetworkApi {
    /** YouranMod 自己的机房（玩法 /64 里的第 0 号 /96） */
    public static final String YOURANMOD_DATACENTER = "youranmod:main";
    /** 玩家临时地址的 /96（玩法 /64 的最后一个，序号 {@link NetworkLayout#PLAYERS_DATACENTER}）；不能在里面登记主机 */
    public static final String PLAYERS_DATACENTER = "youranmod:players";

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
     * 登记一个顶级域，由主机 {@code hostKey} 做它的权威服务器（根服务器把这个顶级域委派给它）。
     * {@code zone} 不为 null 时，YouranMod 在这台主机的 UDP 53 上替你跑权威 DNS 服务器、按 {@code zone} 回答；
     * 为 null 时由你自己在这台主机上跑 DNS 服务器。{@code .com}、{@code .yr} 是 YouranMod 的，不能再登记。
     */
    public static void registerTopLevelDomain(String tld, String hostKey, DnsZone zone) {
        String t = tld.trim();
        if (t.startsWith(".")) {
            t = t.substring(1);
        }
        // 国际化顶级域：先转成 A-label（转不了时抛出 IllegalArgumentException）
        t = DomainNames.toAscii(t);
        if (!NetworkRegistry.TLD.matcher(t).matches()) {
            throw new IllegalArgumentException("顶级域不合法：" + tld);
        }
        NetworkRegistry.addTld(t, checkKey(hostKey), zone);
    }

    /**
     * 在 YouranMod 的 {@code .yr} 或 {@code .com} 下登记一个名字（如 {@code minebay.yr}、{@code 商店.悠然.yr}），指向主机 {@code hostKey}。
     * 国际化域名可以直接写 Unicode，登记时转成 A-label（见 {@link DomainNames}）。
     * {@code .com} 只给少数特殊网站做本地记录（其余 .com 名字回答不存在）。
     */
    public static void registerDomainName(String name, String hostKey) {
        // 国际化域名（如 悠然.yr）先转成 A-label（xn--…），DNS、证书里只用这个形式；转不了时抛出 IllegalArgumentException
        String n = DomainNames.toAscii(name);
        if (!(n.endsWith(".yr") || n.endsWith(".com")) || !n.matches("[a-z0-9]([a-z0-9.-]*[a-z0-9])?") || n.contains("..")) {
            throw new IllegalArgumentException("只能登记 .yr 或 .com 下的名字：" + name);
        }
        NetworkRegistry.addName(n, checkKey(hostKey));
    }

    /**
     * 同上，并登记这个网站在浏览器主页 {@code 180.yr} 上的导航信息（名字、分类、一句话，见 {@link SiteInfo}）。
     */
    public static void registerDomainName(String name, String hostKey, SiteInfo nav) {
        registerDomainName(name, hostKey);
        registerNavigation(name, nav);
    }

    /**
     * 给一个域名登记导航信息（{@code 180.yr} 上的名字、分类、一句话）。域名可以是别处登记的（例如家具模组用自己的接口登记的 MineBay）；
     * 开服时这个名字在 YouranMod 的 {@code .yr} / {@code .com} 区里查不到就不显示。同一个名字登记了两次时先登记的算。
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

    /** 设备表（服务端主线程用）；网络没在运行时为 null */
    public static NetworkDevices devices() {
        NetworkBackend b = NetworkHooks.backend();
        return b == null ? null : b.devices();
    }

    /** 登记过的主机的地址；没有或网络没在运行时为 null */
    public static Ipv6Address hostAddress(String key) {
        NetworkBackend b = NetworkHooks.backend();
        return b == null ? null : b.hostAddress(key);
    }

    /** 递归解析器的地址（设备上的协议栈只问它） */
    public static Ipv6Address resolverAddress() {
        NetworkBackend b = NetworkHooks.backend();
        return b == null ? null : b.resolverAddress();
    }

    /**
     * 服务端：给一个网站做 TLS（443）。证书由服务器配置的中间 CA 按客户端要访问的名字（SNI）现签，
     * 只签 {@code names} 当前给出的名字（每次握手都重新取，名字可以在运行时增删）。
     * 不限于机房主机：挂在任意一台设备地址上的服务端协议栈也能用。服务器没配中间 CA、网络没在运行时返回 null。
     */
    public static TlsServerContext serverTls(Supplier<? extends Collection<String>> names) {
        NetworkBackend b = NetworkHooks.backend();
        return b == null ? null : b.serverTls(names);
    }

    /**
     * 服务端：YouranMod 自己的 {@code .yr} / {@code .com} 区里登记的名字（如 {@code minebay.yr}）的地址，直接查登记表，不走 DNS 报文。
     * 其他模组顶级域里的名字、不存在的名字、网络没在运行时返回 null。给“按页面里的主机名放行玩家临时地址”这类服务端逻辑用。
     */
    public static Ipv6Address addressOfName(String name) {
        NetworkBackend b = NetworkHooks.backend();
        if (b == null || name == null) {
            return null;
        }
        String n = DomainNames.tryToAscii(name);
        return n == null ? null : b.localNameAddress(n);
    }

    /**
     * 服务端：这个地址是设备表里一台设备（家具电脑等）的地址时，返回它在哪个维度、哪一格；不是（玩家临时地址、机房主机、
     * 那一格上没有登记设备、不在这个服务器内网里）或网络没在运行时返回 null。按地址结构直接算（坐标路由里的地址就是坐标），
     * 再查一次设备表，不读世界；任意线程都可以调用（例如在网络线程上按 TCP 来源地址查）。
     * <p>
     * 路由器已经保证设备发出的包源地址就是设备地址（只收当前操作者的、源地址必须对），所以按来源查到的位置可以信。
     */
    public static DeviceLocation deviceLocation(Ipv6Address address) {
        NetworkBackend b = NetworkHooks.backend();
        return b == null || address == null ? null : b.deviceLocation(address);
    }

    /**
     * 服务端：按来源地址认人（“运营商一键登录”的那一层，服主 11-15）。给一个 IPv6 来源地址（例如 TCP 连接的对方地址），返回：
     * <ul>
     * <li>它是设备表里一台设备（家具电脑、SCP 电脑等）的地址时：哪个维度哪一格、设备声明的能力（{@link DeviceCapability}，
     * 如有没有一键登录能力）、现在操作它的玩家（网卡接着玩家客户端时是那个玩家，否则是电脑模组用 {@link DeviceHandle#setOperator} 告诉的）；</li>
     * <li>它是玩家临时地址（{@link PlayerAddresses}）时：直接就是那个玩家（{@link SourceIdentity.Kind#PLAYER}，没有位置）；</li>
     * <li>都不是（机房主机、没登记设备的格子、不在这个服务器内网里）或网络没在运行时：null。</li>
     * </ul>
     * 不读世界，只查主线程在变化时更新的表（快照），任意线程都可以调用（例如在网络线程上处理请求时）。
     * 来源可以信：路由器只收设备当前操作者发来的包、源地址必须是设备地址。
     */
    public static SourceIdentity identify(Ipv6Address address) {
        NetworkBackend b = NetworkHooks.backend();
        return b == null || address == null ? null : b.identify(address);
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
