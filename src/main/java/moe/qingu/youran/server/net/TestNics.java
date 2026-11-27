package moe.qingu.youran.server.net;

import moe.qingu.youran.api.network.ClientRelayNic;
import moe.qingu.youran.api.network.Ipv6Address;
import moe.qingu.youran.api.network.NetworkInterface;
import moe.qingu.youran.api.network.YouranNetworkApi;
import moe.qingu.youran.api.network.stack.DnsResolver;
import moe.qingu.youran.api.network.stack.EventLoop;
import moe.qingu.youran.api.network.stack.NetStack;
import net.minecraft.entity.player.EntityPlayerMP;

import java.security.SecureRandom;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * 测试用的网卡（只给 {@code /ynet device} 用，不存盘，服主 11-27：“在一格上插一张测试网卡”）：
 * <ul>
 *   <li>服务端测试网卡（{@link Emulated}）：插在某一格，背后是网络线程上的一个协议栈（相当于一台服务端模拟的电脑），能 ping、查 DNS、取网页；
 *   可以设成“收不了”（{@link Emulated#refuse}），看路由器回“目标不可达”；</li>
 *   <li>接玩家客户端的测试网卡（{@link Relay}，一张 {@link ClientRelayNic}）：插在某一格、接到这个玩家的客户端，他的客户端多一个网口（/ynetc -i 用它）。</li>
 * </ul>
 */
final class TestNics {
    private final EventLoop loop;
    private final Ipv6Address resolver;
    private final Map<NicTable.PosKey, Emulated> emulated = new LinkedHashMap<>();
    private final Map<UUID, Relay> relays = new LinkedHashMap<>();

    /** 服务端测试网卡：收到的包交给协议栈，协议栈发的包从网卡发出 */
    static final class Emulated extends NetworkInterface {
        final NicTable.PosKey key;
        NetStack stack;
        DnsResolver dns;
        volatile boolean refuse;
        volatile long received;
        volatile long sent;
        volatile long sendRejected;

        Emulated(NicTable.PosKey key) {
            this.key = key;
        }

        @Override
        public boolean receive(byte[] packet) {
            if (refuse) {
                return false;
            }
            received++;
            stack.receive(packet);
            return true;
        }

        void out(byte[] packet) {
            if (send(packet)) {
                sent++;
            } else {
                sendRejected++;
            }
        }
    }

    /** 接玩家客户端的测试网卡 */
    static final class Relay extends ClientRelayNic {
        final NicTable.PosKey key;

        Relay(NicTable.PosKey key) {
            this.key = key;
        }
    }

    TestNics(EventLoop loop, Ipv6Address resolver) {
        this.loop = loop;
        this.resolver = resolver;
    }

    /** 主线程：在这一格插一张服务端测试网卡（已有的测试网卡先拔掉）；没有地址、这一格插着别的网卡时返回 null */
    Emulated add(int dimension, int x, int y, int z) {
        remove(dimension, x, y, z);
        NicTable.PosKey key = new NicTable.PosKey(dimension, x, y, z);
        Emulated e = new Emulated(key);
        e.stack = new NetStack(loop, e::out, new SecureRandom());
        Ipv6Address a = YouranNetworkApi.plug(e, dimension, x, y, z);
        if (a == null) {
            e.stack.close();
            return null;
        }
        e.stack.addAddress(a);
        e.dns = new DnsResolver(e.stack, resolver);
        emulated.put(key, e);
        return e;
    }

    Emulated get(int dimension, int x, int y, int z) {
        Emulated e = emulated.get(new NicTable.PosKey(dimension, x, y, z));
        if (e != null && !e.isPlugged()) {
            e.stack.close();
            emulated.remove(e.key);
            return null;
        }
        return e;
    }

    /** 拔掉这一格的测试网卡（服务端的或接玩家的）；没有返回 false */
    boolean remove(int dimension, int x, int y, int z) {
        NicTable.PosKey key = new NicTable.PosKey(dimension, x, y, z);
        Emulated e = emulated.remove(key);
        if (e != null) {
            YouranNetworkApi.unplug(e);
            e.stack.close();
            return true;
        }
        for (Map.Entry<UUID, Relay> r : new ArrayList<>(relays.entrySet())) {
            if (r.getValue().key.equals(key)) {
                unbindPlayer(r.getKey());
                return true;
            }
        }
        return false;
    }

    List<NetworkInterface> list() {
        List<NetworkInterface> out = new ArrayList<>();
        for (Emulated e : emulated.values()) {
            if (e.isPlugged()) {
                out.add(e);
            }
        }
        out.addAll(relays.values());
        return out;
    }

    /** 主线程：在这一格插一张接玩家客户端的测试网卡，接上他（他原来接着的测试网卡先拔掉）；插不上时返回 null */
    Relay bindPlayer(EntityPlayerMP player, int dimension, int x, int y, int z) {
        unbindPlayer(player.getUniqueID());
        Relay r = new Relay(new NicTable.PosKey(dimension, x, y, z));
        if (YouranNetworkApi.plug(r, dimension, x, y, z) == null) {
            return null;
        }
        r.connect(player);
        relays.put(player.getUniqueID(), r);
        return r;
    }

    boolean unbindPlayer(UUID player) {
        Relay r = relays.remove(player);
        if (r != null) {
            r.disconnect();
            YouranNetworkApi.unplug(r);
            return true;
        }
        return false;
    }

    void onPlayerLeft(UUID player) {
        unbindPlayer(player);
    }

    void clear() {
        for (Emulated e : emulated.values()) {
            YouranNetworkApi.unplug(e);
            e.stack.close();
        }
        emulated.clear();
        for (Relay r : relays.values()) {
            r.disconnect();
            YouranNetworkApi.unplug(r);
        }
        relays.clear();
    }
}
