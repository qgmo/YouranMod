package moe.qingu.youran.api.network;

import moe.qingu.youran.network.NetworkBackend;
import moe.qingu.youran.network.NetworkHooks;

import java.util.UUID;
import java.util.concurrent.CompletableFuture;

/**
 * 系统级确认框（服主 11-15）：向一个在线玩家请求确认，例如 {@code id.yr} 的 OAuth 同意、{@code pay.yr} 的付款。
 * 问谁由调用方给：网络不按来源地址认人（服主 11-27）。要问“现在坐在这台电脑前的人”时，由持有网卡对象的电脑模组给出他
 * （定位模块、身份模块的签名前也是这样问，见 {@code LocationProofs}、{@code OperatorProofs}）。
 * <p>
 * 框画在操作者客户端的<b>电脑屏幕之外</b>（Minecraft 界面上的一层，盖住电脑界面、拦下鼠标键盘），电脑里的程序（浏览器里的网页、
 * SCP 电脑里玩家写的程序）看不到也点不到；按钮出现后过一小会儿才能点，防止正在点网页的人误点。
 * <ul>
 * <li>每个玩家同一时间最多一个：已经有一个开着时，新的马上以 {@link ConfirmResult.Outcome#BUSY} 结束；</li>
 * <li>到时限（{@link ConfirmRequest#timeoutSeconds}，默认 60 秒）没回应按拒绝（{@link ConfirmResult.Outcome#TIMEOUT}）；</li>
 * <li>还没回应时被问的玩家下线，作废（{@link ConfirmResult.Outcome#OPERATOR_CHANGED}）；</li>
 * <li>服务端只收被问的那个玩家发来的回应，题号要对得上；别人发的、过时的、伪造的回应丢掉并记日志。</li>
 * </ul>
 * 只在服务端有用，任意线程都可以调用。返回的 future 在服务端主线程上完成；网络没在运行时马上以 {@link ConfirmResult.Outcome#UNAVAILABLE} 完成。
 * 调用方可以 {@code cancel} 返回的 future 撤回这个框（还开着时客户端上的框关掉，不再占着那个玩家；例如 {@code id.yr} 的“重新弹出”先撤回上一个）。
 * 回应只说明“操作者同意了”，要不要再查别的（例如这时设备还在不在）由调用方决定。
 */
public final class SystemConfirm {
    private SystemConfirm() {
    }

    /** 问一个在线玩家；他下线时作废。不在线时以 {@link ConfirmResult.Outcome#NO_OPERATOR} 结束 */
    public static CompletableFuture<ConfirmResult> askPlayer(UUID player, ConfirmRequest request) {
        NetworkBackend b = NetworkHooks.backend();
        if (b == null || player == null || request == null) {
            return CompletableFuture.completedFuture(new ConfirmResult(ConfirmResult.Outcome.UNAVAILABLE, null, null));
        }
        return b.confirm(player, request);
    }
}
