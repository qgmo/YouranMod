package moe.qingu.youran.api.network.location;

import moe.qingu.youran.api.network.DeviceCapability;
import moe.qingu.youran.api.network.DeviceHandle;
import moe.qingu.youran.api.network.Ipv6Address;
import moe.qingu.youran.network.NetworkBackend;
import moe.qingu.youran.network.NetworkHooks;

import java.security.PublicKey;
import java.util.concurrent.CompletableFuture;

/**
 * 定位模块（位置外设，服主 11-15）的服务端入口：给“这台设备、这个网站、这个随机数”签位置。
 * <p>
 * 私钥只在服务端（ECDSA P-256，首次开服时生成，存在服务器的 {@code config/youranmod-location-key.pem}，不进仓库）；
 * 坐标由服务端按设备表自己填（设备登记在哪一格就是哪一格），不收客户端报的值。签之前先用 {@code SystemConfirm} 问设备的当前操作者
 * “是否允许 某网站 获取这台电脑的位置”，同意才签。设备要声明了 {@link DeviceCapability#LOCATION}（电脑里装了定位模块）。
 * <p>
 * 公钥公开在 {@value #KEY_URL}（{@code https://} 也可以，配了中间 CA 时），PEM 格式；{@code http://location.yr/verify} 帮不能算 ECDSA 的程序核对。
 * 证明的格式与核对办法见 {@link LocationProof}、{@link LocationVerifier}。
 * <p>
 * 家具电脑这类协议栈在客户端的浏览器用 {@link ClientLocation#request}（客户端发请求，服务端按这个玩家现在操作的设备签）。
 */
public final class LocationProofs {
    /** 公钥的网址 */
    public static final String KEY_URL = "http://location.yr/key";
    /** 位置证明在游戏内网络上的域名 */
    public static final String DOMAIN = "location.yr";

    private LocationProofs() {
    }

    /**
     * 服务端（任意线程）：给设备 {@code device} 签位置，签给网站 {@code site}（域名）、随机数 {@code nonce}。先问操作者。
     * future 在服务端主线程上完成。
     */
    public static CompletableFuture<LocationResult> sign(Ipv6Address device, String site, String nonce) {
        NetworkBackend b = NetworkHooks.backend();
        if (b == null || device == null) {
            return CompletableFuture.completedFuture(LocationResult.of(LocationResult.Status.UNAVAILABLE));
        }
        return b.signLocation(device, site, nonce);
    }

    public static CompletableFuture<LocationResult> sign(DeviceHandle device, String site, String nonce) {
        return sign(device == null ? null : device.address(), site, nonce);
    }

    /** 服务端：签名公钥；网络没在运行、没有密钥时为 null */
    public static PublicKey publicKey() {
        NetworkBackend b = NetworkHooks.backend();
        return b == null ? null : b.locationKey();
    }
}
