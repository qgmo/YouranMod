package moe.qingu.youran.mixin.immersivepetroleum;

import flaxbeard.immersivepetroleum.api.crafting.PumpjackHandler;
import moe.qingu.youran.compat.immersivepetroleum.ReservoirCacheHooks;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Redirect;

import java.util.HashMap;

/**
 * 沉浸原油现算出一份油井信息、放进缓存时记下“这是现算的”（审查 IP-L1，见 {@link ReservoirCacheHooks}）。
 * {@code getOilWorldInfo} 里只有这一处往缓存里放。
 */
@Mixin(value = PumpjackHandler.class, remap = false)
public abstract class PumpjackHandlerMixin {
    @Redirect(method = "getOilWorldInfo", at = @At(value = "INVOKE",
            target = "Ljava/util/HashMap;put(Ljava/lang/Object;Ljava/lang/Object;)Ljava/lang/Object;"))
    private static Object youran$markGenerated(HashMap<Object, Object> cache, Object coords, Object info) {
        ReservoirCacheHooks.generated(info);
        return cache.put(coords, info);
    }
}
