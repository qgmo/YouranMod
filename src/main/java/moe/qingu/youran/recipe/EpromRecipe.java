package moe.qingu.youran.recipe;

import moe.qingu.youran.item.ItemEprom;
import moe.qingu.youran.item.ItemModelled;
import moe.qingu.youran.logic.litho.ChipModel;
import moe.qingu.youran.registry.ModItems;
import net.minecraft.inventory.InventoryCrafting;
import net.minecraft.item.ItemStack;
import net.minecraft.util.ResourceLocation;
import net.minecraftforge.oredict.ShapelessOreRecipe;

/**
 * EPROM（服主 11-16）：光刻机出来的 2764 芯片本体 + 做汞灯用的玻璃管（低档玻璃管或石英管）无序合成 → 空白的 EPROM，
 * 芯片的品质数据、玻璃管的档次（只影响烧录速度）都带过去（{@link ItemEprom#make}）。
 */
public class EpromRecipe extends ShapelessOreRecipe {
    public EpromRecipe(ResourceLocation id) {
        super(id, new ItemStack(ModItems.eprom), ModItems.chip.make(ChipModel.EPROM_2764),
                new net.minecraftforge.common.crafting.CompoundIngredient(java.util.Arrays.asList(
                        net.minecraft.item.crafting.Ingredient.fromStacks(new ItemStack(ModItems.glassTube, 1, net.minecraftforge.oredict.OreDictionary.WILDCARD_VALUE)),
                        net.minecraft.item.crafting.Ingredient.fromStacks(new ItemStack(ModItems.quartzTube, 1, net.minecraftforge.oredict.OreDictionary.WILDCARD_VALUE)))) {
                });
        setRegistryName(id);
    }

    @Override
    public ItemStack getCraftingResult(InventoryCrafting inv) {
        ItemStack chip = ItemStack.EMPTY;
        ItemStack tube = ItemStack.EMPTY;
        for (int i = 0; i < inv.getSizeInventory(); i++) {
            ItemStack s = inv.getStackInSlot(i);
            if (s.getItem() == ModItems.chip && ItemModelled.model(s) == ChipModel.EPROM_2764) {
                chip = s;
            } else if (s.getItem() == ModItems.glassTube || s.getItem() == ModItems.quartzTube) {
                tube = s;
            }
        }
        return chip.isEmpty() || tube.isEmpty() ? ItemStack.EMPTY : ItemEprom.make(ModItems.eprom, chip, tube);
    }

    @Override
    public boolean isDynamic() {
        return true;
    }
}
