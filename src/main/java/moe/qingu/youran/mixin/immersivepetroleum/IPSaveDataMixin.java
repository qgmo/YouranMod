package moe.qingu.youran.mixin.immersivepetroleum;

import blusunrize.immersiveengineering.api.DimensionChunkCoords;
import flaxbeard.immersivepetroleum.api.crafting.PumpjackHandler;
import flaxbeard.immersivepetroleum.common.IPSaveData;
import moe.qingu.youran.compat.immersivepetroleum.ReservoirCacheHooks;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Redirect;

import java.util.HashMap;
import java.util.Map;
import java.util.Set;

/**
 * 存档写油井信息时先清理缓存，只写需要记着的条目（审查 IP-L1，见 {@link ReservoirCacheHooks}）。
 * 存档格式不变，读档照沉浸原油原来的。
 */
@Mixin(value = IPSaveData.class, remap = false)
public abstract class IPSaveDataMixin {
    @Redirect(method = "writeToNBT", remap = true, at = @At(value = "INVOKE", remap = false,
            target = "Ljava/util/HashMap;entrySet()Ljava/util/Set;"))
    private Set<Map.Entry<DimensionChunkCoords, PumpjackHandler.OilWorldInfo>> youran$entriesToSave(
            HashMap<DimensionChunkCoords, PumpjackHandler.OilWorldInfo> cache) {
        return ReservoirCacheHooks.entriesToSave(cache);
    }
}
