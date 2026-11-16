package moe.qingu.youran.recipe;

import moe.qingu.youran.item.HandPunch;
import net.minecraft.inventory.InventoryCrafting;
import net.minecraft.item.ItemStack;
import net.minecraft.util.NonNullList;
import net.minecraft.util.ResourceLocation;
import net.minecraftforge.oredict.ShapelessOreRecipe;

/**
 * 工作台里用 SCP 模组的螺丝刀手工冲孔（服主 11-16：光板打孔、软盘套冲孔）：要冲的东西 + 螺丝刀（无序）→ 冲好的；
 * 螺丝刀没有耐久，原样还回；有概率冲坏，拿到手时才定（{@link HandPunch}）。照 {@link ShapelessOreRecipe} 写，JEI 照常显示。
 */
public class ScrewdriverPunch extends ShapelessOreRecipe {
    private final int brokenMeta;
    private final double chance;
    private final ItemStack screwdriver;

    public ScrewdriverPunch(ResourceLocation id, ItemStack input, ItemStack screwdriver, ItemStack output, int brokenMeta, double chance) {
        super(id, output, input, screwdriver);
        this.brokenMeta = brokenMeta;
        this.chance = chance;
        this.screwdriver = screwdriver;
        setRegistryName(id);
    }

    @Override
    public ItemStack getCraftingResult(InventoryCrafting inv) {
        return HandPunch.mark(super.getCraftingResult(inv), brokenMeta, chance);
    }

    @Override
    public NonNullList<ItemStack> getRemainingItems(InventoryCrafting inv) {
        NonNullList<ItemStack> left = super.getRemainingItems(inv);
        for (int i = 0; i < inv.getSizeInventory(); i++) {
            ItemStack s = inv.getStackInSlot(i);
            if (!s.isEmpty() && s.getItem() == screwdriver.getItem()) {
                ItemStack back = s.copy();
                back.setCount(1);
                left.set(i, back);
            }
        }
        return left;
    }
}
