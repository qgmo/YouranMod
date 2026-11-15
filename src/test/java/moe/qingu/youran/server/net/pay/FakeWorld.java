package moe.qingu.youran.server.net.pay;

import moe.qingu.youran.api.pay.PayItem;

import java.io.IOException;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * 测试用的世界：内存里的背包 / 标记 / 掉落物，和“存档”（写盘后的样子）分开。{@link #crashAt} 设成某一步时，走到那一步抛 {@link Crash}
 * （模拟服务器在那里崩了：内存里的东西都没了，只剩存档）。
 */
class FakeWorld implements WorldPort {
    /** 模拟崩溃（Error：不会被 TxRunner 当成普通失败接住） */
    static final class Crash extends Error {
        Crash(String where) {
            super(where);
        }
    }

    final Map<UUID, FakePlayer> online = new LinkedHashMap<>();
    /** 存档：玩家背包、标记 */
    final Map<UUID, Map<PayItem, Long>> diskInv = new HashMap<>();
    final Map<UUID, Set<String>> diskMarkers = new HashMap<>();
    /** 内存里与存档里的掉落物：tx → 物品 */
    final Map<String, Map<PayItem, Long>> memDrops = new HashMap<>();
    final Map<String, Map<PayItem, Long>> diskDrops = new HashMap<>();
    String crashAt;
    /** 写盘不生效（模拟写失败） */
    boolean persistFails;
    boolean chunkPersistFails;
    /** 掉落物生不出来（被保护插件拦了） */
    boolean spawnFails;

    void step(String where) {
        if (where.equals(crashAt)) {
            throw new Crash(where);
        }
    }

    FakePlayer join(UUID id, String name, int capacity) {
        FakePlayer p = new FakePlayer(id, name, capacity);
        p.inv.putAll(diskInv.getOrDefault(id, new LinkedHashMap<PayItem, Long>()));
        p.markers.addAll(diskMarkers.getOrDefault(id, new HashSet<String>()));
        online.put(id, p);
        return p;
    }

    /** 崩了以后重开：内存全丢，按存档重建（掉落物在区块里，开服加载后在内存里） */
    FakeWorld restart() {
        FakeWorld w = new FakeWorld();
        for (Map.Entry<UUID, Map<PayItem, Long>> e : diskInv.entrySet()) {
            w.diskInv.put(e.getKey(), new LinkedHashMap<>(e.getValue()));
        }
        for (Map.Entry<UUID, Set<String>> e : diskMarkers.entrySet()) {
            w.diskMarkers.put(e.getKey(), new HashSet<>(e.getValue()));
        }
        for (Map.Entry<String, Map<PayItem, Long>> e : diskDrops.entrySet()) {
            w.diskDrops.put(e.getKey(), new LinkedHashMap<>(e.getValue()));
            w.memDrops.put(e.getKey(), new LinkedHashMap<>(e.getValue()));
        }
        return w;
    }

    @Override
    public PlayerPort player(UUID id) {
        return online.get(id);
    }

    @Override
    public boolean markerOnDisk(UUID player, String tx) throws IOException {
        step("verify");
        return diskMarkers.getOrDefault(player, new HashSet<String>()).contains(tx);
    }

    @Override
    public Map<PayItem, Long> dropsOnDisk(int dim, int cx, int cz, String tx) {
        return new LinkedHashMap<>(diskDrops.getOrDefault(tx, new LinkedHashMap<PayItem, Long>()));
    }

    @Override
    public void persistChunk(int dim, int cx, int cz) throws IOException {
        step("chunk");
        if (chunkPersistFails) {
            throw new IOException("区块写不进去");
        }
        diskDrops.clear();
        for (Map.Entry<String, Map<PayItem, Long>> e : memDrops.entrySet()) {
            diskDrops.put(e.getKey(), new LinkedHashMap<>(e.getValue()));
        }
        step("chunk-done");
    }

    @Override
    public void killDrops(int dim, int cx, int cz, String tx) {
        memDrops.remove(tx);
    }

    final Set<String> released = new HashSet<>();

    @Override
    public void releaseDrops(int dim, int cx, int cz, String tx) {
        step("release");
        released.add(tx);
    }

    /** 掉落物都算这个玩家的（测试里只有一个玩家拿掉落物） */
    Map<PayItem, Long> allDiskDrops() {
        Map<PayItem, Long> m = new LinkedHashMap<>();
        for (Map<PayItem, Long> d : diskDrops.values()) {
            for (Map.Entry<PayItem, Long> e : d.entrySet()) {
                m.merge(e.getKey(), e.getValue(), Long::sum);
            }
        }
        return m;
    }

    final class FakePlayer implements PlayerPort {
        final UUID id;
        final String name;
        /** 背包最多放多少个（简化：不分格子） */
        final int capacity;
        final Map<PayItem, Long> inv = new LinkedHashMap<>();
        final Set<String> markers = new HashSet<>();

        FakePlayer(UUID id, String name, int capacity) {
            this.id = id;
            this.name = name;
            this.capacity = capacity;
        }

        long total() {
            long t = 0;
            for (long v : inv.values()) {
                t += v;
            }
            return t;
        }

        @Override
        public UUID id() {
            return id;
        }

        @Override
        public String name() {
            return name;
        }

        @Override
        public Map<PayItem, Long> counts() {
            return new LinkedHashMap<>(inv);
        }

        @Override
        public boolean take(Map<PayItem, Long> items) {
            step("take");
            for (Map.Entry<PayItem, Long> e : items.entrySet()) {
                if (inv.getOrDefault(e.getKey(), 0L) < e.getValue()) {
                    return false;
                }
            }
            for (Map.Entry<PayItem, Long> e : items.entrySet()) {
                long left = inv.get(e.getKey()) - e.getValue();
                if (left == 0) {
                    inv.remove(e.getKey());
                } else {
                    inv.put(e.getKey(), left);
                }
            }
            return true;
        }

        @Override
        public Map<PayItem, Long> overflow(Map<PayItem, Long> take, Map<PayItem, Long> give) {
            long t = total();
            for (long v : take.values()) {
                t -= v;
            }
            return fit(give, capacity - t);
        }

        private Map<PayItem, Long> fit(Map<PayItem, Long> give, long room) {
            Map<PayItem, Long> rest = new LinkedHashMap<>();
            for (Map.Entry<PayItem, Long> e : give.entrySet()) {
                long put = Math.max(0, Math.min(room, e.getValue()));
                room -= put;
                if (e.getValue() - put > 0) {
                    rest.put(e.getKey(), e.getValue() - put);
                }
            }
            return rest;
        }

        @Override
        public Map<PayItem, Long> add(Map<PayItem, Long> items) {
            step("add");
            long room = capacity - total();
            Map<PayItem, Long> rest = fit(items, room);
            for (Map.Entry<PayItem, Long> e : items.entrySet()) {
                long put = e.getValue() - rest.getOrDefault(e.getKey(), 0L);
                if (put > 0) {
                    inv.merge(e.getKey(), put, Long::sum);
                }
            }
            return rest;
        }

        @Override
        public void mark(String tx) {
            step("mark");
            markers.add(tx);
        }

        @Override
        public void unmark(String tx) {
            step("unmark");
            markers.remove(tx);
        }

        @Override
        public void persist() {
            step("persist");
            if (persistFails) {
                return;
            }
            diskInv.put(id, new LinkedHashMap<>(inv));
            diskMarkers.put(id, new HashSet<>(markers));
            step("persist-done");
        }

        @Override
        public int dimension() {
            return 0;
        }

        @Override
        public int chunkX() {
            return 1;
        }

        @Override
        public int chunkZ() {
            return 2;
        }

        @Override
        public Map<PayItem, Long> drop(Map<PayItem, Long> items, String tx) {
            step("drop");
            if (spawnFails) {
                return new LinkedHashMap<>();
            }
            Map<PayItem, Long> m = memDrops.computeIfAbsent(tx, k -> new LinkedHashMap<>());
            for (Map.Entry<PayItem, Long> e : items.entrySet()) {
                m.merge(e.getKey(), e.getValue(), Long::sum);
            }
            return new LinkedHashMap<>(items);
        }
    }
}
