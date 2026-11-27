package moe.qingu.youran.api.network.identity;

import com.mojang.authlib.GameProfile;
import moe.qingu.youran.api.network.NetworkInterface;
import moe.qingu.youran.network.NetworkBackend;
import moe.qingu.youran.network.NetworkHooks;

import java.security.PublicKey;
import java.util.concurrent.CompletableFuture;

/**
 * 身份模块（服主 11-27，一键登录的那一层）的服务端入口：电脑模组替它的操作者签“这个地址现在是这名玩家在操作”。
 * <p>
 * 和定位模块（{@code LocationProofs}）同一个办法：<b>地址取自网卡对象</b>（{@link NetworkInterface#address()}），只有持有网卡对象的模组代码能替这个地址签；
 * <b>玩家由调用方给</b>（家具电脑给 {@code ClientRelayNic#connectedPlayer()}，SCP 电脑给正在操作终端的玩家）。网络不按地址认人。
 * 签之前先给这名玩家弹系统确认框“某网站要确认现在操作这台电脑的是你”，同意才签。装没装身份模块由电脑模组自己判断，没装就不调用，
 * 告诉网站 {@link OperatorResult.Status#NO_IDENTITY}。
 * <p>
 * 私钥只在服务端（ECDSA P-256，首次开服时生成，存在服务器的 {@code config/youranmod-operator-key.pem}，与定位模块的密钥分开），
 * 公钥公开在 {@value #KEY_URL}；{@code http://operator.yr/verify} 帮不能算 ECDSA 的程序核对。格式与核对见 {@link OperatorProof}、{@link OperatorVerifier}。
 */
public final class OperatorProofs {
    /** 公钥的网址 */
    public static final String KEY_URL = "http://operator.yr/key";
    /** 操作者证明在游戏内网络上的域名（公钥与核对） */
    public static final String DOMAIN = "operator.yr";

    private OperatorProofs() {
    }

    /**
     * 服务端（任意线程）：给网卡 {@code nic} 现在的地址签操作者证明，玩家是 {@code player}，签给网站 {@code site}（域名）、随机数 {@code nonce}。
     * 先问 {@code player}。网卡没插着、网络没在运行时以 {@link OperatorResult.Status#UNAVAILABLE} 结束。future 在服务端主线程上完成。
     */
    public static CompletableFuture<OperatorResult> sign(NetworkInterface nic, GameProfile player, String site, String nonce) {
        NetworkBackend b = NetworkHooks.backend();
        if (b == null || nic == null || player == null || player.getId() == null || player.getName() == null) {
            return CompletableFuture.completedFuture(OperatorResult.of(b == null || nic == null ? OperatorResult.Status.UNAVAILABLE : OperatorResult.Status.NO_OPERATOR));
        }
        return b.signOperator(nic, player, site, nonce);
    }

    /** 服务端：签名公钥；网络没在运行、没有密钥时为 null */
    public static PublicKey publicKey() {
        NetworkBackend b = NetworkHooks.backend();
        return b == null ? null : b.operatorKey();
    }
}
