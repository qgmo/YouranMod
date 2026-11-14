package moe.qingu.youran.client.network;

import moe.qingu.youran.YouranMod;
import moe.qingu.youran.api.network.ClientNic;
import moe.qingu.youran.api.network.Ipv6Address;
import moe.qingu.youran.api.network.packet.Ipv6Packet;
import moe.qingu.youran.api.network.stack.DnsResolver;
import moe.qingu.youran.api.network.stack.ExecutorEventLoop;
import moe.qingu.youran.api.network.stack.NetStack;
import moe.qingu.youran.net.NicChannel;
import moe.qingu.youran.net.NicMessages;
import moe.qingu.youran.network.ClientNicState;
import net.minecraft.network.NetworkManager;
import net.minecraftforge.fml.common.Mod;
import net.minecraftforge.fml.common.eventhandler.SubscribeEvent;
import net.minecraftforge.fml.common.network.FMLNetworkEvent;
import net.minecraftforge.fml.relauncher.Side;

import java.security.SecureRandom;
import java.util.Arrays;

/**
 * 客户端的网卡（{@link ClientNic} 的实现）：自己的网络线程“YouranMod 网络（客户端）”，上面跑接着的设备的协议栈。
 * 服务器发来的网卡消息在 Netty 线程上收到，直接交给这个线程；协议栈发出的包直接交给到服务器的连接（不经主线程）。
 * 断开服务器时协议栈关掉、线程停下。
 */
@Mod.EventBusSubscriber(value = Side.CLIENT, modid = YouranMod.MODID)
public final class ClientNetwork {
    static final String THREAD_NAME = "YouranMod 网络（客户端）";
    private static volatile NetworkManager connection;
    private static ExecutorEventLoop loop;
    /** 只在 loop 线程上改 */
    private static SessionImpl session;

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
        synchronized (ClientNetwork.class) {
            l = loop;
            loop = null;
        }
        if (l != null) {
            l.execute(ClientNetwork::detachInLoop);
            if (l.shutdown(2000)) {
                YouranMod.LOG.info("已断开服务器，客户端网络线程已结束");
            } else {
                YouranMod.LOG.warn("客户端网络线程没能在时限内停下");
            }
        }
    }

    private static synchronized ExecutorEventLoop loop() {
        if (loop == null || loop.isShutdown()) {
            loop = new ExecutorEventLoop(THREAD_NAME);
        }
        return loop;
    }

    /** Netty 线程上：服务器发来的网卡消息 */
    public static void onServerMessage(byte[] b) {
        switch (b[0]) {
            case NicMessages.PACKET: {
                if (b.length < 1 + Ipv6Packet.HEADER) {
                    return;
                }
                byte[] raw = Arrays.copyOfRange(b, 1, b.length);
                ExecutorEventLoop l = currentLoop();
                if (l != null) {
                    l.execute(() -> {
                        if (session != null) {
                            session.stack.receive(raw);
                        }
                    });
                }
                break;
            }
            case NicMessages.ATTACH: {
                if (b.length < 53) {
                    return;
                }
                int gen = NicMessages.getInt(b, 1);
                Ipv6Address address = Ipv6Address.read(b, 5);
                Ipv6Address resolver = Ipv6Address.read(b, 21);
                Ipv6Address ntp = Ipv6Address.read(b, 37);
                loop().execute(() -> attachInLoop(gen, address, resolver, ntp));
                break;
            }
            case NicMessages.DETACH: {
                if (b.length < 5) {
                    return;
                }
                int gen = NicMessages.getInt(b, 1);
                ExecutorEventLoop l = currentLoop();
                if (l != null) {
                    l.execute(() -> {
                        if (session != null && session.generation == gen) {
                            detachInLoop();
                        }
                    });
                }
                break;
            }
            default:
                break;
        }
    }

    private static synchronized ExecutorEventLoop currentLoop() {
        return loop;
    }

    private static void attachInLoop(int gen, Ipv6Address address, Ipv6Address resolver, Ipv6Address ntp) {
        detachInLoop();
        ExecutorEventLoop l = currentLoop();
        if (l == null) {
            return;
        }
        NetStack stack = new NetStack(l, ClientNetwork::sendToServer, new SecureRandom());
        stack.addAddress(address);
        session = new SessionImpl(gen, address, resolver, ntp, stack);
        YouranMod.LOG.info("游戏内网络：网卡接上了，设备地址 {}，递归解析器 {}", address, resolver);
        ClientNicState.attached(session);
    }

    private static void detachInLoop() {
        SessionImpl s = session;
        if (s == null) {
            return;
        }
        session = null;
        s.closed = true;
        s.stack.close();
        YouranMod.LOG.info("游戏内网络：网卡断开了（设备地址 {}）", s.address);
        ClientNicState.detached(s);
    }

    private static void sendToServer(byte[] packet) {
        NicChannel.sendToServer(connection, NicMessages.packet(packet));
    }

    private static final class SessionImpl implements ClientNic.Session {
        final int generation;
        final Ipv6Address address;
        final Ipv6Address resolver;
        final Ipv6Address ntp;
        final NetStack stack;
        final DnsResolver dns;
        volatile boolean closed;

        SessionImpl(int generation, Ipv6Address address, Ipv6Address resolver, Ipv6Address ntp, NetStack stack) {
            this.generation = generation;
            this.address = address;
            this.resolver = resolver;
            this.ntp = ntp;
            this.stack = stack;
            this.dns = new DnsResolver(stack, resolver);
        }

        @Override
        public Ipv6Address address() {
            return address;
        }

        @Override
        public Ipv6Address resolverAddress() {
            return resolver;
        }

        @Override
        public Ipv6Address ntpAddress() {
            return ntp;
        }

        @Override
        public NetStack stack() {
            return stack;
        }

        @Override
        public DnsResolver dns() {
            return dns;
        }

        @Override
        public boolean isClosed() {
            return closed;
        }
    }
}
