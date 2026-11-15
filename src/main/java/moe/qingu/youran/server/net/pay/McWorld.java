package moe.qingu.youran.server.net.pay;

import moe.qingu.youran.api.pay.PayItem;
import net.minecraft.entity.Entity;
import net.minecraft.entity.item.EntityItem;
import net.minecraft.entity.player.EntityPlayerMP;
import net.minecraft.item.ItemStack;
import net.minecraft.nbt.CompressedStreamTools;
import net.minecraft.nbt.NBTBase;
import net.minecraft.nbt.NBTTagCompound;
import net.minecraft.nbt.NBTTagList;
import net.minecraft.nbt.NBTTagString;
import net.minecraft.server.MinecraftServer;
import net.minecraft.util.math.MathHelper;
import net.minecraft.world.MinecraftException;
import net.minecraft.world.WorldServer;
import net.minecraft.world.chunk.Chunk;
import net.minecraft.world.chunk.storage.AnvilChunkLoader;
import net.minecraft.world.chunk.storage.RegionFileCache;
import net.minecraft.world.gen.ChunkProviderServer;
import net.minecraft.world.storage.ThreadedFileIOBase;
import net.minecraftforge.common.DimensionManager;
import net.minecraftforge.items.ItemHandlerHelper;

import java.io.DataInputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/** {@link WorldPort} 的实现：真的背包、玩家存档、区块存档（服务端主线程） */
final class McWorld implements WorldPort {
    /** 玩家数据（ForgeData）里放进行中的那几笔的标记（字符串列表） */
    static final String MARKER = "youranmod_pay";
    /** 暂时不能捡（实际上是“永远”，直到这笔算数） */
    static final int HOLD_PICKUP = 32767;

    private final MinecraftServer server;
    /** 拿不准的物品要不要收（黑名单等）由调用方先查；这里只管能不能记账 */
    McWorld(MinecraftServer server) {
        this.server = server;
    }

    @Override
    public PlayerPort player(UUID id) {
        EntityPlayerMP p = server.getPlayerList().getPlayerByUUID(id);
        return p == null ? null : new McPlayer(p);
    }

    EntityPlayerMP entity(UUID id) {
        return server.getPlayerList().getPlayerByUUID(id);
    }

    private File playerFile(UUID player) {
        return new File(new File(server.getWorld(0).getSaveHandler().getWorldDirectory(), "playerdata"), player + ".dat");
    }

    @Override
    public boolean markerOnDisk(UUID player, String tx) throws IOException {
        File f = playerFile(player);
        if (!f.isFile()) {
            return false;
        }
        NBTTagCompound c;
        try (InputStream in = new FileInputStream(f)) {
            c = CompressedStreamTools.readCompressed(in);
        }
        return hasMarker(c.getCompoundTag("ForgeData"), tx);
    }

    static boolean hasMarker(NBTTagCompound forgeData, String tx) {
        NBTTagList l = forgeData.getTagList(MARKER, 8);
        for (int i = 0; i < l.tagCount(); i++) {
            if (tx.equals(l.getStringTagAt(i))) {
                return true;
            }
        }
        return false;
    }

    private WorldServer world(int dim) {
        WorldServer w = DimensionManager.getWorld(dim);
        if (w == null) {
            DimensionManager.initDimension(dim);
            w = DimensionManager.getWorld(dim);
        }
        if (w == null) {
            throw new IllegalStateException("维度 " + dim + " 加载不了");
        }
        return w;
    }

    private static File chunkDir(WorldServer w) {
        ChunkProviderServer cp = w.getChunkProvider();
        if (cp.chunkLoader instanceof AnvilChunkLoader) {
            return ((AnvilChunkLoader) cp.chunkLoader).chunkSaveLocation;
        }
        File base = w.getSaveHandler().getWorldDirectory();
        String sub = w.provider.getSaveFolder();
        return sub == null ? base : new File(base, sub);
    }

    @Override
    public Map<PayItem, Long> dropsOnDisk(int dim, int cx, int cz, String tx) throws IOException {
        Map<PayItem, Long> out = new LinkedHashMap<>();
        DataInputStream in = RegionFileCache.getChunkInputStream(chunkDir(world(dim)), cx, cz);
        if (in == null) {
            return out;
        }
        NBTTagCompound c;
        try {
            c = CompressedStreamTools.read(in);
        } finally {
            in.close();
        }
        NBTTagList ents = c.getCompoundTag("Level").getTagList("Entities", 10);
        for (int i = 0; i < ents.tagCount(); i++) {
            NBTTagCompound e = ents.getCompoundTagAt(i);
            if (!tx.equals(e.getCompoundTag("ForgeData").getString(MARKER))) {
                continue;
            }
            ItemStack s = new ItemStack(e.getCompoundTag("Item"));
            PayItem item = PayItem.of(s);
            if (item != null) {
                out.merge(item, (long) s.getCount(), Long::sum);
            }
        }
        return out;
    }

    @Override
    public void persistChunk(int dim, int cx, int cz) throws IOException {
        WorldServer w = world(dim);
        Chunk chunk = w.getChunkProvider().getLoadedChunk(cx, cz);
        if (chunk != null) {
            try {
                w.getChunkProvider().chunkLoader.saveChunk(w, chunk);
            } catch (MinecraftException e) {
                throw new IOException(e);
            }
        }
        try {
            ThreadedFileIOBase.getThreadedIOInstance().waitForFinish();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IOException("等区块写盘时被打断", e);
        }
    }

    private static List<EntityItem> drops(WorldServer w, int cx, int cz, String tx) {
        List<EntityItem> out = new ArrayList<>();
        Chunk chunk = w.getChunk(cx, cz);
        for (net.minecraft.util.ClassInheritanceMultiMap<Entity> list : chunk.getEntityLists()) {
            for (EntityItem e : list.getByClass(EntityItem.class)) {
                if (!e.isDead && tx.equals(e.getEntityData().getString(MARKER))) {
                    out.add(e);
                }
            }
        }
        return out;
    }

    @Override
    public void killDrops(int dim, int cx, int cz, String tx) {
        for (EntityItem e : drops(world(dim), cx, cz, tx)) {
            e.setDead();
        }
    }

    @Override
    public void releaseDrops(int dim, int cx, int cz, String tx) {
        for (EntityItem e : drops(world(dim), cx, cz, tx)) {
            e.setPickupDelay(10);
            e.getEntityData().removeTag(MARKER);
        }
    }

    /** 主线程：玩家上线时去掉已经结束的那几笔的标记 */
    static void cleanMarkers(EntityPlayerMP p, java.util.function.Predicate<String> stillPending) {
        NBTTagCompound d = p.getEntityData();
        if (!d.hasKey(MARKER)) {
            return;
        }
        NBTTagList l = d.getTagList(MARKER, 8);
        NBTTagList keep = new NBTTagList();
        for (int i = 0; i < l.tagCount(); i++) {
            if (stillPending.test(l.getStringTagAt(i))) {
                keep.appendTag(new NBTTagString(l.getStringTagAt(i)));
            }
        }
        if (keep.tagCount() == 0) {
            d.removeTag(MARKER);
        } else {
            d.setTag(MARKER, keep);
        }
    }

    /** 一个在线玩家 */
    final class McPlayer implements PlayerPort {
        final EntityPlayerMP p;

        McPlayer(EntityPlayerMP p) {
            this.p = p;
        }

        @Override
        public UUID id() {
            return p.getUniqueID();
        }

        @Override
        public String name() {
            return p.getName();
        }

        /** 主背包（快捷栏在前）+ 副手 */
        private List<ItemStack> slots() {
            List<ItemStack> s = new ArrayList<>(p.inventory.mainInventory);
            s.addAll(p.inventory.offHandInventory);
            return s;
        }

        private void set(int i, ItemStack stack) {
            int main = p.inventory.mainInventory.size();
            if (i < main) {
                p.inventory.mainInventory.set(i, stack);
            } else {
                p.inventory.offHandInventory.set(i - main, stack);
            }
        }

        @Override
        public Map<PayItem, Long> counts() {
            Map<PayItem, Long> m = new LinkedHashMap<>();
            for (ItemStack s : slots()) {
                PayItem i = itemOf(s);
                if (i != null) {
                    m.merge(i, (long) s.getCount(), Long::sum);
                }
            }
            return m;
        }

        @Override
        public boolean take(Map<PayItem, Long> items) {
            Map<PayItem, Long> have = counts();
            for (Map.Entry<PayItem, Long> e : items.entrySet()) {
                if (have.getOrDefault(e.getKey(), 0L) < e.getValue()) {
                    return false;
                }
            }
            Map<PayItem, Long> left = new LinkedHashMap<>(items);
            List<ItemStack> slots = slots();
            // 先拿主背包里的（快捷栏后拿），最后副手
            int main = p.inventory.mainInventory.size();
            List<Integer> order = new ArrayList<>();
            for (int i = 9; i < main; i++) {
                order.add(i);
            }
            for (int i = 0; i < Math.min(9, main); i++) {
                order.add(i);
            }
            for (int i = main; i < slots.size(); i++) {
                order.add(i);
            }
            for (int i : order) {
                ItemStack s = slots.get(i);
                PayItem it = itemOf(s);
                Long need = it == null ? null : left.get(it);
                if (need == null || need <= 0) {
                    continue;
                }
                int n = (int) Math.min(need, s.getCount());
                ItemStack rest = s.copy();
                rest.shrink(n);
                set(i, rest.isEmpty() ? ItemStack.EMPTY : rest);
                left.put(it, need - n);
            }
            p.inventory.markDirty();
            p.inventoryContainer.detectAndSendChanges();
            return true;
        }

        @Override
        public Map<PayItem, Long> overflow(Map<PayItem, Long> take, Map<PayItem, Long> give) {
            List<ItemStack> sim = new ArrayList<>();
            Map<PayItem, Long> left = new LinkedHashMap<>(take);
            for (ItemStack s : slots()) {
                ItemStack c = s.copy();
                PayItem it = itemOf(c);
                Long need = it == null ? null : left.get(it);
                if (need != null && need > 0) {
                    int n = (int) Math.min(need, c.getCount());
                    c.shrink(n);
                    left.put(it, need - n);
                }
                sim.add(c.isEmpty() ? ItemStack.EMPTY : c);
            }
            return insert(sim, give, null);
        }

        @Override
        public Map<PayItem, Long> add(Map<PayItem, Long> items) {
            List<ItemStack> slots = slots();
            Map<PayItem, Long> rest = insert(slots, items, this::set);
            p.inventory.markDirty();
            p.inventoryContainer.detectAndSendChanges();
            return rest;
        }

        @Override
        public void mark(String tx) {
            NBTTagCompound d = p.getEntityData();
            NBTTagList l = d.getTagList(MARKER, 8);
            l.appendTag(new NBTTagString(tx));
            d.setTag(MARKER, l);
        }

        @Override
        public void unmark(String tx) {
            cleanMarkers(p, t -> !t.equals(tx));
        }

        @Override
        public void persist() {
            server.getWorld(0).getSaveHandler().getPlayerNBTManager().writePlayerData(p);
        }

        @Override
        public int dimension() {
            return p.dimension;
        }

        @Override
        public int chunkX() {
            return MathHelper.floor(p.posX) >> 4;
        }

        @Override
        public int chunkZ() {
            return MathHelper.floor(p.posZ) >> 4;
        }

        @Override
        public Map<PayItem, Long> drop(Map<PayItem, Long> items, String tx) {
            Map<PayItem, Long> out = new LinkedHashMap<>();
            WorldServer w = p.getServerWorld();
            for (Map.Entry<PayItem, Long> e : items.entrySet()) {
                long left = e.getValue();
                while (left > 0) {
                    ItemStack s = e.getKey().toStack(1);
                    if (s.isEmpty()) {
                        break;
                    }
                    int n = (int) Math.min(left, s.getMaxStackSize());
                    s.setCount(n);
                    EntityItem ent = new EntityItem(w, p.posX, p.posY + 0.2, p.posZ, s);
                    ent.motionX = 0;
                    ent.motionY = 0;
                    ent.motionZ = 0;
                    ent.setPickupDelay(HOLD_PICKUP);
                    ent.setOwner(p.getName());
                    ent.getEntityData().setString(MARKER, tx);
                    if (w.spawnEntity(ent)) {
                        out.merge(e.getKey(), (long) n, Long::sum);
                    }
                    left -= n;
                }
            }
            return out;
        }
    }

    /** 这堆是哪种物品；记不了账（NBT 读不回原样）的为 null */
    static PayItem itemOf(ItemStack s) {
        if (s == null || s.isEmpty()) {
            return null;
        }
        try {
            return PayItem.of(s);
        } catch (IllegalArgumentException e) {
            return null;
        }
    }

    /** 往格子里放（先叠到能叠的，再放空格；副手只叠不放），返回放不下的。{@code setter} 为 null 时只算 */
    static Map<PayItem, Long> insert(List<ItemStack> slots, Map<PayItem, Long> items, java.util.function.BiConsumer<Integer, ItemStack> setter) {
        Map<PayItem, Long> rest = new LinkedHashMap<>();
        int mainSize = Math.max(0, slots.size() - 1);
        for (Map.Entry<PayItem, Long> e : items.entrySet()) {
            ItemStack proto = e.getKey().toStack(1);
            long left = e.getValue();
            if (proto.isEmpty()) {
                rest.put(e.getKey(), left);
                continue;
            }
            int max = proto.getMaxStackSize();
            for (int i = 0; i < slots.size() && left > 0; i++) {
                ItemStack s = slots.get(i);
                if (!s.isEmpty() && ItemHandlerHelper.canItemStacksStack(s, proto) && s.getCount() < Math.min(max, s.getMaxStackSize())) {
                    int n = (int) Math.min(left, Math.min(max, s.getMaxStackSize()) - s.getCount());
                    ItemStack ns = s.copy();
                    ns.grow(n);
                    slots.set(i, ns);
                    if (setter != null) {
                        setter.accept(i, ns);
                    }
                    left -= n;
                }
            }
            for (int i = 0; i < mainSize && left > 0; i++) {
                if (slots.get(i).isEmpty()) {
                    int n = (int) Math.min(left, max);
                    ItemStack ns = proto.copy();
                    ns.setCount(n);
                    slots.set(i, ns);
                    if (setter != null) {
                        setter.accept(i, ns);
                    }
                    left -= n;
                }
            }
            if (left > 0) {
                rest.put(e.getKey(), left);
            }
        }
        return rest;
    }

    /** 测试用：NBT 里的标记 */
    static NBTBase markerTag(NBTTagCompound forgeData) {
        return forgeData.getTag(MARKER);
    }
}
