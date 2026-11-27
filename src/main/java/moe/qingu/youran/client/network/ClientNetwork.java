package moe.qingu.youran.client.network;

import moe.qingu.youran.YouranMod;
import moe.qingu.youran.api.network.ClientHost;
import moe.qingu.youran.api.network.stack.ExecutorEventLoop;
import moe.qingu.youran.net.NicChannel;
import moe.qingu.youran.network.ClientHostState;
import net.minecraft.network.NetworkManager;
import net.minecraftforge.fml.common.Mod;
import net.minecraftforge.fml.common.eventhandler.SubscribeEvent;
import net.minecraftforge.fml.common.network.FMLNetworkEvent;
import net.minecraftforge.fml.relauncher.Side;

import java.security.SecureRandom;

/**
 * 客户端这台主机（{@link ClientHost} 的实现）：自己的网络线程“YouranMod 网络（客户端）”，上面跑唯一的协议栈（{@link ClientHostState}）。
 * 服务器发来的网卡消息在 Netty 线程上收到，直接交给这个线程；协议栈发出的包（带网口号）直接交给到服务器的连接（不经主线程）。
 * 断开服务器时网口都去掉、协议栈关掉、线程停下。
 */
@Mod.EventBusSubscriber(value = Side.CLIENT, modid = YouranMod.MODID)
public final class ClientNetwork {
    static final String THREAD_NAME = "YouranMod 网络（客户端）";
    private static volatile NetworkManager connection;
    private static ExecutorEventLoop loop;
    private static ClientHostState state;

    private ClientNetwork() {
    }

    /** 初始化时在客户端上调用：登记客户端测试命令 /ynetc */
    public static void init() {
        net.minecraftforge.client.ClientCommandHandler.instance.registerCommand(new ClientNetCommand());
    }

    @SubscribeEvent
    public static void onConnected(FMLNetworkEvent.ClientConnectedToServerEvent event) {
        connection = event.getManager();
    }

    @SubscribeEvent
    public static void onDisconnected(FMLNetworkEvent.ClientDisconnectionFromServerEvent event) {
        connection = null;
        ExecutorEventLoop l;
        ClientHostState s;
        synchronized (ClientNetwork.class) {
            l = loop;
            s = state;
            loop = null;
            state = null;
        }
        if (l != null) {
            l.execute(() -> {
                if (ClientHostState.current() == s) {
                    ClientHostState.install(null);
                }
                s.close();
            });
            if (l.shutdown(2000)) {
                YouranMod.LOG.info("已断开服务器，客户端网络线程已结束");
            } else {
                YouranMod.LOG.warn("客户端网络线程没能在时限内停下");
            }
        }
    }

    /** 这次连接的事件循环与主机状态（第一次收到网卡消息时建） */
    private static synchronized ExecutorEventLoop loop() {
        if (loop == null || loop.isShutdown()) {
            ExecutorEventLoop l = new ExecutorEventLoop(THREAD_NAME);
            ClientHostState s = new ClientHostState(l, new SecureRandom(), m -> NicChannel.sendToServer(connection, m));
            loop = l;
            state = s;
            l.execute(() -> ClientHostState.install(s));
        }
        return loop;
    }

    /** Netty 线程上：服务器发来的网卡消息 */
    public static void onServerMessage(byte[] b) {
        ExecutorEventLoop l = loop();
        ClientHostState s;
        synchronized (ClientNetwork.class) {
            s = state;
        }
        if (s != null) {
            l.execute(() -> s.onMessage(b));
        }
    }
}
