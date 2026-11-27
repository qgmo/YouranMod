package moe.qingu.youran.net;

import net.minecraftforge.fml.common.network.NetworkRegistry;
import net.minecraftforge.fml.common.network.simpleimpl.SimpleNetworkWrapper;
import net.minecraftforge.fml.relauncher.Side;

/**
 * 网络频道 {@code youranmod}（1.3.4 的 MCreator 频道 {@code youranmod:a} 已去掉）：同步配置；系统确认框（弹框、关框、回应）；
 * 定位模块的签名请求与结果；光刻机界面。编号只往后加。
 */
public final class YouranNetwork {
    public static final SimpleNetworkWrapper CHANNEL = NetworkRegistry.INSTANCE.newSimpleChannel("youranmod");

    private YouranNetwork() {
    }

    /** 预初始化时登记（两端都要登记，包的编号才对得上） */
    public static void register() {
        CHANNEL.registerMessage(SyncSettingsMessage.Handler.class, SyncSettingsMessage.class, 0, Side.CLIENT);
        CHANNEL.registerMessage(ConfirmMessages.ShowHandler.class, ConfirmMessages.Show.class, 1, Side.CLIENT);
        CHANNEL.registerMessage(ConfirmMessages.CloseHandler.class, ConfirmMessages.Close.class, 2, Side.CLIENT);
        CHANNEL.registerMessage(ConfirmMessages.AnswerHandler.class, ConfirmMessages.Answer.class, 3, Side.SERVER);
        // 4、5 原来是客户端请位置（服主 11-27 起由电脑模组自己请签名，见 api.network.location.LocationProofs），空着不用
        // 光刻机界面（2.1.0 光刻）
        CHANNEL.registerMessage(LithoMessages.SyncHandler.class, LithoMessages.Sync.class, 6, Side.CLIENT);
        CHANNEL.registerMessage(LithoMessages.ActionHandler.class, LithoMessages.Action.class, 7, Side.SERVER);
    }
}
