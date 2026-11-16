package moe.qingu.youran.item;

import moe.qingu.youran.fluid.FluidPurity;
import moe.qingu.youran.logic.litho.LampGlass;
import moe.qingu.youran.logic.litho.LampSpec;
import moe.qingu.youran.logic.litho.LithoNumbers;
import net.minecraft.client.resources.I18n;
import net.minecraft.client.util.ITooltipFlag;
import net.minecraft.item.ItemStack;
import net.minecraft.nbt.NBTTagCompound;
import net.minecraft.world.World;
import net.minecraftforge.common.capabilities.ICapabilityProvider;
import net.minecraftforge.fml.relauncher.Side;
import net.minecraftforge.fml.relauncher.SideOnly;

import javax.annotation.Nullable;
import java.util.List;

/**
 * 带玻璃管数据的物品（{@link LampNbt}）：低档玻璃管、石英管、汞灯。悬停说明显示玻璃管种类、铸模或纯度；
 * 汞灯另显示气体、亮度、已用 / 寿命、汞，并能补汞（{@link LampMercuryHandler}：IC2 装罐机、沉浸工程灌装机等往里灌汞液体）。
 */
public class ItemLampPart extends ItemLangTooltip {
    private final boolean lamp;

    public ItemLampPart(String name, int maxStackSize, boolean lamp) {
        super(name, maxStackSize);
        this.lamp = lamp;
    }

    @Nullable
    @Override
    public ICapabilityProvider initCapabilities(ItemStack stack, @Nullable NBTTagCompound nbt) {
        return lamp ? new LampMercuryHandler(stack) : null;
    }

    @SideOnly(Side.CLIENT)
    @Override
    public void addInformation(ItemStack stack, @Nullable World world, List<String> lines, ITooltipFlag flag) {
        super.addInformation(stack, world, lines, flag);
        describe(LampNbt.read(stack.getTagCompound()), lamp, lines);
    }

    /** 玻璃管一行；是灯时再加亮度、已用 / 寿命、汞三行（光源方块的物品、界面也用） */
    @SideOnly(Side.CLIENT)
    public static void describe(LampSpec spec, boolean lamp, List<String> lines) {
        String tube = spec.glass == LampGlass.QUARTZ
                ? I18n.format("youranmod.lamp.quartz", FluidPurity.describe(spec.nines))
                : I18n.format("youranmod.lamp.glass", I18n.format("youranmod.wafer.mold." + (spec.mold == null ? "clay" : spec.mold.id)));
        if (lamp && spec.gas != null) {
            tube += I18n.format("youranmod.lamp.gas", I18n.format("youranmod.lamp.gas." + spec.gas.id));
        }
        lines.add(tube);
        if (lamp) {
            lines.add(I18n.format(spec.burntOut() ? "youranmod.lamp.burnt" : "youranmod.lamp.brightness", Math.round(spec.brightness() * 100),
                    spec.used, spec.life()));
            lines.add(I18n.format("youranmod.lamp.mercury", spec.mercury, LithoNumbers.LAMP_MERCURY));
        }
    }
}
