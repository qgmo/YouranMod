package moe.qingu.youran.mixin.immersivepetroleum;

import flaxbeard.immersivepetroleum.common.Config;
import flaxbeard.immersivepetroleum.common.EventHandler;
import moe.qingu.youran.compat.immersivepetroleum.RecipeUnlock;
import net.minecraft.entity.Entity;
import net.minecraft.entity.player.EntityPlayerMP;
import net.minecraftforge.common.util.FakePlayer;
import net.minecraftforge.event.entity.EntityJoinWorldEvent;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * 玩家进入世界时解锁沉浸原油配方，改用 {@link RecipeUnlock} 存好的配方表，不再每次扫整张合成表（审查性能 P1）。
 * 配置 {@code autounlock_recipes} 照旧生效。客户端的玩家原来也扫（解锁在客户端什么也不做），这里直接跳过。
 */
@Mixin(value = EventHandler.class, remap = false)
public abstract class IpEventHandlerMixin {
    @Inject(method = "onEntityJoiningWorld", at = @At("HEAD"), cancellable = true)
    private static void youran$unlockCached(EntityJoinWorldEvent event, CallbackInfo ci) {
        ci.cancel();
        Entity entity = event.getEntity();
        if (Config.IPConfig.Miscellaneous.autounlock_recipes && entity instanceof EntityPlayerMP && !(entity instanceof FakePlayer)) {
            RecipeUnlock.unlockFor((EntityPlayerMP) entity);
        }
    }
}
