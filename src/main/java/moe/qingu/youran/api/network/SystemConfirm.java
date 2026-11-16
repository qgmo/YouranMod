package moe.qingu.youran.api.network;

import moe.qingu.youran.network.NetworkBackend;
import moe.qingu.youran.network.NetworkHooks;

import java.util.UUID;
import java.util.concurrent.CompletableFuture;

/**
 * 系统级确认框（服主 11-15）：向一台设备（或一个玩家）的当前操作者请求确认，例如 {@code id.yr} 的“用游戏角色登录”、
 * 定位模块的“是否允许某网站获取这台电脑的位置”、{@code pay.yr} 的付款。
 * <p>
 * 框画在操作者客户端的<b>电脑屏幕之外</b>（Minecraft 界面上的一层，盖住电脑界面、拦下鼠标键盘），电脑里的程序（浏览器里的网页、
 * SCP 电脑里玩家写的程序）看不到也点不到；按钮出现后过一小会儿才能点，防止正在点网页的人误点。
 * <ul>
 * <li>每个玩家同一时间最多一个：已经有一个开着时，新的马上以 {@link ConfirmResult.Outcome#BUSY} 结束；</li>
 * <li>到时限（{@link ConfirmRequest#timeoutSeconds}，默认 60 秒）没回应按拒绝（{@link ConfirmResult.Outcome#TIMEOUT}）；</li>
 * <li>还没回应时操作者离开电脑（关界面、走远）、下线、换了人，或设备拆掉、断电（不再有操作者），作废（{@link ConfirmResult.Outcome#OPERATOR_CHANGED}）；</li>
 * <li>服务端只收被问的那个玩家发来的回应，题号要对得上；别人发的、过时的、伪造的回应丢掉并记日志。</li>
 * </ul>
 * 只在服务端有用，任意线程都可以调用。返回的 future 在服务端主线程上完成；网络没在运行时马上以 {@link ConfirmResult.Outcome#UNAVAILABLE} 完成。
 * 调用方可以 {@code cancel} 返回的 future 撤回这个框（还开着时客户端上的框关掉，不再占着那个玩家；例如 {@code id.yr} 的“重新弹出”先撤回上一个）。
 * 回应只说明“操作者同意了”，要不要再查别的（例如这时设备还在不在）由调用方决定。
 */
public final class SystemConfirm {
    private SystemConfirm() {
    }

    /**
     * 向这个来源地址的当前操作者请求确认：是设备地址时问现在操作它的玩家（见 {@link YouranNetworkApi#identify}），
     * 是玩家临时地址时问那个玩家。认不出、没人操作时以 {@link ConfirmResult.Outcome#NO_OPERATOR} 结束。
     */
    public static CompletableFuture<ConfirmResult> ask(Ipv6Address source, ConfirmRequest request) {
        NetworkBackend b = NetworkHooks.backend();
        if (b == null || source == null || request == null) {
            return CompletableFuture.completedFuture(new ConfirmResult(ConfirmResult.Outcome.UNAVAILABLE, null, null));
        }
        return b.confirm(source, null, request);
    }

    /** 向这台设备的当前操作者请求确认 */
    public static CompletableFuture<ConfirmResult> ask(DeviceHandle device, ConfirmRequest request) {
        return ask(device == null ? null : device.address(), request);
    }

    /** 直接问一个在线玩家（不经设备，例如提现时问本人）；他下线时作废 */
    public static CompletableFuture<ConfirmResult> askPlayer(UUID player, ConfirmRequest request) {
        NetworkBackend b = NetworkHooks.backend();
        if (b == null || player == null || request == null) {
            return CompletableFuture.completedFuture(new ConfirmResult(ConfirmResult.Outcome.UNAVAILABLE, null, null));
        }
        return b.confirm(null, player, request);
    }
}
