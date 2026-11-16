package moe.qingu.youran.item;

import moe.qingu.youran.logic.litho.ChipModel;
import net.minecraft.entity.player.EntityPlayer;
import net.minecraft.item.ItemStack;
import net.minecraft.util.ActionResult;
import net.minecraft.util.EnumActionResult;
import net.minecraft.util.EnumHand;
import net.minecraft.world.World;

/**
 * 芯片设计图（服主 11-15）：每种型号一张，右键打开一个画面看版图（风格化的像素版图，自己画的），没有实际作用。
 * 也是“扩展卡”蓝图的合成材料。来源（服主 11-16）：MineBay 不限量（{@code compat.minebay.MineBayListings}）、失落的城市建筑箱子（{@code server.loot.LostCityLoot}）。
 */
public class ItemChipDesign extends ItemModelled implements moe.qingu.youran.api.lang.FormattedItemName {
    public ItemChipDesign() {
        super("chip_design");
    }

    @Override
    public ActionResult<ItemStack> onItemRightClick(World world, EntityPlayer player, EnumHand hand) {
        ItemStack stack = player.getHeldItem(hand);
        ChipModel m = model(stack);
        if (world.isRemote && m != null) {
            moe.qingu.youran.client.litho.ClientLitho.openLayout(m, false);
        }
        return new ActionResult<>(EnumActionResult.SUCCESS, stack);
    }
}
