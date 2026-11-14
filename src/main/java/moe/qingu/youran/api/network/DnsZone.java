package moe.qingu.youran.api.network;

import java.util.List;

/** 一个顶级域的记录（由 YouranMod 的权威 DNS 服务器替注册的模组回答查询；在网络线程上调用） */
@FunctionalInterface
public interface DnsZone {
    /**
     * 名字（小写、不带结尾点，如 {@code shop.example}）的 AAAA 地址。名字不存在返回 null；
     * 名字存在但没有 AAAA 返回空表。
     */
    List<Ipv6Address> aaaa(String name);
}
