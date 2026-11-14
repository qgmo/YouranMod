package moe.qingu.youran.net;

import io.netty.buffer.ByteBuf;
import io.netty.buffer.Unpooled;
import moe.qingu.youran.network.NetworkBackend;
import moe.qingu.youran.network.NetworkHooks;
import net.minecraft.entity.player.EntityPlayerMP;
import net.minecraft.network.NetHandlerPlayServer;
import net.minecraft.network.NetworkManager;
import net.minecraft.network.PacketBuffer;
import net.minecraft.network.play.client.CPacketCustomPayload;
import net.minecraft.network.play.server.SPacketCustomPayload;
import net.minecraftforge.fml.common.eventhandler.SubscribeEvent;
import net.minecraftforge.fml.common.network.FMLNetworkEvent;
import net.minecraftforge.fml.common.network.NetworkRegistry;

/**
 * 网卡频道（格式见 {@link NicMessages}）。收包在 Netty 线程上（FML 的事件频道就在那里触发），直接交给网络线程，不经主线程、不碰世界。
 * 发包直接交给连接的 {@link NetworkManager}（它本身可以从任意线程调用）。
 */
public final class NicChannel {
    private NicChannel() {
    }

    /** 预初始化时登记（两端都要登记，频道才对得上） */
    public static void register() {
        NetworkRegistry.INSTANCE.newEventDrivenChannel(NicMessages.CHANNEL).register(new NicChannel.Listener());
    }

    /** 服务端 → 客户端（任意线程） */
    public static void sendToClient(NetworkManager connection, byte[] message) {
        if (connection != null && connection.isChannelOpen()) {
            connection.sendPacket(new SPacketCustomPayload(NicMessages.CHANNEL, new PacketBuffer(Unpooled.wrappedBuffer(message))));
        }
    }

    /** 客户端 → 服务端（任意线程） */
    public static void sendToServer(NetworkManager connection, byte[] message) {
        if (connection != null && connection.isChannelOpen()) {
            connection.sendPacket(new CPacketCustomPayload(NicMessages.CHANNEL, new PacketBuffer(Unpooled.wrappedBuffer(message))));
        }
    }

    static byte[] read(ByteBuf payload) {
        int n = payload.readableBytes();
        if (n < 1 || n > NicMessages.MAX_MESSAGE) {
            return null;
        }
        byte[] b = new byte[n];
        payload.getBytes(payload.readerIndex(), b);
        return b;
    }

    private static void release(ByteBuf payload) {
        if (payload.refCnt() > 0) {
            payload.release();
        }
    }

    public static final class Listener {
        /**
         * 服务端收到客户端的包（Netty 线程）。FML 的事件频道不替我们释放载荷（SimpleNetworkWrapper 会），
         * 复制出来以后自己释放，否则 Netty 的池化缓冲会泄漏。
         */
        @SubscribeEvent
        public void onServerPacket(FMLNetworkEvent.ServerCustomPacketEvent event) {
            ByteBuf payload = event.getPacket().payload();
            try {
                if (!(event.getHandler() instanceof NetHandlerPlayServer)) {
                    return;
                }
                EntityPlayerMP player = ((NetHandlerPlayServer) event.getHandler()).player;
                NetworkBackend backend = NetworkHooks.backend();
                if (backend == null || player == null) {
                    return;
                }
                int n = payload.readableBytes();
                byte[] b = n >= 1 && n <= NicMessages.MAX_MESSAGE ? read(payload) : null;
                // 太长、太短、不是 PACKET 的也交给网络线程（null），由它记日志
                backend.onClientPacket(player, b != null && b[0] == NicMessages.PACKET ? java.util.Arrays.copyOfRange(b, 1, b.length) : null, n);
            } finally {
                release(payload);
            }
        }

        /** 客户端收到服务器的包（Netty 线程）；同样自己释放载荷 */
        @SubscribeEvent
        public void onClientPacket(FMLNetworkEvent.ClientCustomPacketEvent event) {
            ByteBuf payload = event.getPacket().payload();
            try {
                byte[] b = read(payload);
                if (b != null) {
                    moe.qingu.youran.client.network.ClientNetwork.onServerMessage(b);
                }
            } finally {
                release(payload);
            }
        }
    }
}
