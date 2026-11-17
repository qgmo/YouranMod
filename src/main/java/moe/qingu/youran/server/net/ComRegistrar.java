package moe.qingu.youran.server.net;

import moe.qingu.youran.api.network.dns.DefaultRegistrar;
import moe.qingu.youran.api.network.dns.DnsServer;

import java.io.File;

/**
 * YouranMod 的 {@code .com} 注册局：现实里存在的域名，不能随便注册，只收以模组身份注册的（模组要域名一般是玩法需要，服主 11-17），
 * 以注册局身份申请的一律失败。其余同默认注册局（先到先得，存世界存档）。
 */
final class ComRegistrar extends DefaultRegistrar {
    static final String REFUSED = ".com 只收以模组身份注册的名字";

    ComRegistrar(String modId, DnsServer dns, File file) {
        super(modId, "com", dns, file);
    }

    @Override
    protected String refuse(String name, String owner, boolean registrarIdentity) {
        return registrarIdentity ? REFUSED : null;
    }
}
