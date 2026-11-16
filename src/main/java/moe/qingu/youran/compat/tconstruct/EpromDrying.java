package moe.qingu.youran.compat.tconstruct;

import moe.qingu.youran.item.ItemEprom;
import moe.qingu.youran.logic.pcb.PcbNumbers;
import moe.qingu.youran.registry.ModItems;
import net.minecraft.item.ItemStack;
import slimeknights.mantle.util.RecipeMatch;
import slimeknights.tconstruct.library.DryingRecipe;

/**
 * 匠魂晾干架晒 EPROM（服主 11-16：晒太阳，慢，几个游戏日）：烧过的 EPROM 晾 {@link PcbNumbers#DRYING_RACK_ERASE_TICKS} tick 后内容擦掉。
 * 晾干架做完时按 {@code TinkerRegistry.getDryingResult(输入)} 出东西，配方本身不知道输入，所以 {@link #matches} 记下刚对上的那块
 * （同汞灯补汞的浇铸台配方；只在服务端主线程用）。没烧过的 EPROM 不算这条配方（放上去直接到出口）。
 */
public class EpromDrying extends DryingRecipe {
    private ItemStack last = ItemStack.EMPTY;

    public EpromDrying() {
        super(RecipeMatch.of(new ItemStack(ModItems.eprom)), new ItemStack(ModItems.eprom), PcbNumbers.DRYING_RACK_ERASE_TICKS);
    }

    @Override
    public boolean matches(ItemStack input) {
        if (!ItemEprom.erasable(input)) {
            return false;
        }
        last = input.copy();
        return true;
    }

    @Override
    public ItemStack getResult() {
        return last.isEmpty() ? super.getResult() : ItemEprom.erased(last);
    }

    public static void register() {
        slimeknights.tconstruct.library.TinkerRegistry.addDryingRecipe(new EpromDrying());
    }
}
