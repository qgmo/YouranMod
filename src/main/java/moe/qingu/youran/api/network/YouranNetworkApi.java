package moe.qingu.youran.api.network;

import moe.qingu.youran.network.NetworkBackend;
import moe.qingu.youran.network.NetworkHooks;
import moe.qingu.youran.network.NetworkRegistry;
import moe.qingu.youran.api.network.tls.TlsServerContext;

import java.util.Collection;
import java.util.function.Supplier;

import java.util.Locale;

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

    private YouranNetworkApi() {
    }

    // ---- 登记 ----

    /** 登记一个机房（分一个 /96），按顺序分序号 */
    public static void registerDatacenter(String key) {
        NetworkRegistry.addDatacenter(checkKey(key), -1);
    }

    /** 登记一个机房，指定序号（0–4294967295；0 是 YouranMod 的） */
    public static void registerDatacenter(String key, long index) {
        if (index < 0 || index > 0xFFFFFFFFL) {
            throw new IllegalArgumentException("机房序号超出范围：" + index);
        }
        NetworkRegistry.addDatacenter(checkKey(key), index);
    }

    /**
     * 在机房 {@code datacenter} 里登记一个 /128 主机，按顺序分地址。网络启动时在网络线程上调用 {@code service}。
     */
    public static void registerHost(String key, String datacenter, HostService service) {
        NetworkRegistry.addHost(checkKey(key), checkKey(datacenter), -1, service);
    }

    /** 同上，指定主机号（/96 里的低 32 位，如 0x53 得到 {@code …::53}；0 不能用） */
    public static void registerHost(String key, String datacenter, long hostPart, HostService service) {
        if (hostPart <= 0 || hostPart > 0xFFFFFFFFL) {
            throw new IllegalArgumentException("主机号超出范围：" + hostPart);
        }
        NetworkRegistry.addHost(checkKey(key), checkKey(datacenter), hostPart, service);
    }

    /**
     * 登记一个顶级域，由主机 {@code hostKey} 做它的权威服务器（根服务器把这个顶级域委派给它）。
     * {@code zone} 不为 null 时，YouranMod 在这台主机的 UDP 53 上替你跑权威 DNS 服务器、按 {@code zone} 回答；
     * 为 null 时由你自己在这台主机上跑 DNS 服务器。{@code .com}、{@code .yr} 是 YouranMod 的，不能再登记。
     */
    public static void registerTopLevelDomain(String tld, String hostKey, DnsZone zone) {
        String t = tld.toLowerCase(Locale.ROOT);
        if (t.startsWith(".")) {
            t = t.substring(1);
        }
        if (!NetworkRegistry.TLD.matcher(t).matches()) {
            throw new IllegalArgumentException("顶级域不合法：" + tld);
        }
        NetworkRegistry.addTld(t, checkKey(hostKey), zone);
    }

    /**
     * 在 YouranMod 的 {@code .yr} 或 {@code .com} 下登记一个名字（如 {@code minebay.yr}），指向主机 {@code hostKey}。
     * {@code .com} 只给少数特殊网站做本地记录（其余 .com 名字回答不存在）。
     */
    public static void registerDomainName(String name, String hostKey) {
        String n = name.toLowerCase(Locale.ROOT);
        if (n.endsWith(".")) {
            n = n.substring(0, n.length() - 1);
        }
        if (!(n.endsWith(".yr") || n.endsWith(".com")) || !n.matches("[a-z0-9]([a-z0-9.-]*[a-z0-9])?") || n.contains("..")) {
            throw new IllegalArgumentException("只能登记 .yr 或 .com 下的名字：" + name);
        }
        NetworkRegistry.addName(n, checkKey(hostKey));
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

    /** NTP 服务器的地址 */
    public static Ipv6Address ntpAddress() {
        NetworkBackend b = NetworkHooks.backend();
        return b == null ? null : b.ntpAddress();
    }

    private static String checkKey(String key) {
        if (key == null || !NetworkRegistry.KEY.matcher(key).matches()) {
            throw new IllegalArgumentException("登记名要写成“模组 ID:名字”（小写字母、数字、_ . - /）：" + key);
        }
        return key;
    }
}
