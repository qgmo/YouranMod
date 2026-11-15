package moe.qingu.youran.server.net;

import moe.qingu.youran.api.network.DefaultDimensionNetwork;
import moe.qingu.youran.api.network.DeviceCapability;
import moe.qingu.youran.api.network.DeviceHandle;
import moe.qingu.youran.api.network.DeviceLocation;
import moe.qingu.youran.api.network.Ipv6Address;
import moe.qingu.youran.api.network.NetworkLayout;
import moe.qingu.youran.api.network.SourceIdentity;
import moe.qingu.youran.api.network.stack.ManualEventLoop;
import net.minecraft.entity.player.EntityPlayerMP;
import org.junit.jupiter.api.Test;

import java.util.Collections;
import java.util.EnumSet;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Random;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** 按来源地址认人：设备（能力、操作者、操作者变化、拿掉）与玩家临时地址 */
class IdentifyTest {
    final ManualEventLoop loop = new ManualEventLoop();
    final NetworkLayout layout = NetworkLayout.fromGlobalId(0x0102030405L);
    final AddressBook book;
    final DeviceTable devices;
    final PlayerNics players;

    IdentifyTest() {
        Map<Integer, AddressBook.Dim> dims = new LinkedHashMap<>();
        dims.put(0, new AddressBook.Dim(0, 0, DefaultDimensionNetwork.INSTANCE));
        Map<String, Long> dcs = new LinkedHashMap<>();
        dcs.put("youranmod:main", 0L);
        dcs.put("youranmod:players", NetworkLayout.PLAYERS_DATACENTER);
        book = new AddressBook(layout, dims, dcs, Collections.<String, Ipv6Address>emptyMap());
        devices = new DeviceTable(book, new DeviceTable.Hooks() {
            @Override
            public boolean isLoaded(int dimension, int x, int y, int z) {
                return true;
            }

            @Override
            public Ipv6Address resolverAddress() {
                return null;
            }

            @Override
            public Ipv6Address ntpAddress() {
                return null;
            }

            @Override
            public EntityPlayerMP player(UUID id) {
                return null;
            }

            @Override
            public void fromDevice(DeviceTable.Device d, byte[] packet) {
            }
        });
        Router router = new Router(loop, book, devices, layout.hostAddress(0, 1));
        players = new PlayerNics(loop, layout, router, new RejectLog(10_000), layout.hostAddress(0, 0x53), new Random(1),
                20, 20 * 1280, 20, 20 * 1280);
    }

    @Test
    void deviceWithCapabilitiesAndDeclaredOperator() {
        DeviceHandle h = devices.register(0, 10, 64, -20, DeviceCapability.ONE_CLICK_LOGIN);
        Ipv6Address a = h.address();
        SourceIdentity id = devices.identify(book, a);
        assertEquals(SourceIdentity.Kind.DEVICE, id.kind);
        assertEquals(new DeviceLocation(0, 10, 64, -20), id.location);
        assertEquals(EnumSet.of(DeviceCapability.ONE_CLICK_LOGIN), id.capabilities());
        assertNull(id.operator, "没人操作");
        assertFalse(id.canOneClickLogin(), "有能力但没人操作，也认不出人");

        UUID alice = UUID.randomUUID();
        ((DeviceTable.Device) h).setOperator(alice, "Alice");
        id = devices.identify(book, a);
        assertEquals(alice, id.operator);
        assertEquals("Alice", id.operatorName);
        assertTrue(id.canOneClickLogin());

        h.setCapability(DeviceCapability.LOCATION, true);
        h.setCapability(DeviceCapability.ONE_CLICK_LOGIN, false);
        id = devices.identify(book, a);
        assertEquals(EnumSet.of(DeviceCapability.LOCATION), id.capabilities());
        assertFalse(id.canOneClickLogin(), "去掉了一键登录能力");
        assertTrue(h.hasCapability(DeviceCapability.LOCATION));

        // 换人、清空
        UUID bob = UUID.randomUUID();
        ((DeviceTable.Device) h).setOperator(bob, "Bob");
        assertEquals(bob, devices.identify(book, a).operator);
        h.setOperator(null);
        assertNull(devices.identify(book, a).operator);
    }

    @Test
    void attachedPlayerIsTheOperator() {
        DeviceTable.Device d = (DeviceTable.Device) devices.register(0, 1, 2, 3, DeviceCapability.ONE_CLICK_LOGIN);
        UUID declared = UUID.randomUUID();
        d.setOperator(declared, "Declared");
        UUID carol = UUID.randomUUID();
        d.attach(new DeviceTable.PlayerAttachment(carol, "Carol", null, 1));
        SourceIdentity id = devices.identify(book, d.address());
        assertEquals(carol, id.operator, "网卡接着玩家客户端时操作者就是他，电脑模组说的不算");
        assertEquals("Carol", id.operatorName);
        assertEquals(carol, d.operatorId());

        // 换人：接到另一个玩家
        UUID dave = UUID.randomUUID();
        d.attach(new DeviceTable.PlayerAttachment(dave, "Dave", null, 2));
        assertEquals(dave, devices.identify(book, d.address()).operator);

        // 断开以后回到电脑模组说的（这里还是 Declared）；玩家下线时清掉
        d.detach();
        assertEquals(declared, devices.identify(book, d.address()).operator);
        devices.onPlayerLeft(declared);
        assertNull(devices.identify(book, d.address()).operator);
    }

    @Test
    void removedAndUnknownAddresses() {
        DeviceHandle h = devices.register(0, 5, 70, 5);
        Ipv6Address a = h.address();
        h.setOperator(null);
        h.remove();
        assertNull(devices.identify(book, a), "拿掉以后认不出");
        assertNull(devices.identify(book, devices.addressAt(0, 6, 70, 5)), "那一格没登记设备");
        assertNull(devices.identify(book, layout.hostAddress(0, 0x53)), "机房主机");
        assertNull(devices.identify(book, new Ipv6Address(a.high(), a.low() | 1)), "同一格的 1 号槽位");
        // 同一格重新登记：旧句柄作废、新的什么能力都没有
        DeviceHandle again = devices.register(0, 5, 70, 5);
        assertTrue(devices.identify(book, again.address()).capabilities().isEmpty());
    }

    @Test
    void playerTemporaryAddress() {
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
        Map.Entry<UUID, String> owner = players.owner(a);
        assertEquals(eve, owner.getKey());
        assertEquals("Eve", owner.getValue());
        assertNull(players.owner(new Ipv6Address(a.high(), a.low() ^ 1)), "别的临时地址没人用");
        assertNull(players.owner(devices.addressAt(0, 0, 64, 0)), "不是玩家临时地址");
        SourceIdentity id = new SourceIdentity(SourceIdentity.Kind.PLAYER, a, null, null, owner.getKey(), owner.getValue());
        assertTrue(id.canOneClickLogin(), "玩家临时地址直接就是那个玩家");
        players.detach(eve);
        loop.drain();
        assertNull(players.owner(a), "下线以后收回");
    }
}
