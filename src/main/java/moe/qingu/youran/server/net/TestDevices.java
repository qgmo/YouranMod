package moe.qingu.youran.server.net;

import moe.qingu.youran.api.network.DeviceHandle;
import moe.qingu.youran.api.network.Ipv6Address;
import moe.qingu.youran.api.network.stack.DnsResolver;
import moe.qingu.youran.api.network.stack.EventLoop;
import moe.qingu.youran.api.network.stack.NetStack;

import java.security.SecureRandom;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * 测试用的设备（只给测试命令用，不存盘）：
 * <ul>
 *   <li>服务端测试设备：在某一格登记一台设备，网卡接到网络线程上的一个协议栈（相当于一台服务端模拟的电脑），能 ping、查 DNS、取网页；</li>
 *   <li>把玩家当成某一格上的电脑：网卡接到这个玩家的客户端，协议栈跑在他的客户端上（方案 A）。</li>
 * </ul>
 */
final class TestDevices {
    private final DeviceTable devices;
    private final EventLoop loop;
    private final Ipv6Address resolver;
    private final Map<DeviceTable.PosKey, Emulated> emulated = new LinkedHashMap<>();
    private final Map<UUID, DeviceHandle> players = new LinkedHashMap<>();

    static final class Emulated {
        final DeviceHandle handle;
        final NetStack stack;
        final DnsResolver dns;

        Emulated(DeviceHandle handle, NetStack stack, DnsResolver dns) {
            this.handle = handle;
            this.stack = stack;
            this.dns = dns;
        }
    }

    TestDevices(DeviceTable devices, EventLoop loop, Ipv6Address resolver) {
        this.devices = devices;
        this.loop = loop;
        this.resolver = resolver;
    }

    /** 在这一格放一台服务端测试设备（已有的先拿掉）；没有地址返回 null */
    Emulated add(int dimension, int x, int y, int z) {
        remove(dimension, x, y, z);
        DeviceHandle h = devices.register(dimension, x, y, z);
        if (h == null) {
            return null;
        }
        NetStack stack = new NetStack(loop, h::send, new SecureRandom());
        stack.addAddress(h.address());
        h.attachEndpoint(stack::receive);
        Emulated e = new Emulated(h, stack, new DnsResolver(stack, resolver));
        emulated.put(new DeviceTable.PosKey(dimension, x, y, z), e);
        return e;
    }

    Emulated get(int dimension, int x, int y, int z) {
        Emulated e = emulated.get(new DeviceTable.PosKey(dimension, x, y, z));
        if (e != null && e.handle.isRemoved()) {
            e.stack.close();
            emulated.remove(new DeviceTable.PosKey(dimension, x, y, z));
            return null;
        }
        return e;
    }

    boolean remove(int dimension, int x, int y, int z) {
        Emulated e = emulated.remove(new DeviceTable.PosKey(dimension, x, y, z));
        if (e != null) {
            e.handle.remove();
            e.stack.close();
            return true;
        }
        return false;
    }

    List<Emulated> list() {
        List<Emulated> out = new ArrayList<>();
        for (Emulated e : new ArrayList<>(emulated.values())) {
            if (!e.handle.isRemoved()) {
                out.add(e);
            }
        }
        return out;
    }

    /** 把玩家当成这一格上的电脑；没有地址返回 null */
    DeviceHandle bindPlayer(net.minecraft.entity.player.EntityPlayerMP player, int dimension, int x, int y, int z) {
        Emulated old = emulated.remove(new DeviceTable.PosKey(dimension, x, y, z));
        if (old != null) {
            old.stack.close();
        }
        unbindPlayer(player.getUniqueID());
        DeviceHandle h = devices.at(dimension, x, y, z);
        if (h == null) {
            h = devices.register(dimension, x, y, z);
        }
        if (h == null) {
            return null;
        }
        h.attachPlayer(player);
        players.put(player.getUniqueID(), h);
        return h;
    }

    boolean unbindPlayer(UUID player) {
        DeviceHandle h = players.remove(player);
        if (h != null) {
            h.remove();
            return true;
        }
        return false;
    }

    void onPlayerLeft(UUID player) {
        unbindPlayer(player);
    }

    void clear() {
        for (Emulated e : emulated.values()) {
            e.handle.remove();
            e.stack.close();
        }
        emulated.clear();
        for (DeviceHandle h : players.values()) {
            h.remove();
        }
        players.clear();
    }
}
