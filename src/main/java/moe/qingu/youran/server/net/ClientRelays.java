package moe.qingu.youran.server.net;

import moe.qingu.youran.api.network.Ipv6Address;
import moe.qingu.youran.api.network.stack.EventLoop;
import moe.qingu.youran.net.NicChannel;
import moe.qingu.youran.net.NicMessages;
import moe.qingu.youran.network.ClientRelay;
import moe.qingu.youran.network.RelayEnd;
import net.minecraft.entity.player.EntityPlayerMP;
import net.minecraft.network.NetworkManager;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Supplier;

/**
 * 网卡与玩家客户端之间的中转（{@link ClientRelay} 的实现，{@code ClientRelayNic} 用）：每个接着的网卡在玩家客户端上是一个网口，
 * 网口号由这里按顺序分（1–255，0 是玩家自己的网口）。一个玩家同时只接一张网卡：接新的时旧的断开（告诉客户端去掉那个网口、告诉旧网卡没接着了）。
 * 玩家下线时断开。客户端发来的包按网口号找到网卡，网口号对不上（已经去掉的或从来没有的）就丢掉。
 * <p>
 * 线程：接上、断开任意线程（一般是主线程）都行，表是并发的，改动加锁；收发包在网络线程上。
 */
final class ClientRelays implements ClientRelay {
    /** 到玩家客户端的连接（真实的是 {@code NicChannel}；单元测试换成假的） */
    interface Link {
        void send(byte[] message);

        boolean isOpen();
    }

    static final class Conn {
        final RelayEnd end;
        final UUID player;
        final String name;
        final Link link;
        final int port;
        final Ipv6Address address;

        Conn(RelayEnd end, UUID player, String name, Link link, int port, Ipv6Address address) {
            this.end = end;
            this.player = player;
            this.name = name;
            this.link = link;
            this.port = port;
            this.address = address;
        }
    }

    private final EventLoop loop;
    private final RejectLog rejects;
    private final Supplier<Ipv6Address> resolver;
    private final Supplier<Ipv6Address> ntp;
    private final Map<RelayEnd, Conn> byEnd = new ConcurrentHashMap<>();
    private final Map<UUID, Conn> byPlayer = new ConcurrentHashMap<>();
    /** 玩家 → 上次分的网口号（下一个从它往后找） */
    private final Map<UUID, Integer> lastPort = new ConcurrentHashMap<>();
    volatile long toClient;
    volatile long fromClient;

    ClientRelays(EventLoop loop, RejectLog rejects, Supplier<Ipv6Address> resolver, Supplier<Ipv6Address> ntp) {
        this.loop = loop;
        this.rejects = rejects;
        this.resolver = resolver;
        this.ntp = ntp;
    }

    // ---- 接上、断开 ----

    @Override
    public void connect(RelayEnd end, Ipv6Address address, EntityPlayerMP player) {
        if (player == null) {
            disconnect(end);
            return;
        }
        NetworkManager conn = player.connection == null ? null : player.connection.getNetworkManager();
        connect(end, address, player.getUniqueID(), player.getName(), player, new Link() {
            @Override
            public void send(byte[] message) {
                NicChannel.sendToClient(conn, message);
            }

            @Override
            public boolean isOpen() {
                return conn != null && conn.isChannelOpen();
            }
        });
    }

    /** 接上（单元测试直接调用，{@code entity} 可以为 null） */
    synchronized void connect(RelayEnd end, Ipv6Address address, UUID player, String name, EntityPlayerMP entity, Link link) {
        if (end == null || address == null || player == null) {
            return;
        }
        Conn cur = byEnd.get(end);
        if (cur != null && cur.player.equals(player) && cur.address.equals(address)) {
            return;
        }
        if (cur != null) {
            drop(cur);
        }
        Conn prev = byPlayer.get(player);
        if (prev != null) {
            drop(prev);
        }
        int port = nextPort(player);
        Conn c = new Conn(end, player, name, link, port, address);
        byEnd.put(end, c);
        byPlayer.put(player, c);
        link.send(NicMessages.portUp(port, NicMessages.KIND_RELAY, address, resolver.get(), ntp.get()));
        notifyEnd(end, entity);
    }

    private int nextPort(UUID player) {
        int last = lastPort.getOrDefault(player, 0);
        int port = last % NicMessages.MAX_PORT + 1;
        lastPort.put(player, port);
        return port;
    }

    @Override
    public synchronized void disconnect(RelayEnd end) {
        Conn c = end == null ? null : byEnd.get(end);
        if (c != null) {
            drop(c);
        }
    }

    private void drop(Conn c) {
        byEnd.remove(c.end, c);
        byPlayer.remove(c.player, c);
        c.link.send(NicMessages.portDown(c.port));
        notifyEnd(c.end, null);
    }

    private static void notifyEnd(RelayEnd end, EntityPlayerMP player) {
        try {
            end.connected(player);
        } catch (Throwable t) {
            NetworkServer.LOG.error("游戏内网络：网卡处理“接着谁”时出错", t);
        }
    }

    /** 玩家下线：他接着的网卡断开 */
    synchronized void playerLeft(UUID player) {
        Conn c = byPlayer.get(player);
        if (c != null) {
            drop(c);
        }
        lastPort.remove(player);
    }

    /** 关服：全部断开 */
    synchronized void clear() {
        for (Conn c : new ArrayList<>(byEnd.values())) {
            drop(c);
        }
        lastPort.clear();
    }

    // ---- 收发（网络线程） ----

    @Override
    public boolean toClient(EntityPlayerMP player, byte[] packet) {
        return player != null && toClient(player.getUniqueID(), packet);
    }

    boolean toClient(UUID player, byte[] packet) {
        Conn c = byPlayer.get(player);
        if (c == null || !c.link.isOpen()) {
            return false;
        }
        toClient++;
        c.link.send(NicMessages.packet(c.port, packet));
        return true;
    }

    /** 玩家客户端从网口 {@code port}（1–255）发来的包：交给接着的那张网卡发出去；网口号对不上时丢掉 */
    void fromClient(UUID player, String name, int port, byte[] packet) {
        Conn c = byPlayer.get(player);
        if (c == null || c.port != port) {
            rejects.reject("玩家 " + name + " 的网口 " + port, "没有这个网口（已经去掉的或从来没有的网口号）", loop.nowMillis());
            return;
        }
        fromClient++;
        // 没收下时原因由网卡的端口记（不合格、超速、已经拔掉）
        try {
            c.end.fromClient(packet);
        } catch (Throwable t) {
            NetworkServer.LOG.error("游戏内网络：网卡处理客户端发来的包时出错", t);
        }
    }

    /** 这个玩家现在接着的网口号；没接为 -1（测试、/ynet 用） */
    int portOf(UUID player) {
        Conn c = byPlayer.get(player);
        return c == null ? -1 : c.port;
    }

    /** 给 /ynet 看 */
    List<String> describe() {
        List<String> out = new ArrayList<>();
        for (Conn c : byPlayer.values()) {
            out.add(c.name + " 网口 " + c.port + " → 网卡 " + c.address);
        }
        return out;
    }
}
