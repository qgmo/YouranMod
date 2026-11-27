package moe.qingu.youran.api.network.location;

import com.mojang.authlib.GameProfile;
import moe.qingu.youran.api.network.NetworkInterface;
import moe.qingu.youran.network.NetworkBackend;
import moe.qingu.youran.network.NetworkHooks;

import java.security.PublicKey;
import java.util.concurrent.CompletableFuture;

/**
 * 定位模块（位置外设，服主 11-15）的服务端入口：给“这张网卡、这个位置、这个网站、这个随机数”签名。
 * <p>
 * 服主 11-27：<b>地址取自网卡对象</b>（{@link NetworkInterface#address()}），只有持有网卡对象的模组代码能替这个地址签；
 * <b>坐标由电脑模组按自己方块的位置填</b>（网络不按地址解码坐标）；签之前给调用方指定的玩家（电脑现在的操作者）弹系统确认框
 * “是否允许 某网站 获取这台电脑的位置”，同意才签。装没装定位模块由电脑模组自己判断，没装就不调用，告诉网站 {@link LocationResult.Status#NO_LOCATOR}。
 * <p>
 * 私钥只在服务端（ECDSA P-256，首次开服时生成，存在服务器的 {@code config/youranmod-location-key.pem}，不进仓库）。
 * 公钥公开在 {@value #KEY_URL}（{@code https://} 也可以，配了中间 CA 时），PEM 格式；{@code http://location.yr/verify} 帮不能算 ECDSA 的程序核对。
 * 证明的格式与核对办法见 {@link LocationProof}、{@link LocationVerifier}（签名内容、公钥不变）。
 */
public final class LocationProofs {
    /** 公钥的网址 */
    public static final String KEY_URL = "http://location.yr/key";
    /** 位置证明在游戏内网络上的域名 */
    public static final String DOMAIN = "location.yr";

    private LocationProofs() {
    }

    /**
     * 服务端（任意线程）：给网卡 {@code nic} 现在的地址签位置（维度 ID 与方块坐标由调用方给），签给网站 {@code site}（域名）、随机数 {@code nonce}。
     * 先问 {@code player}。网卡没插着、网络没在运行时以 {@link LocationResult.Status#UNAVAILABLE} 结束。future 在服务端主线程上完成。
     */
    public static CompletableFuture<LocationResult> sign(NetworkInterface nic, GameProfile player, int dimension, int x, int y, int z, String site, String nonce) {
        NetworkBackend b = NetworkHooks.backend();
        if (b == null || nic == null) {
            return CompletableFuture.completedFuture(LocationResult.of(LocationResult.Status.UNAVAILABLE));
        }
        if (player == null || player.getId() == null) {
            return CompletableFuture.completedFuture(LocationResult.of(LocationResult.Status.NO_OPERATOR));
        }
        return b.signLocation(nic, player, dimension, x, y, z, site, nonce);
    }

    /** 服务端：签名公钥；网络没在运行、没有密钥时为 null */
    public static PublicKey publicKey() {
        NetworkBackend b = NetworkHooks.backend();
        return b == null ? null : b.locationKey();
    }
}
