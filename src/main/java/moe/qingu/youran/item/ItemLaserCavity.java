package moe.qingu.youran.item;

import moe.qingu.youran.fluid.FluidPurity;
import moe.qingu.youran.logic.litho.DuvNumbers;
import moe.qingu.youran.logic.litho.ExcimerSpec;
import moe.qingu.youran.registry.ModFluids;
import net.minecraft.client.resources.I18n;
import net.minecraft.client.util.ITooltipFlag;
import net.minecraft.creativetab.CreativeTabs;
import net.minecraft.item.ItemStack;
import net.minecraft.nbt.NBTTagCompound;
import net.minecraft.util.EnumFacing;
import net.minecraft.util.NonNullList;
import net.minecraft.world.World;
import net.minecraftforge.common.capabilities.Capability;
import net.minecraftforge.common.capabilities.ICapabilityProvider;
import net.minecraftforge.fluids.FluidStack;
import net.minecraftforge.fluids.capability.CapabilityFluidHandler;
import net.minecraftforge.fluids.capability.FluidTankProperties;
import net.minecraftforge.fluids.capability.IFluidHandlerItem;
import net.minecraftforge.fluids.capability.IFluidTankProperties;
import net.minecraftforge.fml.relauncher.Side;
import net.minecraftforge.fml.relauncher.SideOnly;

import javax.annotation.Nullable;
import java.util.List;

/**
 * ArF 激光腔（服主 11-16）：沉浸工程工程师装配台“光源”蓝图做出来（空的），再分别灌气：
 * <ul>
 *     <li>氩：IC2 流体装罐机或沉浸工程灌装机，按 Forge 流体容器往里灌，每次只收 {@value DuvNumbers#ARGON_PER_FILL} mB 液氩，最多
 *     {@value DuvNumbers#ARGON_MAX_MB} mB，满了不再收（{@link ArgonHandler}）；</li>
 *     <li>氟、氖：IC2 固体装罐机，每次放一个炼金化学元素（{@code compat.ic2.GasFillHooks}）。</li>
 * </ul>
 * 数据见 {@link LaserNbt}；和沉浸工程泛光灯合成准分子激光光源，拆回来时数据带回来，可以补氟再合回去。
 */
public class ItemLaserCavity extends ItemLangTooltip {
    public ItemLaserCavity(String name) {
        super(name, 1);
    }

    public static ExcimerSpec spec(ItemStack s) {
        return LaserNbt.excimer(s.getTagCompound());
    }

    public ItemStack make(ExcimerSpec spec) {
        ItemStack s = new ItemStack(this);
        s.setTagCompound(LaserNbt.tag(spec));
        return s;
    }

    @Override
    public void getSubItems(CreativeTabs tab, NonNullList<ItemStack> items) {
        if (isInCreativeTab(tab)) {
            items.add(make(ExcimerSpec.empty(1)));
        }
    }

    @Nullable
    @Override
    public ICapabilityProvider initCapabilities(ItemStack stack, @Nullable NBTTagCompound nbt) {
        return new ArgonHandler(stack);
    }

    @SideOnly(Side.CLIENT)
    @Override
    public void addInformation(ItemStack stack, @Nullable World world, List<String> lines, ITooltipFlag flag) {
        super.addInformation(stack, world, lines, flag);
        describe(spec(stack), lines);
    }

    /** 窗口、三样气体的数量、预计输出刻度、已用 / 寿命（激光腔、准分子激光光源、光刻机界面共用） */
    @SideOnly(Side.CLIENT)
    public static void describe(ExcimerSpec s, List<String> lines) {
        lines.add(I18n.format("youranmod.laser.window", FluidPurity.describe(s.nines)));
        lines.add(I18n.format("youranmod.laser.gas", String.format(java.util.Locale.ROOT, "%.2f", s.fluorine()), s.neon, s.argonMb,
                DuvNumbers.ARGON_MAX_MB));
        lines.add(I18n.format("youranmod.laser.output", gauge(s.strength()), Math.round(s.strength() * 100)));
        lines.add(I18n.format(s.burntOut() ? "youranmod.laser.burnt" : "youranmod.laser.used", s.used, s.life()));
    }

    /** 十格的刻度条 */
    public static String gauge(double v) {
        int n = (int) Math.round(Math.max(0, Math.min(1, v)) * 10);
        StringBuilder b = new StringBuilder("§a");
        for (int i = 0; i < 10; i++) {
            if (i == n) {
                b.append("§8");
            }
            b.append('|');
        }
        return b.append("§7").toString();
    }

    /**
     * 灌氩：只收液氩，每次 {@value DuvNumbers#ARGON_PER_FILL} mB，到 {@value DuvNumbers#ARGON_MAX_MB} mB 为止；抽不出来。
     * “每次”= 一次灌装（服主 11-16：手动、每次 10 mB）：同一个处理器真灌过一次以后就不再收，IC2 装罐机据此当它“满了”、
     * 灌完 10 mB 就送到输出格（不然会在原位一直灌到 750 mB）；沉浸工程灌装机每过一个也只灌一次。
     */
    public static final class ArgonHandler implements IFluidHandlerItem, ICapabilityProvider {
        private final ItemStack stack;
        private boolean filled;

        ArgonHandler(ItemStack stack) {
            this.stack = stack;
        }

        @Override
        public IFluidTankProperties[] getTankProperties() {
            ExcimerSpec s = spec(stack);
            FluidStack content = s.argonMb > 0 && ModFluids.argon != null ? new FluidStack(ModFluids.argon, s.argonMb) : null;
            return new IFluidTankProperties[]{new FluidTankProperties(content, DuvNumbers.ARGON_MAX_MB, true, false)};
        }

        @Override
        public int fill(FluidStack resource, boolean doFill) {
            if (filled || resource == null || ModFluids.argon == null || resource.getFluid() != ModFluids.argon || stack.getCount() != 1) {
                return 0;
            }
            ExcimerSpec s = spec(stack);
            int n = s.argonFill(resource.amount);
            if (n > 0 && doFill) {
                stack.setTagCompound(LaserNbt.tag(s.withArgon(n)));
                filled = true;
            }
            return n;
        }

        @Nullable
        @Override
        public FluidStack drain(FluidStack resource, boolean doDrain) {
            return null;
        }

        @Nullable
        @Override
        public FluidStack drain(int maxDrain, boolean doDrain) {
            return null;
        }

        @Override
        public ItemStack getContainer() {
            return stack;
        }

        @Override
        public boolean hasCapability(Capability<?> capability, @Nullable EnumFacing facing) {
            return capability == CapabilityFluidHandler.FLUID_HANDLER_ITEM_CAPABILITY;
        }

        @Nullable
        @Override
        public <T> T getCapability(Capability<T> capability, @Nullable EnumFacing facing) {
            return capability == CapabilityFluidHandler.FLUID_HANDLER_ITEM_CAPABILITY ? CapabilityFluidHandler.FLUID_HANDLER_ITEM_CAPABILITY.cast(this) : null;
        }
    }
}
