package moe.qingu.youran.network;

import moe.qingu.youran.api.network.DimensionNetwork;
import moe.qingu.youran.api.network.HostService;
import moe.qingu.youran.api.network.RawHostService;
import moe.qingu.youran.api.network.SiteInfo;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;

/**
 * 其他模组（和 YouranMod 自己）通过 {@code api.network.YouranNetworkApi} 做的登记。只是记下来：
 * 地址在服务端网络启动时按存档里的分配表分（见服务端的 {@code server.net}）。两端都有这个类（客户端包里也有 api），
 * 客户端上登记了也没有作用。
 */
public final class NetworkRegistry {
    /** 登记名：模组 ID + 冒号 + 名字（如 {@code cfm:minebay}） */
    public static final Pattern KEY = Pattern.compile("[a-z0-9_.-]{1,64}:[a-z0-9_./-]{1,64}");

    private static final List<DatacenterDecl> datacenters = new ArrayList<>();
    private static final List<HostDecl> hosts = new ArrayList<>();
    private static final Map<Integer, DimensionNetwork> dimensionNetworks = new HashMap<>();
    private static final List<NavDecl> navs = new ArrayList<>();

    private NetworkRegistry() {
    }

    public static final class DatacenterDecl {
        public final String key;
        /** 指定的机房序号；-1 表示按顺序分 */
        public final long fixedIndex;

        DatacenterDecl(String key, long fixedIndex) {
            this.key = key;
            this.fixedIndex = fixedIndex;
        }
    }

    public static final class HostDecl {
        public final String key;
        public final String datacenter;
        /** 指定的主机号（/96 里的低 32 位）；-1 表示按顺序分 */
        public final long fixedHost;
        public final HostService service;
        /** 原始主机的回调；不为 null 时这是原始主机（不建协议栈，{@link #service} 为 null） */
        public final RawHostService rawService;

        HostDecl(String key, String datacenter, long fixedHost, HostService service, RawHostService rawService) {
            this.key = key;
            this.datacenter = datacenter;
            this.fixedHost = fixedHost;
            this.service = service;
            this.rawService = rawService;
        }

        public boolean isRaw() {
            return rawService != null;
        }
    }

    /** 导航信息（浏览器主页 180.yr 用） */
    public static final class NavDecl {
        /** 域名（A-label） */
        public final String name;
        public final SiteInfo info;
        /** 只是兜底（YouranMod 替别的模组写的默认值）：同一个名字有别人登记的就用别人的 */
        public final boolean fallback;

        NavDecl(String name, SiteInfo info, boolean fallback) {
            this.name = name;
            this.info = info;
            this.fallback = fallback;
        }
    }

    public static synchronized void addNav(String name, SiteInfo info, boolean fallback) {
        navs.add(new NavDecl(name, info, fallback));
    }

    public static synchronized List<NavDecl> navs() {
        return Collections.unmodifiableList(new ArrayList<>(navs));
    }

    public static synchronized void addDatacenter(String key, long fixedIndex) {
        datacenters.add(new DatacenterDecl(key, fixedIndex));
    }

    public static synchronized void addHost(String key, String datacenter, long fixedHost, HostService service) {
        hosts.add(new HostDecl(key, datacenter, fixedHost, service, null));
    }

    public static synchronized void addRawHost(String key, String datacenter, long fixedHost, RawHostService service) {
        hosts.add(new HostDecl(key, datacenter, fixedHost, null, service));
    }

    public static synchronized void putDimensionNetwork(int dimension, DimensionNetwork network) {
        dimensionNetworks.put(dimension, network);
    }

    public static synchronized List<DatacenterDecl> datacenters() {
        return Collections.unmodifiableList(new ArrayList<>(datacenters));
    }

    public static synchronized List<HostDecl> hosts() {
        return Collections.unmodifiableList(new ArrayList<>(hosts));
    }

    public static synchronized Map<Integer, DimensionNetwork> dimensionNetworks() {
        return Collections.unmodifiableMap(new HashMap<>(dimensionNetworks));
    }
}
