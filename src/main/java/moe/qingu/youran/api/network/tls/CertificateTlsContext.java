package moe.qingu.youran.api.network.tls;

import javax.net.ssl.KeyManager;
import javax.net.ssl.SSLContext;
import javax.net.ssl.SSLEngine;
import javax.net.ssl.SSLParameters;
import javax.net.ssl.X509ExtendedKeyManager;
import java.net.Socket;
import java.security.GeneralSecurityException;
import java.security.Principal;
import java.security.PrivateKey;
import java.security.SecureRandom;
import java.security.cert.X509Certificate;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;

/**
 * 只有一张证书的服务端 TLS（网站的 443 口）：证书（连同私钥）可以在运行中换掉，换了以后新的握手用新证书，
 * 已经建好的连接不受影响。还没放证书时握手失败（客户端看到 TLS 错误）。
 * <p>
 * 网站一般不直接用它，而是用 {@code api.network.ca.CertificateClient}：它向证书签发服务申请证书、到期前续期，换证书时就换进这里。
 * 只开 TLSv1.3 与 TLSv1.2（JDK 不支持 1.3 时只用 1.2）。任意线程都可以调用。
 */
public final class CertificateTlsContext implements TlsServerContext {
    static final String[] PROTOCOLS = {"TLSv1.3", "TLSv1.2"};

    /** 一张证书（换证书时整个换掉，别名是代号：握手中途换了证书也能取到同一张的链与私钥） */
    private static final class Entry {
        final String alias;
        final PrivateKey key;
        final X509Certificate[] chain;

        Entry(String alias, PrivateKey key, X509Certificate[] chain) {
            this.alias = alias;
            this.key = key;
            this.chain = chain;
        }
    }

    private final SSLContext context;
    private volatile Entry current;
    /** 上一张：正在握手的连接可能刚选了它 */
    private volatile Entry previous;
    private long generation;

    public CertificateTlsContext() {
        try {
            context = SSLContext.getInstance("TLS");
            context.init(new KeyManager[]{new OneKeyManager()}, null, new SecureRandom());
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException("TLS 初始化失败", e);
        }
    }

    /**
     * 换上一张证书。
     *
     * @param key   证书公钥对应的私钥（EC 或 RSA）
     * @param chain 叶子在前，后面跟中间 CA（不用带根）
     */
    public synchronized void set(PrivateKey key, List<X509Certificate> chain) {
        if (key == null || chain == null || chain.isEmpty()) {
            throw new IllegalArgumentException("私钥与证书链都不能为空");
        }
        previous = current;
        current = new Entry(Long.toString(++generation), key, chain.toArray(new X509Certificate[0]));
    }

    /** 现在用的证书链（叶子在前）；还没放证书时为空表 */
    public List<X509Certificate> chain() {
        Entry e = current;
        return e == null ? Collections.<X509Certificate>emptyList() : Collections.unmodifiableList(new ArrayList<>(Arrays.asList(e.chain)));
    }

    @Override
    public SSLEngine newEngine() {
        SSLEngine e = context.createSSLEngine();
        e.setUseClientMode(false);
        SSLParameters p = e.getSSLParameters();
        List<String> supported = Arrays.asList(e.getSupportedProtocols());
        List<String> use = new ArrayList<>();
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

    private Entry byAlias(String alias) {
        Entry c = current;
        if (c != null && c.alias.equals(alias)) {
            return c;
        }
        Entry p = previous;
        return p != null && p.alias.equals(alias) ? p : null;
    }

    private final class OneKeyManager extends X509ExtendedKeyManager {
        private String alias(String keyType) {
            Entry e = current;
            if (e == null || keyType == null) {
                return null;
            }
            String alg = e.key.getAlgorithm();
            boolean ok = keyType.equals(alg) || (alg.equals("RSA") && keyType.equals("RSASSA-PSS"));
            return ok ? e.alias : null;
        }

        @Override
        public String chooseEngineServerAlias(String keyType, Principal[] issuers, SSLEngine engine) {
            return alias(keyType);
        }

        @Override
        public String chooseServerAlias(String keyType, Principal[] issuers, Socket socket) {
            return alias(keyType);
        }

        @Override
        public X509Certificate[] getCertificateChain(String alias) {
            Entry e = alias == null ? null : byAlias(alias);
            return e == null ? null : e.chain.clone();
        }

        @Override
        public PrivateKey getPrivateKey(String alias) {
            Entry e = alias == null ? null : byAlias(alias);
            return e == null ? null : e.key;
        }

        @Override
        public String[] getServerAliases(String keyType, Principal[] issuers) {
            String a = alias(keyType);
            return a == null ? null : new String[]{a};
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
