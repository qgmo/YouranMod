package moe.qingu.youran.api.network.dns;

import moe.qingu.youran.api.network.Ipv6Address;

import java.util.Collection;

/**
 * 注册局：管一个区（顶级域，或以注册局身份注册、可以往下分发的二级域名）下面一层名字的注册。
 * 谁能注册、要不要审核、收不收钱，都由注册局自己定；注册信息也由注册局自己保存。
 * 注册成功后，注册局负责让它那个区的 DNS 把这个名字委派给申请人交的 DNS 服务器。
 * <p>
 * 申请人有两种身份，两种都要交自己的 DNS 服务器地址（IPv6 /128，至少一个）：
 * <ul>
 * <li>交一个注册局对象（{@link #register(String, Registrar, Collection)}）：这个名字以后可以再往下分发；</li>
 * <li>以模组身份（{@link #register(String, String, Collection)}）：名字归这个模组。</li>
 * </ul>
 * YouranMod 提供先到先得的默认实现 {@link DefaultRegistrar}。方法可能在任意线程上调用，实现要线程安全。
 */
public interface Registrar {
    /** 这个注册局所属的模组（模组 ID） */
    String modId();

    /** 以注册局身份注册 {@code name}（名字归 {@code registrar} 所属的模组，可以再往下分发） */
    Registration register(String name, Registrar registrar, Collection<Ipv6Address> servers);

    /** 以模组身份注册 {@code name}（名字归模组 {@code modId}） */
    Registration register(String name, String modId, Collection<Ipv6Address> servers);

    /**
     * 本区下的 whois：{@code name} 或它的上级里已经注册的那一个的信息；没注册过为 null。
     * 以注册局身份注册的名字下面的名字，交给那个注册局去查。
     */
    Whois whois(String name);
}
