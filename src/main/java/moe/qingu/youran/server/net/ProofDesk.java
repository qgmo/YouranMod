package moe.qingu.youran.server.net;

import com.mojang.authlib.GameProfile;
import moe.qingu.youran.api.network.ConfirmRequest;
import moe.qingu.youran.api.network.ConfirmResult;
import moe.qingu.youran.api.network.ConfirmText;
import moe.qingu.youran.api.network.DomainNames;
import moe.qingu.youran.api.network.Ipv6Address;
import moe.qingu.youran.api.network.NetworkInterface;
import moe.qingu.youran.api.network.identity.OperatorProof;
import moe.qingu.youran.api.network.identity.OperatorResult;
import moe.qingu.youran.api.network.location.LocationProof;
import moe.qingu.youran.api.network.location.LocationResult;
import moe.qingu.youran.server.net.identity.OperatorSigner;
import moe.qingu.youran.server.net.location.LocationSigner;
import moe.qingu.youran.server.net.service.captcha.SourceLimiter;

import java.util.Arrays;
import java.util.Locale;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;

/**
 * 定位模块、身份模块的签名台（服主 11-27，只在主线程上用；时间、确认框经 {@link Env}，单元测试换成假的）。
 * 两种证明同一个办法：地址取自网卡对象（{@link NetworkInterface#address()}，没插着签不了）；确认框问调用方给的玩家，同意才签；
 * 签名时网卡要还插着、地址没变。坐标（位置证明）、玩家（操作者证明）都由调用方给，网络不按地址认人、不按地址解码坐标。
 */
final class ProofDesk {
    interface Env {
        long nowMillis();

        /** 问这名玩家（{@code SystemConfirm.askPlayer} 的做法：每人同时一个、到时按拒绝、下线作废） */
        CompletableFuture<ConfirmResult> confirm(UUID player, ConfirmRequest request);
    }

    private final Env env;
    /** 每名玩家请证明的频率（两种合起来） */
    private final SourceLimiter limit = new SourceLimiter(0.5, 3, 4096);
    volatile LocationSigner locationSigner;
    volatile OperatorSigner operatorSigner;

    ProofDesk(Env env) {
        this.env = env;
    }

    /** 网站域名转成 A-label 小写；不合格为 null */
    static String site(String site) {
        String s = site == null ? null : DomainNames.tryToAscii(site.trim().toLowerCase(Locale.ROOT));
        return s != null && LocationProof.SITE.matcher(s).matches() ? s : null;
    }

    CompletableFuture<LocationResult> location(NetworkInterface nic, GameProfile player, int dimension, int x, int y, int z, String site, String nonce) {
        String s = site(site);
        if (s == null || nonce == null || !LocationProof.NONCE.matcher(nonce).matches()) {
            return done(LocationResult.of(LocationResult.Status.BAD_REQUEST));
        }
        LocationSigner signer = locationSigner;
        Ipv6Address address = nic == null ? null : nic.address();
        if (signer == null || address == null) {
            return done(LocationResult.of(LocationResult.Status.UNAVAILABLE));
        }
        if (player == null || player.getId() == null) {
            return done(LocationResult.of(LocationResult.Status.NO_OPERATOR));
        }
        if (!limit.tryAcquire(player.getId().toString(), env.nowMillis())) {
            return done(LocationResult.of(LocationResult.Status.BUSY));
        }
        // 固定文字用翻译键，按玩家客户端的语言显示（服务端写好的中文是客户端没有这个键时的退路）
        ConfirmRequest req = new ConfirmRequest(ConfirmText.translate("youranmod.confirm.location.title", "获取这台电脑的位置"), s, Arrays.asList(
                ConfirmText.translate("youranmod.confirm.location.position",
                        "这台电脑的位置：维度 " + dimension + "，坐标 " + x + ", " + y + ", " + z, dimension, x, y, z),
                ConfirmText.translate("youranmod.confirm.location.address", "这台电脑的游戏内地址")),
                ConfirmRequest.DEFAULT_TIMEOUT_SECONDS, ConfirmRequest.Purpose.SHARE);
        return env.confirm(player.getId(), req).thenApply(r -> {
            LocationResult.Status st = locationStatus(r, player.getId());
            if (st != LocationResult.Status.OK) {
                return LocationResult.of(st);
            }
            if (!address.equals(nic.address())) {
                // 等确认的时候网卡拔掉了（或换了地方）
                return LocationResult.of(LocationResult.Status.UNAVAILABLE);
            }
            try {
                LocationProof proof = signer.sign(dimension, x, y, z, address, env.nowMillis() / 1000, s, nonce);
                NetworkServer.LOG.info("游戏内网络：{} 同意把网卡 {} 的位置（维度 {} {} {} {}）给 {}", r.playerName, address, dimension, x, y, z, s);
                return new LocationResult(LocationResult.Status.OK, proof.text());
            } catch (Exception e) {
                NetworkServer.LOG.error("游戏内网络：位置签名失败", e);
                return LocationResult.of(LocationResult.Status.UNAVAILABLE);
            }
        });
    }

    CompletableFuture<OperatorResult> operator(NetworkInterface nic, GameProfile player, String site, String nonce) {
        String s = site(site);
        if (s == null || nonce == null || !LocationProof.NONCE.matcher(nonce).matches()) {
            return done(OperatorResult.of(OperatorResult.Status.BAD_REQUEST));
        }
        OperatorSigner signer = operatorSigner;
        Ipv6Address address = nic == null ? null : nic.address();
        if (signer == null || address == null) {
            return done(OperatorResult.of(OperatorResult.Status.UNAVAILABLE));
        }
        if (player == null || player.getId() == null || player.getName() == null || !OperatorProof.NAME.matcher(player.getName()).matches()) {
            return done(OperatorResult.of(OperatorResult.Status.NO_OPERATOR));
        }
        if (!limit.tryAcquire(player.getId().toString(), env.nowMillis())) {
            return done(OperatorResult.of(OperatorResult.Status.BUSY));
        }
        ConfirmRequest req = new ConfirmRequest(ConfirmText.translate("youranmod.confirm.operator.title", "确认是你在操作这台电脑"), s, Arrays.asList(
                ConfirmText.translate("youranmod.confirm.operator.player", "你的游戏角色：" + player.getName(), player.getName()),
                ConfirmText.translate("youranmod.confirm.operator.address", "这台电脑的游戏内地址")),
                ConfirmRequest.DEFAULT_TIMEOUT_SECONDS, ConfirmRequest.Purpose.SHARE);
        return env.confirm(player.getId(), req).thenApply(r -> {
            LocationResult.Status st = locationStatus(r, player.getId());
            if (st != LocationResult.Status.OK) {
                return OperatorResult.of(OperatorResult.Status.byCode(st.code));
            }
            if (!address.equals(nic.address())) {
                return OperatorResult.of(OperatorResult.Status.UNAVAILABLE);
            }
            try {
                OperatorProof proof = signer.sign(player.getId(), player.getName(), address, env.nowMillis() / 1000, s, nonce);
                NetworkServer.LOG.info("游戏内网络：{} 同意向 {} 证明是他在操作网卡 {}", player.getName(), s, address);
                return new OperatorResult(OperatorResult.Status.OK, proof.text());
            } catch (Exception e) {
                NetworkServer.LOG.error("游戏内网络：操作者证明签名失败", e);
                return OperatorResult.of(OperatorResult.Status.UNAVAILABLE);
            }
        });
    }

    /** 确认框的结果 → 两种证明共用的代号（同意且回答的就是被问的玩家时为 OK） */
    private static LocationResult.Status locationStatus(ConfirmResult r, UUID asked) {
        if (r == null) {
            return LocationResult.Status.UNAVAILABLE;
        }
        switch (r.outcome) {
            case APPROVED:
                return asked.equals(r.player) ? LocationResult.Status.OK : LocationResult.Status.OPERATOR_CHANGED;
            case DENIED:
                return LocationResult.Status.DENIED;
            case TIMEOUT:
                return LocationResult.Status.TIMEOUT;
            case OPERATOR_CHANGED:
                return LocationResult.Status.OPERATOR_CHANGED;
            case BUSY:
                return LocationResult.Status.BUSY;
            case NO_OPERATOR:
                return LocationResult.Status.NO_OPERATOR;
            default:
                return LocationResult.Status.UNAVAILABLE;
        }
    }

    private static <T> CompletableFuture<T> done(T v) {
        return CompletableFuture.completedFuture(v);
    }

}
