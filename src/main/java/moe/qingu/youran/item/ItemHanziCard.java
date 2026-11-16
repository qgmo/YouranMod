package moe.qingu.youran.item;

import moe.qingu.youran.api.pcb.BoardNbt;
import net.minecraft.client.util.ITooltipFlag;
import net.minecraft.item.ItemStack;
import net.minecraft.world.World;
import net.minecraftforge.fml.relauncher.Side;
import net.minecraftforge.fml.relauncher.SideOnly;

import javax.annotation.Nullable;
import java.util.List;

/**
 * 汉卡（外设，种类 {@code hanzi}）：服主 11-16 起在主板线上做——打好孔的汉卡光板 + 字库 ROM 1–4，工程师装配台“扩展卡”蓝图，
 * 一次一块，四片字库 ROM 的品质数据记进 NBT（{@link BoardNbt}，同主板）。自然生成的普通电脑带的汉卡没有这些数据。
 */
public class ItemHanziCard extends ItemPeripheral {
    public ItemHanziCard(String name, String kind) {
        super(name, kind);
    }

    @SideOnly(Side.CLIENT)
    @Override
    public void addInformation(ItemStack stack, @Nullable World world, List<String> lines, ITooltipFlag flag) {
        super.addInformation(stack, world, lines, flag);
        if (stack.hasTagCompound() && stack.getTagCompound().hasKey(BoardNbt.TAG, 10)) {
            BoardTooltip.lines(stack.getTagCompound().getCompoundTag(BoardNbt.TAG), lines, flag);
        }
    }
}
