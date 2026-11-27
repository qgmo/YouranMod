package moe.qingu.youran.server.net;

import moe.qingu.youran.api.network.DefaultDimensionNetwork;
import moe.qingu.youran.api.network.Ipv6Address;
import moe.qingu.youran.api.network.NetworkLayout;
import moe.qingu.youran.api.network.stack.ManualEventLoop;
import moe.qingu.youran.network.NetworkHooks;
import org.junit.jupiter.api.Test;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Random;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 原来测“按来源地址认人”（{@code identify}）。服主 11-27 把网络层的认人去掉了（网卡只管收发；认人由持有网卡对象的电脑模组签操作者证明，
 * 见 {@link ProofDeskTest}），这里改测它留下的那一层：插着的网卡按地址查得到、拔掉就查不到；接玩家客户端的网卡“接着谁”只由中转说了算；
 * 玩家临时地址不是插着的网卡。
 */
class IdentifyTest {
    final ManualEventLoop loop = new ManualEventLoop();
    final NetworkLayout layout = NetworkLayout.fromGlobalId(0x0102030405L);
    final AddressBook book;
    final NicTable nics;
    final PlayerNics players;
    final ClientRelays relays;

    IdentifyTest() {
        Map<Integer, AddressBook.Dim> dims = new LinkedHashMap<>();
        dims.put(0, new AddressBook.Dim(0, 0, DefaultDimensionNetwork.INSTANCE));
        Map<String, Long> dcs = new LinkedHashMap<>();
        dcs.put("youranmod:main", 0L);
        dcs.put("youranmod:players", NetworkLayout.PLAYERS_DATACENTER);
        book = new AddressBook(layout, dims, dcs, Collections.<String, Ipv6Address>emptyMap());
        RejectLog rejects = new RejectLog(10_000);
        Router[] router = new Router[1];
        nics = new NicTable(book::coordinateAddress, loop, rejects, p -> router[0].forward(p), 20, 20 * 1280, p -> {
        });
        router[0] = new Router(loop, book, nics, layout.hostAddress(0, 1));
        players = new PlayerNics(loop, layout, router[0], rejects, layout.hostAddress(0, 0x53), new Random(1), 20, 20 * 1280, 20, 20 * 1280);
        relays = new ClientRelays(loop, rejects, () -> null, () -> null);
    }

    Ipv6Address plug(TestNic nic, int dim, int x, int y, int z) {
        NicTable.Port p = nics.plug(nic, dim, x, y, z);
        if (p == null) {
            return null;
        }
        NetworkHooks.nicAccess().attach(nic, p);
        return p.address;
    }

    @Test
    void networkKnowsOnlyTheNicAtAnAddress() {
        TestNic nic = new TestNic();
        Ipv6Address a = plug(nic, 0, 10, 64, -20);
        NicTable.Port p = nics.byAddress(a);
        assertNotNull(p);
        assertSame(nic, p.nic, "按地址查到的只是一张网卡：不带能力、不带操作者");
        assertEquals(a, nic.address());
    }

    @Test
    void connectedPlayerComesFromTheRelayOnly() {
        ClientRelaysTest.End end = new ClientRelaysTest.End();
        ClientRelaysTest.Link link = new ClientRelaysTest.Link();
        UUID carol = UUID.randomUUID();
        UUID dave = UUID.randomUUID();
        Ipv6Address a = Ipv6Address.parse("fd01:203:405::1:0");
        relays.connect(end, a, carol, "Carol", null, link);
        assertEquals(1, relays.portOf(carol), "接着 Carol 的客户端");
        // 换人：同一张网卡接到 Dave，Carol 那边断开
        relays.connect(end, a, dave, "Dave", null, link);
        assertEquals(-1, relays.portOf(carol));
        assertEquals(1, relays.portOf(dave));
        // Dave 下线：断开
        relays.playerLeft(dave);
        assertEquals(-1, relays.portOf(dave));
    }

    @Test
    void unpluggedAndUnknownAddresses() {
        TestNic nic = new TestNic();
        Ipv6Address a = plug(nic, 0, 5, 70, 5);
        nics.unplug(NetworkHooks.nicAccess().portOf(nic));
        assertNull(nics.byAddress(a), "拔掉以后查不到");
        assertNull(nics.byAddress(nics.addressAt(0, 6, 70, 5)), "那一格没插网卡");
        assertNull(nics.byAddress(layout.hostAddress(0, 0x53)), "机房主机不是插着的网卡");
        assertNull(nics.byAddress(new Ipv6Address(a.high(), a.low() | 1)), "同一格的 1 号槽位");
        // 同一格插一张新网卡：地址一样，是新的那张
        TestNic again = new TestNic();
        assertEquals(a, plug(again, 0, 5, 70, 5));
        assertSame(again, nics.byAddress(a).nic);
    }

    @Test
    void playerTemporaryAddressIsNotAPluggedNic() {
        UUID eve = UUID.randomUUID();
        Ipv6Address a = players.attach(eve, "Eve", new PlayerNics.Link() {
            @Override
            public void deliver(byte[] packet) {
            }

            @Override
            public void control(byte[] message) {
            }
        });
        loop.drain();
        assertEquals(a, players.address(eve));
        assertTrue(layout.isPlayerAddress(a));
        assertNull(nics.byAddress(a), "玩家临时地址在玩法段里，不是坐标层插着的网卡");
        players.detach(eve);
        loop.drain();
        assertNull(players.address(eve), "下线以后收回");
    }
}
