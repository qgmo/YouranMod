package moe.qingu.youran.recipe;

import moe.qingu.youran.YouranMod;
import moe.qingu.youran.compat.alchemistry.AlchemistryCompat;
import net.minecraft.block.Block;
import net.minecraft.item.crafting.IRecipe;
import net.minecraftforge.event.RegistryEvent;
import net.minecraftforge.fml.common.Mod;
import net.minecraftforge.fml.common.eventhandler.EventPriority;
import net.minecraftforge.fml.common.eventhandler.SubscribeEvent;

/**
 * 内置配方的登记时机（两端）：
 * <ul>
 *     <li>方块注册事件（最早的注册事件，所有模组的预初始化之后）：在炼金化学里注册本模组的化合物。这时炼金化学的元素、化合物表已经建好，
 *     它的物品模型（模型事件）、按化合物表生成的配方（后初始化）、JEI 都还没用到化合物表；</li>
 *     <li>配方注册事件：装罐机反应配方、软盘加工链的工作台与熔炉配方（{@link FloppyRecipes}）。CraftTweaker 在同一事件的 LOWEST 优先级跑脚本，这里用 HIGH，脚本才删得掉、改得了。</li>
 * </ul>
 */
@Mod.EventBusSubscriber(modid = YouranMod.MODID)
public final class BuiltinRecipes {
    private BuiltinRecipes() {
    }

    @SubscribeEvent(priority = EventPriority.HIGHEST)
    public static void registerBlocks(RegistryEvent.Register<Block> event) {
        AlchemistryCompat.registerCompounds();
    }

    @SubscribeEvent(priority = EventPriority.HIGH)
    public static void registerRecipes(RegistryEvent.Register<IRecipe> event) {
        CannerReactions.registerBuiltins();
        FloppyRecipes.register(event.getRegistry());
        LithoRecipes.registerCrafting(event.getRegistry());
        PcbRecipes.registerCrafting(event.getRegistry());
        PositiveResistRecipes.registerCrafting(event.getRegistry());
        PeripheralRecipes.register(event.getRegistry());
    }
}
