package moe.qingu.youran.server.net.identity;

import moe.qingu.youran.api.network.Ipv6Address;
import moe.qingu.youran.api.network.identity.OperatorProof;
import moe.qingu.youran.api.network.location.LocationProof;
import moe.qingu.youran.server.net.location.LocationSigner;

import java.io.IOException;
import java.nio.file.Path;
import java.security.GeneralSecurityException;
import java.security.KeyPair;
import java.security.PrivateKey;
import java.security.PublicKey;
import java.util.UUID;

/**
 * 身份模块（操作者证明）的签名密钥（ECDSA P-256）：与定位模块的密钥分开（服主 11-27，各签各的，网站按用途取公钥），
 * 存法相同（{@link LocationSigner#loadOrCreatePair}）：第一次开服时生成，以后照读；读不了时不覆盖、不签名。
 */
public final class OperatorSigner {
    static final String COMMENT = "# YouranMod 身份模块（操作者证明）的签名密钥（ECDSA P-256）。私钥不要外传；换掉这个文件等于换密钥，网站要重新取公钥。\n";

    private final PrivateKey privateKey;
    private final PublicKey publicKey;
    private final String keyId;

    public OperatorSigner(KeyPair pair) {
        this.privateKey = pair.getPrivate();
        this.publicKey = pair.getPublic();
        this.keyId = LocationProof.keyId(publicKey);
    }

    /** 结果：读到的或新生成的密钥，以及是不是这次新生成的 */
    public static final class Loaded {
        public final OperatorSigner signer;
        public final boolean created;

        Loaded(OperatorSigner signer, boolean created) {
            this.signer = signer;
            this.created = created;
        }
    }

    /** 读密钥文件；文件不存在时生成并写进去。文件存在但读不了时抛出（不覆盖） */
    public static Loaded loadOrCreate(Path file) throws IOException, GeneralSecurityException {
        boolean[] created = new boolean[1];
        KeyPair pair = LocationSigner.loadOrCreatePair(file, COMMENT, created);
        return new Loaded(new OperatorSigner(pair), created[0]);
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

    /** 签一份证明（玩家、地址由调用方给） */
    public OperatorProof sign(UUID player, String name, Ipv6Address address, long timeSeconds, String site, String nonce) throws GeneralSecurityException {
        return new OperatorProof(player, name, address, timeSeconds, site, nonce, keyId).sign(privateKey);
    }
}
