package moe.qingu.youran.compat.immersivepetroleum;

import blusunrize.immersiveengineering.api.DimensionChunkCoords;
import flaxbeard.immersivepetroleum.api.crafting.PumpjackHandler;
import moe.qingu.youran.logic.ip.ReservoirCache;
import net.minecraft.world.WorldServer;
import net.minecraftforge.common.DimensionManager;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;

/**
 * 沉浸原油油井信息缓存按需清理（审查 IP-L1），由 {@code mixin.immersivepetroleum} 调用。
 *
 * <p>沉浸原油的 {@code PumpjackHandler.oilCache} 记着每个查过储层的区块（泵机、取芯钻、{@code /ip reservoir}），只增不减，
 * 每次存档整块写进 {@code ImmersivePetroleum-SaveData}。这里在存档时：
 * <ul>
 *     <li>这次开服里现算出来、之后没动过的条目（{@link ReservoirCache#regenerable}）不写进存档：下次开服再查时按同样的种子、
 *     坐标算出同样的一份；</li>
 *     <li>这类条目所在区块已经卸载的，同时从内存里删掉（区块再加载、再查时重新算）；</li>
 *     <li>残余产量的计时表 {@code timeCache} 删掉记的不是现在这一 tick 的条目（与没有这一条效果相同）。</li>
 * </ul>
 * 抽过油、被管理员改过（覆盖类型、储量、容量）的条目与从存档读回来的条目一律照旧保留、照旧存档，已有油井的储量不变。
 * 存档在服务端主线程上做。
 */
public final class ReservoirCacheHooks {
    private ReservoirCacheHooks() {
    }

    /** 沉浸原油现算出一份新的油井信息、放进缓存之前 */
    public static void generated(Object info) {
        if (info instanceof GeneratedReservoir) {
            ((GeneratedReservoir) info).youran$markGenerated();
        }
    }

    /** 存档时代替 {@code oilCache.entrySet()}：先清理，再给出要写进存档的条目 */
    public static Set<Map.Entry<DimensionChunkCoords, PumpjackHandler.OilWorldInfo>> entriesToSave(
            Map<DimensionChunkCoords, PumpjackHandler.OilWorldInfo> cache) {
        ReservoirCache.removeIf(cache, e -> regenerable(e.getValue()) && !chunkLoaded(e.getKey()));
        ReservoirCache.pruneTimes(PumpjackHandler.timeCache, ReservoirCacheHooks::worldTime);
        Map<DimensionChunkCoords, PumpjackHandler.OilWorldInfo> toSave = new LinkedHashMap<>();
        for (Map.Entry<DimensionChunkCoords, PumpjackHandler.OilWorldInfo> e : cache.entrySet()) {
            if (!regenerable(e.getValue())) {
                toSave.put(e.getKey(), e.getValue());
            }
        }
        return toSave.entrySet();
    }

    private static boolean regenerable(Object info) {
        return info instanceof GeneratedReservoir && ((GeneratedReservoir) info).youran$regenerable();
    }

    /** 条目所在区块是否已加载（沉浸原油的储层按区块算，坐标就是区块坐标） */
    private static boolean chunkLoaded(DimensionChunkCoords coords) {
        if (coords == null) {
            return false;
        }
        WorldServer world = DimensionManager.getWorld(coords.dimension);
        return world != null && world.getChunkProvider().getLoadedChunk(coords.x, coords.z) != null;
    }

    private static long worldTime(DimensionChunkCoords coords) {
        WorldServer world = coords == null ? null : DimensionManager.getWorld(coords.dimension);
        return world == null ? Long.MIN_VALUE : world.getTotalWorldTime();
    }
}
