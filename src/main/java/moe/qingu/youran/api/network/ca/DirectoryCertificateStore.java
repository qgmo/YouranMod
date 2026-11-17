package moe.qingu.youran.api.network.ca;

import moe.qingu.youran.api.network.tls.Pem;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.security.GeneralSecurityException;
import java.security.KeyFactory;
import java.security.PrivateKey;
import java.security.cert.X509Certificate;
import java.security.spec.PKCS8EncodedKeySpec;
import java.util.List;
import java.util.function.LongSupplier;

/** {@link CertificateStore#directory}：一个目录里的 {@value #CHAIN} 与 {@value #KEY} */
final class DirectoryCertificateStore implements CertificateStore {
    private static final Logger LOG = LogManager.getLogger("YouranMod");
    static final String CHAIN = "fullchain.pem";
    static final String KEY = "privkey.pem";

    private final Path dir;
    private final LongSupplier clock;

    DirectoryCertificateStore(Path dir, LongSupplier clock) {
        if (dir == null || clock == null) {
            throw new IllegalArgumentException("dir、clock 不能为 null");
        }
        this.dir = dir;
        this.clock = clock;
    }

    @Override
    public Saved load() {
        Path chainFile = dir.resolve(CHAIN);
        Path keyFile = dir.resolve(KEY);
        boolean hasChain = Files.isRegularFile(chainFile);
        boolean hasKey = Files.isRegularFile(keyFile);
        if (!hasChain && !hasKey) {
            return null;
        }
        if (!hasChain || !hasKey) {
            LOG.warn("游戏内网络：{} 里只有 {}，当作没存证书", dir, hasChain ? CHAIN : KEY);
            return null;
        }
        try {
            List<X509Certificate> chain = Pem.certificates(Pem.text(Files.readAllBytes(chainFile)));
            if (chain.isEmpty()) {
                throw new GeneralSecurityException(CHAIN + " 里没有证书");
            }
            return new Saved(chain, privateKey(Files.readAllBytes(keyFile)));
        } catch (IOException | GeneralSecurityException | RuntimeException e) {
            String suffix = ".bad-" + clock.getAsLong();
            boolean moved = rename(chainFile, suffix) & rename(keyFile, suffix);
            LOG.error("游戏内网络：{} 里存的证书读不了，{}，当作没有：{}", dir, moved ? "改名成 *" + suffix + " 留着" : "也改不了名", e.toString());
            return null;
        }
    }

    @Override
    public void save(List<X509Certificate> chain, PrivateKey key) throws IOException {
        if (chain == null || chain.isEmpty() || key == null) {
            throw new IllegalArgumentException("证书链与私钥都不能为空");
        }
        StringBuilder pem = new StringBuilder();
        try {
            for (X509Certificate c : chain) {
                pem.append(Pem.encode("CERTIFICATE", c.getEncoded()));
            }
        } catch (GeneralSecurityException e) {
            throw new IOException("证书编码不了", e);
        }
        Files.createDirectories(dir);
        Path chainTmp = dir.resolve(CHAIN + ".tmp");
        Path keyTmp = dir.resolve(KEY + ".tmp");
        // 两个都写好了再换上：换的中途出事，下次开服核对时发现私钥与证书对不上，重新申请
        Files.write(chainTmp, pem.toString().getBytes(StandardCharsets.US_ASCII));
        Files.write(keyTmp, Pem.encode("PRIVATE KEY", key.getEncoded()).getBytes(StandardCharsets.US_ASCII));
        replace(keyTmp, dir.resolve(KEY));
        replace(chainTmp, dir.resolve(CHAIN));
    }

    private static void replace(Path tmp, Path file) throws IOException {
        try {
            Files.move(tmp, file, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
        } catch (AtomicMoveNotSupportedException e) {
            Files.move(tmp, file, StandardCopyOption.REPLACE_EXISTING);
        }
    }

    private static boolean rename(Path file, String suffix) {
        if (!Files.exists(file)) {
            return true;
        }
        try {
            Files.move(file, file.resolveSibling(file.getFileName() + suffix));
            return true;
        } catch (IOException e) {
            return false;
        }
    }

    static PrivateKey privateKey(byte[] pemBytes) throws GeneralSecurityException {
        for (Pem.Block b : Pem.parse(Pem.text(pemBytes))) {
            if (b.label.equals("PRIVATE KEY")) {
                PKCS8EncodedKeySpec spec = new PKCS8EncodedKeySpec(b.der);
                for (String alg : new String[]{"EC", "RSA"}) {
                    try {
                        return KeyFactory.getInstance(alg).generatePrivate(spec);
                    } catch (GeneralSecurityException ignored) {
                        // 试下一种
                    }
                }
                throw new GeneralSecurityException("私钥既不是 EC 也不是 RSA");
            }
        }
        throw new GeneralSecurityException(KEY + " 里没有 -----BEGIN PRIVATE KEY-----");
    }
}
