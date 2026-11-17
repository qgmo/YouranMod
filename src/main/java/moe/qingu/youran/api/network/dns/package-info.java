/**
 * 游戏内网络的 DNS 注册：按真实 DNS 的分层委派，运行中能注册。
 * <ul>
 * <li>{@link moe.qingu.youran.api.network.dns.DnsRegistry}：顶级域登记处（顶级域 → 注册局 + DNS 服务器），根区由它生成；</li>
 * <li>{@link moe.qingu.youran.api.network.dns.Registrar}：注册局，让别人注册下面一层的名字（以注册局身份或模组身份），带 whois；
 * 默认实现 {@link moe.qingu.youran.api.network.dns.DefaultRegistrar}（先到先得，存世界存档）；</li>
 * <li>{@link moe.qingu.youran.api.network.dns.Registration}：注册结果（成功 / 失败 / 等待）；</li>
 * <li>{@link moe.qingu.youran.api.network.dns.Whois}：注册信息；</li>
 * <li>{@link moe.qingu.youran.api.network.dns.DnsServer}：默认权威 DNS 服务，跑在自己主机的协议栈上，管一个或几个区。</li>
 * </ul>
 * 一般用法（网站 {@code shop.yr}，在网站那台主机的 {@code HostService.start} 里，网络线程上）：
 * <pre>{@code
 * DnsServer dns = DnsServer.start(ctx.stack());
 * dns.addZone("shop.yr");
 * dns.setAddresses("shop.yr", Collections.singletonList(ctx.address()));
 * Registration r = DnsRegistry.registrar("yr").register("shop.yr", "mymod", Collections.singletonList(dns.address()));
 * }</pre>
 * 改动不马上生效：按 TTL 传开（解析器缓存里的旧回答到期才换）。设备上的协议栈经 YouranMod 的递归解析器从根一路问下去。
 * DNS 与 HTTPS 证书无关：注册域名、跑 DNS 都不会签证书。
 */
package moe.qingu.youran.api.network.dns;
