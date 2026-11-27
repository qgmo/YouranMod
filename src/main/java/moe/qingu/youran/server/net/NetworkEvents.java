package moe.qingu.youran.server.net;

import net.minecraftforge.fml.common.eventhandler.SubscribeEvent;
import net.minecraftforge.fml.common.gameevent.PlayerEvent;

/**
 * 主线程上的事件：玩家上线、下线 → 分、收回临时地址，下线时断开他接着的网卡；每 tick → 系统确认框。
 * 网络不跟区块加载（服主 11-27）：方块加载时插网卡、卸载时拔由模组自己做。
 */
final class NetworkEvents {
    private final java.util.function.Consumer<net.minecraft.entity.player.EntityPlayerMP> onLogin;
    private final java.util.function.Consumer<java.util.UUID> onLogout;
    /** 每 tick（主线程，tick 末尾）：系统确认框的超时与作废 */
    private final Runnable onTick;

    NetworkEvents(java.util.function.Consumer<net.minecraft.entity.player.EntityPlayerMP> onLogin, java.util.function.Consumer<java.util.UUID> onLogout,
                  Runnable onTick) {
        this.onLogin = onLogin;
        this.onLogout = onLogout;
        this.onTick = onTick;
    }

    @SubscribeEvent
    public void onLogin(PlayerEvent.PlayerLoggedInEvent event) {
        if (event.player instanceof net.minecraft.entity.player.EntityPlayerMP) {
            onLogin.accept((net.minecraft.entity.player.EntityPlayerMP) event.player);
        }
    }

    @SubscribeEvent
    public void onServerTick(net.minecraftforge.fml.common.gameevent.TickEvent.ServerTickEvent event) {
        if (event.phase == net.minecraftforge.fml.common.gameevent.TickEvent.Phase.END) {
            onTick.run();
        }
    }

    @SubscribeEvent
    public void onLogout(PlayerEvent.PlayerLoggedOutEvent event) {
        onLogout.accept(event.player.getUniqueID());
    }
}
