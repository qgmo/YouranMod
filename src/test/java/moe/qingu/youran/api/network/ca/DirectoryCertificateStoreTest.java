package moe.qingu.youran.api.network.ca;

import moe.qingu.youran.server.net.tls.TestPki;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.KeyPair;
import java.security.cert.X509Certificate;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.Date;
import java.util.List;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** {@link CertificateStore#directory}：存取、原子写、坏文件改名留着（假时钟：坏文件的后缀是固定的时间） */
class DirectoryCertificateStoreTest {
    static final long NOW = 1_800_000_000_000L;

    @TempDir
    Path tmp;
    Path dir;
    DirectoryCertificateStore store;
    TestPki pki;
    KeyPair kp;
    List<X509Certificate> chain;

    @BeforeEach
    void setUp() throws Exception {
        dir = tmp.resolve("youranmod").resolve("certs").resolve("shop");
        store = new DirectoryCertificateStore(dir, () -> NOW);
        pki = new TestPki(NOW, "测试根 CA", "yr");
        kp = TestPki.ec();
        chain = pki.intermediate().issue(Arrays.asList("shop.yr", "www.shop.yr"), kp.getPublic(), new Date(NOW));
    }

    List<String> files() throws IOException {
        List<String> out = new ArrayList<>();
        try (Stream<Path> s = Files.list(dir)) {
            s.forEach(p -> out.add(p.getFileName().toString()));
        }
        Collections.sort(out);
        return out;
    }

    @Test
    void nothingSavedYet() throws Exception {
        assertNull(store.load(), "目录都没有");
        Files.createDirectories(dir);
        assertNull(store.load(), "空目录");
    }

    @Test
    void savesAndLoadsBack() throws Exception {
        store.save(chain, kp.getPrivate());
        assertEquals(Arrays.asList("fullchain.pem", "privkey.pem"), files(), "目录自己建；没留临时文件");
        String pem = new String(Files.readAllBytes(dir.resolve("fullchain.pem")), StandardCharsets.US_ASCII);
        assertTrue(pem.startsWith("-----BEGIN CERTIFICATE-----"), pem);
        assertTrue(new String(Files.readAllBytes(dir.resolve("privkey.pem")), StandardCharsets.US_ASCII).startsWith("-----BEGIN PRIVATE KEY-----"));
        CertificateStore.Saved s = new DirectoryCertificateStore(dir, () -> NOW).load();
        assertNotNull(s);
        assertEquals(chain, s.chain, "整条链（叶子、中间 CA、根）按顺序");
        assertArrayEquals(kp.getPrivate().getEncoded(), s.key.getEncoded());
        assertTrue(CertificateClient.keyMatches(s.key, s.chain.get(0).getPublicKey()));
        // RSA 也行
        KeyPair rsa = TestPki.rsa();
        List<X509Certificate> rsaChain = pki.intermediate().issue(Collections.singletonList("shop.yr"), rsa.getPublic(), new Date(NOW));
        store.save(rsaChain, rsa.getPrivate());
        assertArrayEquals(rsa.getPrivate().getEncoded(), store.load().key.getEncoded());
    }

    @Test
    void saveOverwritesAndIgnoresLeftoverTempFiles() throws Exception {
        store.save(chain, kp.getPrivate());
        // 上次写到一半关服留下的临时文件
        Files.write(dir.resolve("fullchain.pem.tmp"), "半个".getBytes(StandardCharsets.UTF_8));
        Files.write(dir.resolve("privkey.pem.tmp"), "半个".getBytes(StandardCharsets.UTF_8));
        assertEquals(chain, store.load().chain, "临时文件不读");
        KeyPair kp2 = TestPki.ec();
        List<X509Certificate> chain2 = pki.intermediate().issue(Collections.singletonList("shop.yr"), kp2.getPublic(), new Date(NOW + 1000));
        store.save(chain2, kp2.getPrivate());
        assertEquals(Arrays.asList("fullchain.pem", "privkey.pem"), files());
        CertificateStore.Saved s = store.load();
        assertEquals(chain2, s.chain);
        assertArrayEquals(kp2.getPrivate().getEncoded(), s.key.getEncoded());
    }

    @Test
    void badFilesAreRenamedAndKept() throws Exception {
        Files.createDirectories(dir);
        Files.write(dir.resolve("fullchain.pem"), "不是证书".getBytes(StandardCharsets.UTF_8));
        Files.write(dir.resolve("privkey.pem"), "-----BEGIN PRIVATE KEY-----\nAAAA\n-----END PRIVATE KEY-----\n".getBytes(StandardCharsets.US_ASCII));
        assertNull(store.load());
        assertEquals(Arrays.asList("fullchain.pem.bad-" + NOW, "privkey.pem.bad-" + NOW), files());
        assertEquals("不是证书", new String(Files.readAllBytes(dir.resolve("fullchain.pem.bad-" + NOW)), StandardCharsets.UTF_8));
        // 之后照常存
        store.save(chain, kp.getPrivate());
        assertEquals(chain, store.load().chain);
    }

    @Test
    void goodChainWithBadKeyIsRenamedToo() throws Exception {
        store.save(chain, kp.getPrivate());
        Files.write(dir.resolve("privkey.pem"), "坏了".getBytes(StandardCharsets.UTF_8));
        assertNull(store.load());
        assertEquals(Arrays.asList("fullchain.pem.bad-" + NOW, "privkey.pem.bad-" + NOW), files());
    }

    @Test
    void onlyOneFileCountsAsNothing() throws Exception {
        store.save(chain, kp.getPrivate());
        Files.delete(dir.resolve("privkey.pem"));
        assertNull(store.load());
        assertEquals(Collections.singletonList("fullchain.pem"), files(), "不改名：下次存时补齐");
        store.save(chain, kp.getPrivate());
        assertNotNull(store.load());
    }

    @Test
    void saveFailsWithIOExceptionWhenTheDirectoryCannotBeMade() throws Exception {
        // 该是目录的地方是个文件
        Files.createDirectories(dir.getParent());
        Files.write(dir, "我是文件".getBytes(StandardCharsets.UTF_8));
        assertThrows(IOException.class, () -> store.save(chain, kp.getPrivate()));
        assertNull(store.load());
        assertThrows(IllegalArgumentException.class, () -> store.save(Collections.<X509Certificate>emptyList(), kp.getPrivate()));
    }
}
