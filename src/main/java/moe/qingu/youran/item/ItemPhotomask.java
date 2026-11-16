package moe.qingu.youran.item;

import moe.qingu.youran.logic.litho.ChipModel;
import moe.qingu.youran.logic.litho.LithoNumbers;
import net.minecraft.client.resources.I18n;
import net.minecraft.client.util.ITooltipFlag;
import net.minecraft.entity.player.EntityPlayer;
import net.minecraft.item.ItemStack;
import net.minecraft.util.ActionResult;
import net.minecraft.util.EnumActionResult;
import net.minecraft.util.EnumHand;
import net.minecraft.world.World;
import net.minecraftforge.fml.relauncher.Side;
import net.minecraftforge.fml.relauncher.SideOnly;

import javax.annotation.Nullable;
import java.util.List;

/**
 * 掩模（服主 11-15）：一套掩模（一种芯片的全部层）做成一个物品，放进光刻机的掩模槽（只能手动放取）。固定型号，型号是物品元数据（服主 11-16），NBT 只记磨损
 * （{@code Wear}：用它做过几片，不随每片消耗、慢慢磨损，到 {@link LithoNumbers#MASK_LIFE} 不能再用）。右键可以欣赏版图。
 * 来源（MineBay 每月一两个、失落的城市战利品）这一阶段不接，只有物品本身。
 */
public class ItemPhotomask extends ItemModelled {
    public static final String TAG_WEAR = "Wear";

    public ItemPhotomask() {
        super("photomask");
    }

    public static int wear(ItemStack stack) {
        return stack.hasTagCompound() ? Math.max(0, stack.getTagCompound().getInteger(TAG_WEAR)) : 0;
    }

    public static boolean wornOut(ItemStack stack) {
        return wear(stack) >= LithoNumbers.MASK_LIFE;
    }

    public static void addWear(ItemStack stack) {
        if (!stack.hasTagCompound()) {
            stack.setTagCompound(new net.minecraft.nbt.NBTTagCompound());
        }
        stack.getTagCompound().setInteger(TAG_WEAR, wear(stack) + 1);
    }

    @Override
    public ActionResult<ItemStack> onItemRightClick(World world, EntityPlayer player, EnumHand hand) {
        ItemStack stack = player.getHeldItem(hand);
        ChipModel m = model(stack);
        if (world.isRemote && m != null) {
            moe.qingu.youran.client.litho.ClientLitho.openLayout(m, true);
        }
        return new ActionResult<>(EnumActionResult.SUCCESS, stack);
    }

    @SideOnly(Side.CLIENT)
    @Override
    public void addInformation(ItemStack stack, @Nullable World world, List<String> lines, ITooltipFlag flag) {
        super.addInformation(stack, world, lines, flag);
        ChipModel m = model(stack);
        if (m != null) {
            lines.add(I18n.format("youranmod.mask.node", String.valueOf(m.nodeUm), m.layers));
        }
        int wear = wear(stack);
        lines.add(I18n.format(wornOut(stack) ? "youranmod.mask.worn_out" : "youranmod.mask.wear", wear, LithoNumbers.MASK_LIFE));
    }
}
