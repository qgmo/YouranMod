package moe.qingu.youran.api.network.stack;

import moe.qingu.youran.api.network.Ipv6Address;
import moe.qingu.youran.api.network.packet.Ipv6Packet;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.function.Function;

/**
 * 测试用的“网络”：按目的地址把包交给对应的协议栈（隔 1 毫秒到）。{@link #filter} 可以丢包、推迟、改包：
 * 返回 null 丢掉，返回正数表示推迟这么多毫秒，0 表示照常。
 */
public final class TestLink {
    public final ManualEventLoop loop = new ManualEventLoop();
    private final Map<Ipv6Address, NetStack> hosts = new HashMap<>();
    public final List<byte[]> sent = new ArrayList<>();
    public Function<byte[], Long> filter = p -> 0L;
    public long latency = 1;

    public NetStack host(String address) {
        return host(address, new Random(address.hashCode()));
    }

    public NetStack host(String address, Random random) {
        Ipv6Address a = Ipv6Address.parse(address);
        NetStack s = new NetStack(loop, this::deliver, random);
        s.addAddress(a);
        hosts.put(a, s);
        return s;
    }

    private void deliver(byte[] p) {
        sent.add(p);
        Long d = filter.apply(p);
        if (d == null) {
            return;
        }
        NetStack dst = hosts.get(Ipv6Packet.destinationOf(p));
        if (dst != null) {
            loop.schedule(() -> dst.receive(p), latency + d);
        }
    }
}
