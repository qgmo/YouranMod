package moe.qingu.youran.recipe;

import moe.qingu.youran.YouranMod;
import net.minecraft.item.crafting.IRecipe;
import net.minecraftforge.event.RegistryEvent;
import net.minecraftforge.fml.common.Mod;
import net.minecraftforge.fml.common.eventhandler.EventPriority;
import net.minecraftforge.fml.common.eventhandler.SubscribeEvent;

/**
 * 内置的装罐机反应配方在配方注册事件里登记（两端）：所有模组的预初始化之后，CraftTweaker 跑脚本之前
 * （它在同一事件的 LOWEST 优先级跑，这里用 HIGH），脚本才删得掉、改得了。
 */
@Mod.EventBusSubscriber(modid = YouranMod.MODID)
public final class BuiltinRecipes {
    private BuiltinRecipes() {
    }

    @SubscribeEvent(priority = EventPriority.HIGH)
    public static void registerRecipes(RegistryEvent.Register<IRecipe> event) {
        CannerReactions.registerBuiltins();
    }
}
