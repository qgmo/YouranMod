package moe.qingu.youran.server.net.location;

import moe.qingu.youran.api.network.Ipv6Address;
import moe.qingu.youran.api.network.location.LocationProof;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.attribute.PosixFilePermission;
import java.security.GeneralSecurityException;
import java.security.KeyFactory;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.PrivateKey;
import java.security.PublicKey;
import java.security.SecureRandom;
import java.security.spec.ECGenParameterSpec;
import java.security.spec.PKCS8EncodedKeySpec;
import java.util.Base64;
import java.util.EnumSet;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 定位模块的签名密钥（ECDSA P-256，Java 8 自带）。密钥文件里放两段 PEM：私钥（PKCS#8，不加密）与公钥（SubjectPublicKeyInfo）。
 * 第一次开服时生成（文件只给运行服务器的帐号读写），以后照读；读不了（坏了）时不覆盖、不签名，让服主处理。
 */
public final class LocationSigner {
    private static final Pattern PRIVATE = Pattern.compile("-----BEGIN PRIVATE KEY-----([A-Za-z0-9+/=\\s]+)-----END PRIVATE KEY-----");
    private static final Pattern PUBLIC = Pattern.compile("-----BEGIN PUBLIC KEY-----([A-Za-z0-9+/=\\s]+)-----END PUBLIC KEY-----");

    private final PrivateKey privateKey;
    private final PublicKey publicKey;
    private final String keyId;

    public LocationSigner(KeyPair pair) {
        this.privateKey = pair.getPrivate();
        this.publicKey = pair.getPublic();
        this.keyId = LocationProof.keyId(publicKey);
    }

    /** 新生成一对 */
    public static KeyPair generate(SecureRandom random) throws GeneralSecurityException {
        KeyPairGenerator g = KeyPairGenerator.getInstance("EC");
        g.initialize(new ECGenParameterSpec("secp256r1"), random);
        return g.generateKeyPair();
    }

    /** 结果：读到的或新生成的密钥，以及是不是这次新生成的 */
    public static final class Loaded {
        public final LocationSigner signer;
        public final boolean created;

        Loaded(LocationSigner signer, boolean created) {
            this.signer = signer;
            this.created = created;
        }
    }

    /** 读密钥文件；文件不存在时生成并写进去。文件存在但读不了时抛出（不覆盖） */
    public static Loaded loadOrCreate(Path file) throws IOException, GeneralSecurityException {
        boolean[] created = new boolean[1];
        KeyPair pair = loadOrCreatePair(file, COMMENT, created);
        return new Loaded(new LocationSigner(pair), created[0]);
    }

    static final String COMMENT = "# YouranMod 定位模块的签名密钥（ECDSA P-256）。私钥不要外传；换掉这个文件等于换密钥，网站要重新取公钥。\n";

    /**
     * 签名密钥文件的通用读写（定位模块、身份模块各一份，存法相同）：两段 PEM，前面一行注释 {@code comment}。
     * 文件不存在时生成并写进去（{@code created[0]} 置 true）；存在但读不了时抛出（不覆盖）
     */
    public static KeyPair loadOrCreatePair(Path file, String comment, boolean[] created) throws IOException, GeneralSecurityException {
        if (Files.exists(file)) {
            return read(new String(Files.readAllBytes(file), StandardCharsets.US_ASCII));
        }
        KeyPair pair = generate(new SecureRandom());
        Path dir = file.toAbsolutePath().getParent();
        if (dir != null) {
            Files.createDirectories(dir);
        }
        Path tmp = file.resolveSibling(file.getFileName() + ".tmp");
        Files.write(tmp, write(pair, comment).getBytes(StandardCharsets.US_ASCII));
        try {
            Files.setPosixFilePermissions(tmp, EnumSet.of(PosixFilePermission.OWNER_READ, PosixFilePermission.OWNER_WRITE));
        } catch (UnsupportedOperationException | IOException e) {
            // Windows 等没有 POSIX 权限的文件系统：照常写
        }
        Files.move(tmp, file, StandardCopyOption.ATOMIC_MOVE);
        if (created != null && created.length > 0) {
            created[0] = true;
        }
        return pair;
    }

    /** 两段 PEM */
    public static String write(KeyPair pair) {
        return write(pair, COMMENT);
    }

    static String write(KeyPair pair, String comment) {
        Base64.Encoder enc = Base64.getMimeEncoder(64, new byte[]{'\n'});
        return comment
                + "-----BEGIN PRIVATE KEY-----\n" + enc.encodeToString(pair.getPrivate().getEncoded()) + "\n-----END PRIVATE KEY-----\n"
                + LocationProof.publicKeyPem(pair.getPublic());
    }

    public static KeyPair read(String text) throws GeneralSecurityException {
        Matcher pr = PRIVATE.matcher(text);
        Matcher pu = PUBLIC.matcher(text);
        if (!pr.find() || !pu.find()) {
            throw new GeneralSecurityException("密钥文件里要有 PRIVATE KEY 与 PUBLIC KEY 两段 PEM");
        }
        PrivateKey priv = KeyFactory.getInstance("EC").generatePrivate(
                new PKCS8EncodedKeySpec(Base64.getMimeDecoder().decode(pr.group(1).trim())));
        PublicKey pub = LocationProof.readPublicKey(pu.group(0));
        // 两段要是一对：签一下、核一下
        LocationProof probe = new LocationProof(0, 0, 0, 0, Ipv6Address.UNSPECIFIED, 0, "check.yr", "selfcheck", LocationProof.keyId(pub)).sign(priv);
        if (!probe.signatureValid(pub)) {
            throw new GeneralSecurityException("密钥文件里的私钥与公钥不是一对");
        }
        return new KeyPair(pub, priv);
    }

    public PublicKey publicKey() {
        return publicKey;
    }

    public String keyId() {
        return keyId;
    }

    public String publicKeyPem() {
        return LocationProof.publicKeyPem(publicKey);
    }

    /** 签一份证明（坐标、地址由调用方按设备表给） */
    public LocationProof sign(int dimension, int x, int y, int z, Ipv6Address device, long timeSeconds, String site, String nonce)
            throws GeneralSecurityException {
        return new LocationProof(dimension, x, y, z, device, timeSeconds, site, nonce, keyId).sign(privateKey);
    }
}
