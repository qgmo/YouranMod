package moe.qingu.youran.server.net;

import moe.qingu.youran.api.network.Ipv6Address;
import moe.qingu.youran.api.network.NetworkInterface;
import moe.qingu.youran.network.NetworkHooks;
import moe.qingu.youran.network.NicPort;

import java.util.ArrayList;
import java.util.List;

/** 测试用的网卡：收到的包记下来；{@link #refuse} 为真时收不了。{@link #plugAt} 不经网络、直接装一个只有地址的端口 */
class TestNic extends NetworkInterface {
    final List<byte[]> received = new ArrayList<>();
    boolean refuse;

    @Override
    public boolean receive(byte[] packet) {
        if (refuse) {
            return false;
        }
        received.add(packet);
        return true;
    }

    boolean out(byte[] packet) {
        return send(packet);
    }

    /** 只有地址的端口（发出的包记在 {@code sent} 里） */
    static final class FakePort implements NicPort {
        final Ipv6Address address;
        final List<byte[]> sent = new ArrayList<>();

        FakePort(Ipv6Address address) {
            this.address = address;
        }

        @Override
        public Ipv6Address address() {
            return address;
        }

        @Override
        public boolean send(byte[] packet) {
            sent.add(packet);
            return true;
        }
    }

    FakePort plugAt(Ipv6Address a) {
        FakePort p = new FakePort(a);
        if (!NetworkHooks.nicAccess().attach(this, p)) {
            throw new IllegalStateException("已经插着");
        }
        return p;
    }

    void unplugFake() {
        NicPort p = NetworkHooks.nicAccess().portOf(this);
        NetworkHooks.nicAccess().detach(this, p);
    }
}
