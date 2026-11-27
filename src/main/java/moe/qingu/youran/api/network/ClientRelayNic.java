package moe.qingu.youran.api.network;

import moe.qingu.youran.network.NetworkHooks;
import moe.qingu.youran.network.RelayEnd;
import net.minecraft.entity.player.EntityPlayerMP;

/**
 * YouranMod 提供的一种网卡：协议栈跑在玩家的客户端上（方案 A，家具电脑用的就是这种）。
 * 接着玩家时，发给网卡的包转给他的客户端，他客户端发出的包从这张网卡进网络；没接玩家时什么都收不了。
 * <p>
 * {@link #connect}、{@link #disconnect} 只在服务端主线程上调用。一个玩家同时只接一张这种网卡，接新的时旧的自动断开；
 * 玩家下线时也自动断开。
 */
public class ClientRelayNic extends NetworkInterface {
    private volatile EntityPlayerMP player;

    /** 交给中转那边（YouranMod 内部）的另一头：中转只能通过它改“接着谁”、把客户端的包送进来 */
    private final RelayEnd end = new RelayEnd() {
        @Override
        public void connected(EntityPlayerMP p) {
            player = p;
        }

        @Override
        public boolean fromClient(byte[] packet) {
            return send(packet);
        }
    };

    /** 接到这个玩家的客户端；服务器告诉客户端网卡地址与递归解析器地址。没插着时什么都不做 */
    public final void connect(EntityPlayerMP player) {
        if (isPlugged()) {
            NetworkHooks.relay().connect(end, address(), player);
        }
    }

    /** 断开 */
    public final void disconnect() {
        NetworkHooks.relay().disconnect(end);
    }

    /** 现在接着的玩家；没有为 null */
    public final EntityPlayerMP connectedPlayer() {
        return player;
    }

    @Override
    public boolean receive(byte[] packet) {
        EntityPlayerMP p = player;
        return p != null && NetworkHooks.relay().toClient(p, packet);
    }
}
