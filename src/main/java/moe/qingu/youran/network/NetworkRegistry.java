package moe.qingu.youran.network;

import moe.qingu.youran.api.network.DimensionNetwork;
import moe.qingu.youran.api.network.DnsZone;
import moe.qingu.youran.api.network.HostService;
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
    /** 顶级域：字母、数字、连字符，不以连字符开头或结尾 */
    public static final Pattern TLD = Pattern.compile("[a-z0-9]([a-z0-9-]{0,61}[a-z0-9])?");

    private static final List<DatacenterDecl> datacenters = new ArrayList<>();
    private static final List<HostDecl> hosts = new ArrayList<>();
    private static final List<TldDecl> tlds = new ArrayList<>();
    private static final List<NameDecl> names = new ArrayList<>();
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

        HostDecl(String key, String datacenter, long fixedHost, HostService service) {
            this.key = key;
            this.datacenter = datacenter;
            this.fixedHost = fixedHost;
            this.service = service;
        }
    }

    public static final class TldDecl {
        public final String tld;
        public final String hostKey;
        public final DnsZone zone;

        TldDecl(String tld, String hostKey, DnsZone zone) {
            this.tld = tld;
            this.hostKey = hostKey;
            this.zone = zone;
        }
    }

    public static final class NameDecl {
        public final String name;
        public final String hostKey;

        NameDecl(String name, String hostKey) {
            this.name = name;
            this.hostKey = hostKey;
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
        hosts.add(new HostDecl(key, datacenter, fixedHost, service));
    }

    public static synchronized void addTld(String tld, String hostKey, DnsZone zone) {
        tlds.add(new TldDecl(tld, hostKey, zone));
    }

    public static synchronized void addName(String name, String hostKey) {
        names.add(new NameDecl(name, hostKey));
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

    public static synchronized List<TldDecl> tlds() {
        return Collections.unmodifiableList(new ArrayList<>(tlds));
    }

    public static synchronized List<NameDecl> names() {
        return Collections.unmodifiableList(new ArrayList<>(names));
    }

    public static synchronized Map<Integer, DimensionNetwork> dimensionNetworks() {
        return Collections.unmodifiableMap(new HashMap<>(dimensionNetworks));
    }
}
