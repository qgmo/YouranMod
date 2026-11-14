package moe.qingu.youran.server.net.tls;

import moe.qingu.youran.api.network.tls.TlsServerContext;
import org.apache.logging.log4j.LogManager;

import javax.net.ssl.ExtendedSSLSession;
import javax.net.ssl.KeyManager;
import javax.net.ssl.SNIHostName;
import javax.net.ssl.SNIServerName;
import javax.net.ssl.SSLContext;
import javax.net.ssl.SSLEngine;
import javax.net.ssl.SSLParameters;
import javax.net.ssl.SSLSession;
import javax.net.ssl.X509ExtendedKeyManager;
import java.net.Socket;
import java.security.GeneralSecurityException;
import java.security.Principal;
import java.security.PrivateKey;
import java.security.SecureRandom;
import java.security.cert.X509Certificate;
import java.util.Arrays;
import java.util.Collection;
import java.util.Locale;
import java.util.function.Supplier;
import java.util.regex.Pattern;

/**
 * 一个网站的 TLS：按客户端握手里的主机名（SNI）选证书，名字归这个网站（{@code names} 当前给出的名字之一）时用它，
 * 否则用默认名字（第一个）；证书从 {@link LeafCache} 取，没有就现签。名字可以在运行时变（每次握手都重新问 {@code names}）。
 */
public final class IssuingTlsContext implements TlsServerContext {
    static final Pattern DNS_NAME = Pattern.compile("[a-z0-9]([a-z0-9-]{0,61}[a-z0-9])?(\\.[a-z0-9]([a-z0-9-]{0,61}[a-z0-9])?)+");
    static final String[] PROTOCOLS = {"TLSv1.3", "TLSv1.2"};

    private final LeafCache cache;
    private final Supplier<? extends Collection<String>> names;
    private final SSLContext context;

    public IssuingTlsContext(LeafCache cache, Supplier<? extends Collection<String>> names) throws GeneralSecurityException {
        this.cache = cache;
        this.names = names;
        this.context = SSLContext.getInstance("TLS");
        this.context.init(new KeyManager[]{new SniKeyManager()}, null, new SecureRandom());
    }

    @Override
    public SSLEngine newEngine() {
        SSLEngine e = context.createSSLEngine();
        e.setUseClientMode(false);
        SSLParameters p = e.getSSLParameters();
        java.util.List<String> supported = Arrays.asList(e.getSupportedProtocols());
        java.util.List<String> use = new java.util.ArrayList<>();
        for (String s : PROTOCOLS) {
            if (supported.contains(s)) {
                use.add(s);
            }
        }
        p.setProtocols(use.toArray(new String[0]));
        p.setNeedClientAuth(false);
        p.setWantClientAuth(false);
        e.setSSLParameters(p);
        return e;
    }

    /** 这次握手用哪个名字的证书；没有可用的名字为 null */
    String chooseName(String sni) {
        Collection<String> owned = names.get();
        if (owned == null || owned.isEmpty()) {
            return null;
        }
        if (sni != null) {
            String n = sni.toLowerCase(Locale.ROOT);
            if (owned.contains(n) && DNS_NAME.matcher(n).matches()) {
                return n;
            }
        }
        for (String n : owned) {
            if (DNS_NAME.matcher(n).matches()) {
                return n;
            }
        }
        return null;
    }

    private static String sniOf(SSLSession s) {
        if (s instanceof ExtendedSSLSession) {
            for (SNIServerName n : ((ExtendedSSLSession) s).getRequestedServerNames()) {
                if (n instanceof SNIHostName) {
                    return ((SNIHostName) n).getAsciiName();
                }
            }
        }
        return null;
    }

    /** 别名就是域名 */
    private final class SniKeyManager extends X509ExtendedKeyManager {
        private String alias(String keyType, String sni) {
            // 叶子都是 EC 密钥
            if (!"EC".equals(keyType)) {
                return null;
            }
            String name = chooseName(sni);
            if (name == null) {
                return null;
            }
            try {
                cache.get(name);
                return name;
            } catch (GeneralSecurityException | RuntimeException e) {
                LogManager.getLogger("YouranMod").error("游戏内网络：给 {} 签证书失败", name, e);
                return null;
            }
        }

        @Override
        public String chooseEngineServerAlias(String keyType, Principal[] issuers, SSLEngine engine) {
            SSLSession hs = engine == null ? null : engine.getHandshakeSession();
            return alias(keyType, hs == null ? null : sniOf(hs));
        }

        @Override
        public String chooseServerAlias(String keyType, Principal[] issuers, Socket socket) {
            return alias(keyType, null);
        }

        @Override
        public X509Certificate[] getCertificateChain(String alias) {
            IntermediateCa.Issued i = alias == null ? null : cache.peek(alias);
            return i == null ? null : i.chain.toArray(new X509Certificate[0]);
        }

        @Override
        public PrivateKey getPrivateKey(String alias) {
            IntermediateCa.Issued i = alias == null ? null : cache.peek(alias);
            return i == null ? null : i.key;
        }

        @Override
        public String[] getServerAliases(String keyType, Principal[] issuers) {
            return null;
        }

        @Override
        public String[] getClientAliases(String keyType, Principal[] issuers) {
            return null;
        }

        @Override
        public String chooseClientAlias(String[] keyType, Principal[] issuers, Socket socket) {
            return null;
        }
    }
}
