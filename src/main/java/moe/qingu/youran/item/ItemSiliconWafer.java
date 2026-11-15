package moe.qingu.youran.item;

import moe.qingu.youran.fluid.FluidPurity;
import moe.qingu.youran.logic.purity.WaferMold;
import net.minecraft.client.resources.I18n;
import net.minecraft.client.util.ITooltipFlag;
import net.minecraft.item.Item;
import net.minecraft.item.ItemStack;
import net.minecraft.nbt.NBTTagCompound;
import net.minecraft.world.World;
import net.minecraftforge.fml.relauncher.Side;
import net.minecraftforge.fml.relauncher.SideOnly;

import javax.annotation.Nullable;
import java.util.List;

/**
 * 硅晶圆、高精硅晶圆（服主 11-15，芯片线第 8、9 步）：一个一组，NBT 记纯度（几个 9，同流体）与所用铸模的材质（{@link WaferNbt}），
 * 悬停说明里显示。纯度来自浇注用的混合气；清洗成高精硅晶圆时两样都照抄。
 *
 * <p>没有纯度标记的按 1 个 9 算（同流体）；没有铸模标记的不显示铸模，清洗时按粘土算（报废率高的那种）。
 */
public class ItemSiliconWafer extends ItemWithTooltip {
    public ItemSiliconWafer(String name, String tooltip) {
        super(name, 1, tooltip);
    }

    /** 这种晶圆，纯度 {@code nines} 个 9，铸模 {@code mold} */
    public static ItemStack make(Item item, int nines, @Nullable WaferMold mold) {
        ItemStack stack = new ItemStack(item);
        stack.setTagCompound(tag(nines, mold));
        return stack;
    }

    /** 晶圆的 NBT（见 {@link WaferNbt}） */
    public static NBTTagCompound tag(int nines, @Nullable WaferMold mold) {
        return WaferNbt.tag(nines, mold);
    }

    /** 晶圆的纯度（几个 9）；没有标记时 1 个 */
    public static int nines(ItemStack stack) {
        return WaferNbt.nines(stack.getTagCompound());
    }

    /** 晶圆用的铸模；没有标记（或认不出）时为 null */
    @Nullable
    public static WaferMold mold(ItemStack stack) {
        return WaferNbt.mold(stack.getTagCompound());
    }

    /** 把 {@code from} 的纯度与铸模照抄成 {@code item}（清洗成高精硅晶圆时用） */
    public static ItemStack copyAs(ItemStack from, Item item) {
        ItemStack stack = new ItemStack(item);
        stack.setTagCompound(WaferNbt.copy(from.getTagCompound()));
        return stack;
    }

    @SideOnly(Side.CLIENT)
    @Override
    public void addInformation(ItemStack stack, @Nullable World world, List<String> lines, ITooltipFlag flag) {
        super.addInformation(stack, world, lines, flag);
        lines.add(FluidPurity.describe(nines(stack)));
        WaferMold mold = mold(stack);
        if (mold != null) {
            lines.add(I18n.format("youranmod.wafer.mold", I18n.format("youranmod.wafer.mold." + mold.id)));
        }
    }
}
