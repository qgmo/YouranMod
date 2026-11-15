package moe.qingu.youran.server.net;

import moe.qingu.youran.api.network.DeviceEndpoint;
import moe.qingu.youran.api.network.DeviceHandle;
import moe.qingu.youran.api.network.DeviceLocation;
import moe.qingu.youran.api.network.Ipv6Address;
import moe.qingu.youran.api.network.NetworkDevices;
import moe.qingu.youran.net.NicChannel;
import moe.qingu.youran.net.NicMessages;
import net.minecraft.entity.player.EntityPlayerMP;
import net.minecraft.network.NetworkManager;
import net.minecraft.util.math.BlockPos;
import net.minecraft.world.World;
import net.minecraftforge.common.DimensionManager;

import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 线程安全的设备表。主线程登记、改状态（通电、区块加载、接到谁）；网络线程只查（按坐标、按玩家）。
 * 状态字段都是 volatile，网络线程读到的是主线程最后写的值。
 */
public final class DeviceTable implements NetworkDevices {
    private final AddressBook addresses;
    private final Hooks bridge;
    private final Map<PosKey, Device> byPosition = new ConcurrentHashMap<>();
    private final Map<UUID, Device> byPlayer = new ConcurrentHashMap<>();
    /** 区块 → 里面的设备（只在主线程上用） */
    private final Map<ChunkKey, Set<Device>> byChunk = new HashMap<>();
    private int generation;

    /** 设备表要从外面拿的东西（服务端由 {@link NetworkServer} 提供；单元测试换成假的） */
    interface Hooks {
        /** 主线程：这一格所在区块加载了没有（不触发加载） */
        boolean isLoaded(int dimension, int x, int y, int z);

        Ipv6Address resolverAddress();

        Ipv6Address ntpAddress();

        EntityPlayerMP player(UUID id);

        /** 设备发包（任意线程）：转给网络线程上的网卡校验 */
        void fromDevice(Device d, byte[] packet);

        /** 主线程：网卡接到玩家时，取到他的连接与身份 */
        default PlayerAttachment attachmentFor(EntityPlayerMP player, int generation) {
            return new PlayerAttachment(player.getUniqueID(), player.getName(), player.connection.getNetworkManager(), generation);
        }
    }

    DeviceTable(AddressBook addresses, Hooks bridge) {
        this.addresses = addresses;
        this.bridge = bridge;
    }

    // ---- 主线程 ----

    @Override
    public DeviceHandle register(int dimension, int x, int y, int z) {
        Ipv6Address a = addresses.coordinateAddress(dimension, x, y, z);
        if (a == null) {
            return null;
        }
        PosKey key = new PosKey(dimension, x, y, z);
        Device old = byPosition.get(key);
        if (old != null) {
            old.remove();
        }
        Device d = new Device(key, a);
        d.loaded = bridge.isLoaded(dimension, x, y, z);
        byPosition.put(key, d);
        byChunk.computeIfAbsent(key.chunk(), k -> new HashSet<>()).add(d);
        return d;
    }

    @Override
    public DeviceHandle at(int dimension, int x, int y, int z) {
        return byPosition.get(new PosKey(dimension, x, y, z));
    }

    @Override
    public Ipv6Address addressAt(int dimension, int x, int y, int z) {
        return addresses.coordinateAddress(dimension, x, y, z);
    }

    /** 默认的区块加载检查 */
    static boolean isLoadedInWorld(int dimension, int x, int y, int z) {
        World w = DimensionManager.getWorld(dimension);
        return w != null && w.isBlockLoaded(new BlockPos(x, y, z));
    }

    /** 区块加载、卸载（主线程，由 {@link NetworkEvents} 调用） */
    void onChunk(int dimension, int cx, int cz, boolean loaded) {
        Set<Device> set = byChunk.get(new ChunkKey(dimension, cx, cz));
        if (set != null) {
            for (Device d : set) {
                d.loaded = loaded;
            }
        }
    }

    /** 维度卸载：里面的设备都算没加载 */
    void onDimensionUnload(int dimension) {
        for (Device d : byPosition.values()) {
            if (d.key.dimension == dimension) {
                d.loaded = false;
            }
        }
    }

    /** 玩家退出：他接着的设备断开 */
    void onPlayerLeft(UUID player) {
        Device d = byPlayer.get(player);
        if (d != null) {
            d.detach();
        }
    }

    /** 关服时：断开所有玩家（告诉客户端），清空 */
    void clear() {
        for (Device d : new ArrayList<>(byPosition.values())) {
            d.remove();
        }
        byPlayer.clear();
        byChunk.clear();
    }

    public Collection<Device> all() {
        return byPosition.values();
    }

    // ---- 网络线程 ----

    /**
     * 地址 → 设备位置：按地址结构算出坐标（{@link AddressBook#locate}），再查那一格上登记着（没被拿掉）的设备；
     * 不是设备地址时为 null。只读并发表与不可变的地址表，任意线程都可以调用。
     */
    DeviceLocation locate(AddressBook book, Ipv6Address address) {
        DeviceLocation l = book.locate(address);
        if (l == null) {
            return null;
        }
        Device d = byPosition.get(new PosKey(l.dimension, l.x, l.y, l.z));
        return d == null || d.removed || !d.address().equals(address) ? null : l;
    }

    Device byPosition(int dimension, int x, int y, int z) {
        return byPosition.get(new PosKey(dimension, x, y, z));
    }

    Device byPlayer(UUID player) {
        return byPlayer.get(player);
    }

    /** 网卡接到哪里（不可变；换的时候整个换掉） */
    abstract static class Attachment {
        /** 把发给设备的包交出去（网络线程） */
        abstract void deliver(byte[] packet);

        abstract String describe();
    }

    static class PlayerAttachment extends Attachment {
        final UUID player;
        final String name;
        final NetworkManager connection;
        final int generation;

        PlayerAttachment(UUID player, String name, NetworkManager connection, int generation) {
            this.player = player;
            this.name = name;
            this.connection = connection;
            this.generation = generation;
        }

        @Override
        void deliver(byte[] packet) {
            NicChannel.sendToClient(connection, NicMessages.packet(packet));
        }

        /** 发网卡控制消息（接上、断开） */
        void control(byte[] message) {
            NicChannel.sendToClient(connection, message);
        }

        @Override
        String describe() {
            return "玩家 " + name;
        }
    }

    static final class EndpointAttachment extends Attachment {
        final DeviceEndpoint endpoint;

        EndpointAttachment(DeviceEndpoint endpoint) {
            this.endpoint = endpoint;
        }

        @Override
        void deliver(byte[] packet) {
            try {
                endpoint.receive(packet);
            } catch (Throwable t) {
                NetworkServer.LOG.error("设备端点处理包时出错", t);
            }
        }

        @Override
        String describe() {
            return "服务端端点";
        }
    }

    public final class Device implements DeviceHandle {
        final PosKey key;
        final Ipv6Address address;
        volatile boolean powered = true;
        volatile boolean loaded;
        volatile boolean removed;
        volatile Attachment attachment;
        /** 只在网络线程上用 */
        RateLimiter limiter;

        Device(PosKey key, Ipv6Address address) {
            this.key = key;
            this.address = address;
        }

        /** 能不能收发包；不能时返回原因，能时返回 null（网络线程上读） */
        String blockedReason() {
            if (removed) {
                return "设备已拿掉";
            }
            if (!powered) {
                return "设备没通电";
            }
            if (!loaded) {
                return "设备所在区块没加载";
            }
            if (attachment == null) {
                return "设备没接网卡（没人操作）";
            }
            return null;
        }

        String label() {
            return "设备 " + address + "（维度 " + key.dimension + " " + key.x + ", " + key.y + ", " + key.z + "）";
        }

        @Override
        public Ipv6Address address() {
            return address;
        }

        @Override
        public int dimension() {
            return key.dimension;
        }

        @Override
        public int x() {
            return key.x;
        }

        @Override
        public int y() {
            return key.y;
        }

        @Override
        public int z() {
            return key.z;
        }

        @Override
        public void setPowered(boolean p) {
            powered = p;
        }

        @Override
        public boolean isPowered() {
            return powered;
        }

        public boolean isLoaded() {
            return loaded;
        }

        @Override
        public void attachPlayer(EntityPlayerMP player) {
            if (removed) {
                return;
            }
            if (player == null) {
                detach();
                return;
            }
            attach(bridge.attachmentFor(player, ++generation));
        }

        /** 网卡接到玩家（{@link #attachPlayer} 取到连接以后；单元测试直接调用） */
        void attach(PlayerAttachment a) {
            if (removed) {
                return;
            }
            Device previous = byPlayer.get(a.player);
            if (previous != null && previous != this) {
                previous.detach();
            }
            detach();
            attachment = a;
            byPlayer.put(a.player, this);
            a.control(NicMessages.attach(a.generation, address, bridge.resolverAddress(), bridge.ntpAddress()));
        }

        @Override
        public void attachEndpoint(DeviceEndpoint endpoint) {
            if (removed) {
                return;
            }
            detach();
            attachment = endpoint == null ? null : new EndpointAttachment(endpoint);
        }

        @Override
        public void detach() {
            Attachment a = attachment;
            attachment = null;
            if (a instanceof PlayerAttachment) {
                PlayerAttachment pa = (PlayerAttachment) a;
                byPlayer.remove(pa.player, this);
                pa.control(NicMessages.detach(pa.generation));
            }
        }

        @Override
        public EntityPlayerMP attachedPlayer() {
            Attachment a = attachment;
            if (!(a instanceof PlayerAttachment)) {
                return null;
            }
            return bridge.player(((PlayerAttachment) a).player);
        }

        public String attachmentDescription() {
            Attachment a = attachment;
            return a == null ? "没接" : a.describe();
        }

        @Override
        public void send(byte[] packet) {
            bridge.fromDevice(this, packet);
        }

        @Override
        public void remove() {
            if (removed) {
                return;
            }
            detach();
            removed = true;
            byPosition.remove(key, this);
            Set<Device> set = byChunk.get(key.chunk());
            if (set != null) {
                set.remove(this);
                if (set.isEmpty()) {
                    byChunk.remove(key.chunk());
                }
            }
        }

        @Override
        public boolean isRemoved() {
            return removed;
        }
    }

    static final class PosKey {
        final int dimension;
        final int x;
        final int y;
        final int z;

        PosKey(int dimension, int x, int y, int z) {
            this.dimension = dimension;
            this.x = x;
            this.y = y;
            this.z = z;
        }

        ChunkKey chunk() {
            return new ChunkKey(dimension, x >> 4, z >> 4);
        }

        @Override
        public boolean equals(Object o) {
            if (!(o instanceof PosKey)) {
                return false;
            }
            PosKey k = (PosKey) o;
            return k.dimension == dimension && k.x == x && k.y == y && k.z == z;
        }

        @Override
        public int hashCode() {
            return ((dimension * 31 + x) * 31 + y) * 31 + z;
        }
    }

    static final class ChunkKey {
        final int dimension;
        final int cx;
        final int cz;

        ChunkKey(int dimension, int cx, int cz) {
            this.dimension = dimension;
            this.cx = cx;
            this.cz = cz;
        }

        @Override
        public boolean equals(Object o) {
            if (!(o instanceof ChunkKey)) {
                return false;
            }
            ChunkKey k = (ChunkKey) o;
            return k.dimension == dimension && k.cx == cx && k.cz == cz;
        }

        @Override
        public int hashCode() {
            return (dimension * 31 + cx) * 31 + cz;
        }
    }

    /** 测试用：现在接着设备的玩家 */
    List<UUID> attachedPlayers() {
        return new ArrayList<>(byPlayer.keySet());
    }
}
