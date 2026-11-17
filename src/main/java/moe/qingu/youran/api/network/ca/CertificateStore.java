package moe.qingu.youran.api.network.ca;

import java.io.IOException;
import java.nio.file.Path;
import java.security.PrivateKey;
import java.security.cert.X509Certificate;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * 网站的证书与私钥存在哪：交给 {@link CertificateClient} 后，拿到证书（含续期）就{@link #save 存}，开服时先{@link #load 读}，
 * 读出来的还能用就直接用、不再申请（像现实里 certbot 存在 {@code /etc/letsencrypt}）。存在哪由调用方自己定；不给就不存，每次开服重新申请。
 * <p>
 * 读出来的东西能不能用（没过期、名字对、链到现在的中间 CA、私钥和证书对得上）由 {@link CertificateClient} 核对，存储只管存取。
 * 想存成一个目录里的 PEM 文件时用 {@link #directory}；也可以自己实现（存进数据库等）。
 * 两个方法都在网络线程上调用，要快（小文件读写可以）。
 */
public interface CertificateStore {
    /**
     * 读存着的证书与私钥。没存过、或存的东西读不了（文件坏了等，实现自己处理并记日志）时返回 null；
     * 抛出异常时 {@link CertificateClient} 记日志、当作没有。
     */
    Saved load() throws IOException;

    /**
     * 存下（覆盖上一次的）。{@code chain} 叶子在前，后面是中间 CA；{@code key} 是叶子公钥对应的私钥。
     * 存不下时抛出异常：{@link CertificateClient} 记日志、照用内存里的证书。
     */
    void save(List<X509Certificate> chain, PrivateKey key) throws IOException;

    /** 存着的证书链（叶子在前）与私钥 */
    final class Saved {
        public final List<X509Certificate> chain;
        public final PrivateKey key;

        public Saved(List<X509Certificate> chain, PrivateKey key) {
            this.chain = chain == null ? Collections.<X509Certificate>emptyList() : Collections.unmodifiableList(new ArrayList<>(chain));
            this.key = key;
        }
    }

    /**
     * 存在目录 {@code dir} 里：{@code fullchain.pem}（整条链，叶子在前）与 {@code privkey.pem}（不加密的 PKCS#8），
     * 和 certbot 的文件名一样。目录不存在时存的时候建。
     * <ul>
     * <li>写的时候先写成 {@code .tmp} 再改名换上，写到一半关服不会留下半个文件；</li>
     * <li>读不了的文件（坏了、不是 PEM）改名成 {@code <文件名>.bad-<时间>} 留着，记日志，当作没有；</li>
     * <li>只有其中一个文件时当作没有（下次存时补齐）。</li>
     * </ul>
     * 例：YouranMod 自己的网站存在世界存档的 {@code youranmod/certs/<网站>/}
     * （世界存档的位置见 {@code YouranNetworkApi.worldDirectory()}）。
     */
    static CertificateStore directory(Path dir) {
        return new DirectoryCertificateStore(dir, System::currentTimeMillis);
    }
}
