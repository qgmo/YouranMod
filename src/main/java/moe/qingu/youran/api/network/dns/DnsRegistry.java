package moe.qingu.youran.api.network.dns;

import moe.qingu.youran.api.network.DomainNames;
import moe.qingu.youran.api.network.Ipv6Address;
import moe.qingu.youran.network.DnsState;

import java.util.Collection;
import java.util.List;

/**
 * 顶级域登记处（全局）：只管顶级域。登记一个顶级域要交它的注册局和一组 DNS 服务器地址（IPv6 /128），
 * 这几台就是这个顶级域的标准 DNS 服务器；根区由登记表生成，每个顶级域委派给它登记的 DNS 服务器。
 * <p>
 * 登记只在这次开服里有效（关服时清空），每次网络启动后重新登记：一般在跑顶级域 DNS 的那台主机的
 * {@code HostService.start} 里登记。{@code .yr}、{@code .com} 是 YouranMod 的，开服时最先登记。
 * 改动按缓存时间传开（根区的“查无此名”缓存 {@value DnsServer#TTL_NEGATIVE} 秒）。任意线程。
 */
public final class DnsRegistry {
    private DnsRegistry() {
    }

    /**
     * 登记顶级域 {@code tld}（如 {@code cfm}；可以写 Unicode）。先到先得：已经由别的模组（按注册局所属的模组算）登记了时失败；
     * 同一个模组再登记是更新注册局和 DNS 服务器。只会返回成功或失败。
     *
     * @throws IllegalArgumentException 顶级域写法不对、没有 DNS 服务器地址
     */
    public static Registration register(String tld, Registrar registrar, Collection<Ipv6Address> servers) {
        return DnsState.registerTld(tld, registrar, servers);
    }

    /** 顶级域 {@code tld}（不带点，A-label）的注册局；没登记为 null */
    public static Registrar registrar(String tld) {
        String t = DomainNames.tryToAscii(tld);
        return t == null ? null : DnsState.registrar(t);
    }

    /** 登记了的顶级域 */
    public static List<String> tlds() {
        return DnsState.tlds();
    }

    /**
     * whois：{@code name} 是顶级域时给顶级域的信息（注册局、所属模组、DNS 服务器）；否则交给它所在顶级域的注册局查
     * （给已经注册的那一层的信息，如问 {@code dado.wixsite.com} 得到 {@code wixsite.com} 的）。没有为 null
     */
    public static Whois whois(String name) {
        String n = DomainNames.tryToAscii(name);
        if (n == null) {
            return null;
        }
        int dot = n.lastIndexOf('.');
        if (dot < 0) {
            return DnsState.tldWhois(n);
        }
        Registrar r = DnsState.registrar(n.substring(dot + 1));
        return r == null ? null : r.whois(n);
    }
}
