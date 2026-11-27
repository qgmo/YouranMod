package moe.qingu.youran.server.net;

import moe.qingu.youran.api.network.stack.EventLoop;
import moe.qingu.youran.api.network.stack.NeighborDiscovery;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Consumer;

/**
 * 机房的局域网（一段多点链路，同以太网的一个广播域）：接在上面的每一台（机房路由器、主机的协议栈、原始主机的代答）有一个 48 位链路层地址，
 * 按接上的先后分（本地管理的单播地址 {@code 02:00:00:…}）。帧发给一个链路层地址时只交给那一台，发给 {@link NeighborDiscovery#BROADCAST}
 * 时交给除了发的那台以外的所有台（收的一方自己按组播组过滤）。帧排进网络线程再交（不在发的调用里递归）。网络线程上用。
 */
final class Lan {
    static final long FIRST_ADDRESS = 0x02_00_00_00_00_01L;

    final String name;
    private final EventLoop loop;
    private final Map<Long, Consumer<byte[]>> stations = new LinkedHashMap<>();
    private long next = FIRST_ADDRESS;
    volatile long frames;

    Lan(String name, EventLoop loop) {
        this.name = name;
        this.loop = loop;
    }

    /** 接上一台：分一个链路层地址，收到的帧交给 {@code receiver} */
    long attach(Consumer<byte[]> receiver) {
        long a = next++;
        stations.put(a, receiver);
        return a;
    }

    void detach(long address) {
        stations.remove(address);
    }

    /** 这台的发送口 */
    NeighborDiscovery.Link linkFor(long from) {
        return (packet, dst) -> transmit(from, dst, packet);
    }

    void transmit(long from, long dst, byte[] packet) {
        if (!stations.containsKey(from)) {
            return;
        }
        frames++;
        loop.execute(() -> {
            if (dst == NeighborDiscovery.BROADCAST) {
                List<Map.Entry<Long, Consumer<byte[]>>> all = new ArrayList<>(stations.entrySet());
                for (Map.Entry<Long, Consumer<byte[]>> e : all) {
                    if (e.getKey() != from) {
                        e.getValue().accept(packet);
                    }
                }
            } else {
                Consumer<byte[]> s = stations.get(dst);
                if (s != null) {
                    s.accept(packet);
                }
            }
        });
    }

    int size() {
        return stations.size();
    }
}
