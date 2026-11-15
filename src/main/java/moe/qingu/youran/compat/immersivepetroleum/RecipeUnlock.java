package moe.qingu.youran.compat.immersivepetroleum;

import net.minecraft.entity.player.EntityPlayerMP;
import net.minecraft.init.Blocks;
import net.minecraft.item.Item;
import net.minecraft.item.crafting.CraftingManager;
import net.minecraft.item.crafting.IRecipe;
import net.minecraft.util.ResourceLocation;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * 沉浸原油的配方自动解锁（{@code autounlock_recipes}，审查性能 P1）。原来玩家每次进入世界（登录、换维度、重生）
 * 都把整张合成表（整合包里上万条）扫一遍、逐条拼名字比前缀，再把沉浸原油的配方全部发一遍解锁。
 *
 * <p>这里第一次用时照原来的条件挑出沉浸原油的配方存起来（合成表的条数变了才重挑），每次进入世界只看这些配方里
 * 玩家还没解锁的，有才解锁。解锁的配方与原来相同；已经全解锁的玩家换维度时只查几十个配方，不再扫整张表、不再发空的解锁包。
 * 只在服务端主线程上用。
 */
public final class RecipeUnlock {
    private static final String PREFIX = "immersivepetroleum";
    private static List<IRecipe> cached;
    private static int cachedRegistrySize = -1;

    private RecipeUnlock() {
    }

    /** 沉浸原油的配方：条件同原来 {@code EventHandler.onEntityJoiningWorld}（名字以 immersivepetroleum 开头、长于 18 个字、产物不是空气） */
    public static List<IRecipe> recipes() {
        int size = CraftingManager.REGISTRY.getKeys().size();
        if (cached == null || size != cachedRegistrySize) {
            List<IRecipe> list = new ArrayList<>();
            Item air = Item.getItemFromBlock(Blocks.AIR);
            for (IRecipe recipe : CraftingManager.REGISTRY) {
                ResourceLocation id = recipe.getRegistryName();
                String name = id == null ? "" : id.toString();
                if (name.length() <= PREFIX.length() || !name.startsWith(PREFIX) || recipe.getRecipeOutput().getItem() == air) {
                    continue;
                }
                list.add(recipe);
            }
            cached = Collections.unmodifiableList(list);
            cachedRegistrySize = size;
        }
        return cached;
    }

    /** 给玩家解锁还没解锁的沉浸原油配方；都解锁过了就什么也不做 */
    public static void unlockFor(EntityPlayerMP player) {
        List<IRecipe> missing = null;
        for (IRecipe recipe : recipes()) {
            if (recipe.isDynamic() || player.getRecipeBook().isUnlocked(recipe)) {
                continue;
            }
            if (missing == null) {
                missing = new ArrayList<>();
            }
            missing.add(recipe);
        }
        if (missing != null) {
            player.unlockRecipes(missing);
        }
    }
}
