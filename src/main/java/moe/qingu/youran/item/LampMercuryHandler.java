package moe.qingu.youran.item;

import moe.qingu.youran.logic.litho.LampSpec;
import moe.qingu.youran.logic.litho.LithoNumbers;
import moe.qingu.youran.registry.ModFluids;
import net.minecraft.item.ItemStack;
import net.minecraft.util.EnumFacing;
import net.minecraftforge.common.capabilities.Capability;
import net.minecraftforge.common.capabilities.ICapabilityProvider;
import net.minecraftforge.fluids.FluidStack;
import net.minecraftforge.fluids.capability.CapabilityFluidHandler;
import net.minecraftforge.fluids.capability.FluidTankProperties;
import net.minecraftforge.fluids.capability.IFluidHandlerItem;
import net.minecraftforge.fluids.capability.IFluidTankProperties;

import javax.annotation.Nullable;

/**
 * 汞灯的“汞”（服主 11-16）：只收本模组的汞液体，补到 {@link LithoNumbers#LAMP_MERCURY} mB，抽不出来。
 * IC2 装罐机（装液体）、沉浸工程灌装机都按 Forge 的流体容器往里灌；匠魂浇铸台另有配方（{@code recipe.LithoRecipes}）。
 * 汞记在灯的 NBT 里（{@link LampNbt#TAG_MERCURY}），不是一般流体容器的格式。
 */
public class LampMercuryHandler implements IFluidHandlerItem, ICapabilityProvider {
    private ItemStack stack;

    public LampMercuryHandler(ItemStack stack) {
        this.stack = stack;
    }

    LampSpec spec() {
        return LampNbt.read(stack.getTagCompound());
    }

    @Override
    public IFluidTankProperties[] getTankProperties() {
        LampSpec s = spec();
        FluidStack content = s.mercury > 0 && ModFluids.hydrargyrum != null ? new FluidStack(ModFluids.hydrargyrum, s.mercury) : null;
        return new IFluidTankProperties[]{new FluidTankProperties(content, LithoNumbers.LAMP_MERCURY, true, false)};
    }

    @Override
    public int fill(FluidStack resource, boolean doFill) {
        if (resource == null || resource.getFluid() != ModFluids.hydrargyrum || stack.getCount() != 1 || stack.getTagCompound() == null) {
            return 0;
        }
        LampSpec s = spec();
        if (s.gas == null) {
            return 0;
        }
        int n = Math.min(resource.amount, s.mercurySpace());
        if (n > 0 && doFill) {
            stack.setTagCompound(LampNbt.tag(s.withMercury(s.mercury + n)));
        }
        return Math.max(0, n);
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
