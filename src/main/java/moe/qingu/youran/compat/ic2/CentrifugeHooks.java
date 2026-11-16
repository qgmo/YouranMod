package moe.qingu.youran.compat.ic2;

import ic2.core.block.machine.tileentity.TileEntityCentrifuge;
import ic2.core.block.machine.tileentity.TileEntityStandardMachine;
import moe.qingu.youran.logic.litho.LithoNumbers;
import moe.qingu.youran.registry.ModItems;
import net.minecraft.item.ItemStack;
import net.minecraftforge.oredict.OreDictionary;

import java.util.ArrayList;
import java.util.Collection;
import java.util.List;

/**
 * IC2 热能离心机分铜矿时有几率多出 1 小撮钼粉（服主 11-16）。IC2 的离心机配方产物是固定的，所以在
 * {@code TileEntityStandardMachine#operateOnce} 开头（{@code mixin.ic2.TileEntityStandardMachineMixin}）按几率加上：
 * 输入是粉碎铜矿石（{@code crushedCopper}）{@value LithoNumbers#MO_CENTRIFUGE_CRUSHED}，纯净的（{@code crushedPurifiedCopper}）
 * {@value LithoNumbers#MO_CENTRIFUGE_PURIFIED}。输出格放不下钼粉时这次就不出（不挤掉原来的产物）。
 */
public final class CentrifugeHooks {
    private CentrifugeHooks() {
    }

    public static Collection<ItemStack> withByproduct(TileEntityStandardMachine<?, ?, ?> machine, Collection<ItemStack> result) {
        if (!(machine instanceof TileEntityCentrifuge) || machine.getWorld() == null || ModItems.smallMolybdenumDust == null) {
            return result;
        }
        ItemStack input = machine.inputSlot.get();
        float chance = hasOre(input, "crushedPurifiedCopper") ? LithoNumbers.MO_CENTRIFUGE_PURIFIED
                : hasOre(input, "crushedCopper") ? LithoNumbers.MO_CENTRIFUGE_CRUSHED : 0;
        if (chance <= 0 || machine.getWorld().rand.nextFloat() >= chance) {
            return result;
        }
        List<ItemStack> more = new ArrayList<>(result);
        more.add(new ItemStack(ModItems.smallMolybdenumDust));
        return machine.outputSlot.canAdd(more) ? more : result;
    }

    static boolean hasOre(ItemStack s, String ore) {
        if (s == null || s.isEmpty() || !OreDictionary.doesOreNameExist(ore)) {
            return false;
        }
        int id = OreDictionary.getOreID(ore);
        for (int i : OreDictionary.getOreIDs(s)) {
            if (i == id) {
                return true;
            }
        }
        return false;
    }
}
