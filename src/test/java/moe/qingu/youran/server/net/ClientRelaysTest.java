package moe.qingu.youran.server.net;

import moe.qingu.youran.api.network.Ipv6Address;
import moe.qingu.youran.api.network.stack.ManualEventLoop;
import moe.qingu.youran.net.NicMessages;
import moe.qingu.youran.network.RelayEnd;
import net.minecraft.entity.player.EntityPlayerMP;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** 网卡与玩家客户端之间的中转：网口号、一个玩家只接一张、包消息带网口号、旧的网口号的包丢掉 */
class ClientRelaysTest {
    final ManualEventLoop loop = new ManualEventLoop();
    final RejectLog rejects = new RejectLog(10_000);
    final Ipv6Address resolver = Ipv6Address.parse("fd00::53");
    final Ipv6Address ntp = Ipv6Address.parse("fd00::123");
    final ClientRelays relays = new ClientRelays(loop, rejects, () -> resolver, () -> ntp);
    final UUID carol = UUID.fromString("00000000-0000-0000-0000-0000000000c0");
    final Ipv6Address pcA = Ipv6Address.parse("fd00:0:0:0:a::");
    final Ipv6Address pcB = Ipv6Address.parse("fd00:0:0:0:b::");

    /** 记下中转对它做了什么 */
    static final class End implements RelayEnd {
        final List<byte[]> fromClient = new ArrayList<>();

        @Override
        public void connected(EntityPlayerMP player) {
            // 单元测试里没有玩家实体：接上时也是 null，按调用次数算
        }

        @Override
        public boolean fromClient(byte[] packet) {
            fromClient.add(packet);
            return true;
        }
    }

    /** 假的客户端连接 */
    static final class Link implements ClientRelays.Link {
        final List<byte[]> messages = new ArrayList<>();
        boolean open = true;

        @Override
        public void send(byte[] message) {
            messages.add(message);
        }

        @Override
        public boolean isOpen() {
            return open;
        }

        byte[] last() {
            return messages.get(messages.size() - 1);
        }
    }

    @Test
    void connectAnnouncesAPortAndPacketsCarryIt() {
        End a = new End();
        Link link = new Link();
        relays.connect(a, pcA, carol, "Carol", null, link);
        byte[] up = link.last();
        assertEquals(NicMessages.PORT_UP, up[0]);
        assertEquals(1, up[1], "第一个接着的网卡是 1 号网口（0 号是玩家自己的）");
        assertEquals(NicMessages.KIND_RELAY, up[2]);
        assertEquals(pcA, Ipv6Address.read(up, 3));
        assertEquals(resolver, Ipv6Address.read(up, 19));
        assertEquals(ntp, Ipv6Address.read(up, 35));
        assertEquals(1, relays.portOf(carol));
        // 发给客户端的包带网口号
        byte[] packet = new byte[40];
        packet[0] = 0x60;
        assertTrue(relays.toClient(carol, packet));
        assertEquals(NicMessages.PACKET, link.last()[0]);
        assertEquals(1, link.last()[1]);
        assertEquals(42, link.last().length);
        // 客户端从 1 号网口发来的包交给这张网卡
        relays.fromClient(carol, "Carol", 1, packet);
        assertEquals(1, a.fromClient.size());
        // 连接断了：发不出去
        link.open = false;
        assertFalse(relays.toClient(carol, packet));
    }

    @Test
    void onePlayerOneNicAndOldPortsAreDropped() {
        End a = new End();
        End b = new End();
        Link link = new Link();
        relays.connect(a, pcA, carol, "Carol", null, link);
        // 接另一张：旧的断开（客户端去掉 1 号网口），新的是 2 号
        relays.connect(b, pcB, carol, "Carol", null, link);
        assertEquals(NicMessages.PORT_DOWN, link.messages.get(1)[0]);
        assertEquals(1, link.messages.get(1)[1]);
        assertEquals(NicMessages.PORT_UP, link.messages.get(2)[0]);
        assertEquals(2, link.messages.get(2)[1]);
        assertEquals(2, relays.portOf(carol));
        // 客户端还没处理完、从旧的 1 号网口发来的包：丢掉，记日志
        byte[] packet = new byte[40];
        packet[0] = 0x60;
        relays.fromClient(carol, "Carol", 1, packet);
        relays.fromClient(carol, "Carol", 7, packet);
        assertTrue(a.fromClient.isEmpty());
        assertTrue(b.fromClient.isEmpty());
        assertEquals(2, rejects.total());
        relays.fromClient(carol, "Carol", 2, packet);
        assertEquals(1, b.fromClient.size());
        // 同一张网卡、同一个人再接一次：什么都不变
        int n = link.messages.size();
        relays.connect(b, pcB, carol, "Carol", null, link);
        assertEquals(n, link.messages.size());
        // 断开、下线
        relays.disconnect(b);
        assertArrayEquals(NicMessages.portDown(2), link.last());
        assertEquals(-1, relays.portOf(carol));
        relays.connect(a, pcA, carol, "Carol", null, link);
        assertEquals(3, relays.portOf(carol), "网口号接着往后分，不马上重用");
        relays.playerLeft(carol);
        assertArrayEquals(NicMessages.portDown(3), link.last());
        assertFalse(relays.toClient(carol, packet));
    }

    @Test
    void portNumbersWrapWithinOneToTwoFiftyFive() {
        Link link = new Link();
        End e = new End();
        for (int i = 0; i < 300; i++) {
            relays.connect(e, i % 2 == 0 ? pcA : pcB, carol, "Carol", null, link);
            int port = relays.portOf(carol);
            assertTrue(port >= 1 && port <= 255, "网口号 " + port);
            assertEquals(i % 255 + 1, port);
        }
    }
}
