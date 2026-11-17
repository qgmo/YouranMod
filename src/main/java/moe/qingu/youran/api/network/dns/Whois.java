package moe.qingu.youran.api.network.dns;

import moe.qingu.youran.api.network.Ipv6Address;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/** 一个已注册的域名（或顶级域）的 whois 信息。不可变 */
public final class Whois {
    /** 域名（A-label，小写，不带结尾点），顶级域是不带点的一段（如 {@code yr}） */
    public final String name;
    /** 经手的注册局（顶级域是它自己的注册局） */
    public final Registrar registrar;
    /** 所属模组：以模组身份注册的是那个模组，以注册局身份注册的是那个注册局所属的模组 */
    public final String owner;
    /** 是不是以注册局身份注册的（这个名字可以再往下分发） */
    public final boolean registrarIdentity;
    /**
     * 往下分发用的注册局；以模组身份注册的为 null。以注册局身份注册、但这次开服申请人还没重新交注册局对象时也是 null
     * （注册信息存在注册局那里，注册局对象存不下来）
     */
    public final Registrar subRegistrar;
    /** 这个名字的 DNS 服务器地址（每个都是 /128） */
    public final List<Ipv6Address> servers;
    /** 第一次注册的时间（毫秒） */
    public final long registeredAt;

    public Whois(String name, Registrar registrar, String owner, boolean registrarIdentity, Registrar subRegistrar,
                 List<Ipv6Address> servers, long registeredAt) {
        this.name = name;
        this.registrar = registrar;
        this.owner = owner;
        this.registrarIdentity = registrarIdentity;
        this.subRegistrar = subRegistrar;
        this.servers = Collections.unmodifiableList(new ArrayList<>(servers));
        this.registeredAt = registeredAt;
    }

    @Override
    public String toString() {
        return name + "：注册局 " + (registrar == null ? "?" : registrar.modId()) + "，所属 " + owner
                + (registrarIdentity ? "（注册局身份）" : "（模组身份）") + "，DNS 服务器 " + servers;
    }
}
