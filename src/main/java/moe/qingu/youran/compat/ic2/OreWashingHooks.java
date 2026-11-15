package moe.qingu.youran.compat.ic2;

import ic2.api.recipe.Recipes;
import ic2.core.block.machine.tileentity.TileEntityOreWashing;
import moe.qingu.youran.YouranMod;
import moe.qingu.youran.item.ItemSiliconWafer;
import moe.qingu.youran.logic.purity.WashWater;
import moe.qingu.youran.logic.purity.WaferRules;
import moe.qingu.youran.registry.ModItems;
import net.minecraft.item.ItemStack;
import net.minecraft.nbt.NBTTagCompound;
import net.minecraftforge.fluids.Fluid;
import net.minecraftforge.fluids.FluidRegistry;
import net.minecraftforge.fluids.FluidStack;
import net.minecraftforge.fluids.FluidTank;
import net.minecraftforge.oredict.OreDictionary;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Random;
import java.util.Set;

/**
 * IC2 洗矿机（芯片线第 9 步，服主 11-15），由 {@code mixin.ic2.TileEntityOreWashingMixin} 调用。只有装了 IC2 时才会被加载。
 * <ul>
 *     <li>认的水：原来只认普通水，现在 {@link WashWater} 里登记的、整合包里有的水都认（流体罐、倒空容器的格子），
 *     洗别的矿物照原来的配方，用哪种水都一样；</li>
 *     <li>洗硅晶圆：配方是“硅晶圆 + {@value WaferRules#WASH_WATER} mB 水 → 高精硅晶圆”，交付时按罐里水的纯净率、
 *     晶圆的纯度与铸模算报废概率（{@link WaferRules#scrapChance}），报废了出报废硅晶圆。</li>
 * </ul>
 */
public final class OreWashingHooks {
    private OreWashingHooks() {
    }

    /** 罐子、容器格子认的流体：原来的（普通水）再加上整合包里有的别的水 */
    public static Fluid[] acceptedFluids(Fluid[] original) {
        Set<Fluid> fluids = new LinkedHashSet<>(Arrays.asList(original));
        for (String name : WashWater.rates().keySet()) {
            Fluid f = FluidRegistry.getFluid(name);
            if (f != null) {
                fluids.add(f);
            }
        }
        return fluids.toArray(new Fluid[0]);
    }

    /**
     * 洗完一份时交付的东西（{@code TileEntityOreWashing#operateOnce} 开头）：输入是硅晶圆时按报废概率换成高精或报废硅晶圆；
     * 别的照原来的 {@code processResult}。这时输入还没扣、罐里的水还没抽。
     */
    public static Collection<ItemStack> washResult(TileEntityOreWashing washer, FluidTank tank, Collection<ItemStack> processResult) {
        ItemStack input = washer.inputSlot.get();
        if (input.isEmpty() || input.getItem() != ModItems.siliconWafer) {
            return processResult;
        }
        FluidStack water = tank.getFluid();
        int rate = WashWater.rate(water == null ? null : water.getFluid().getName());
        double chance = WaferRules.scrapChance(rate, ItemSiliconWafer.nines(input), ItemSiliconWafer.mold(input));
        Random rand = washer.getWorld() == null ? new Random() : washer.getWorld().rand;
        List<ItemStack> out = new ArrayList<>();
        out.add(rand.nextDouble() < chance ? new ItemStack(ModItems.scrapSiliconWafer)
                : ItemSiliconWafer.copyAs(input, ModItems.precisionSiliconWafer));
        return out;
    }

    /** 洗矿机配方：硅晶圆（任意 NBT）+ {@value WaferRules#WASH_WATER} mB 水 → 高精硅晶圆（交付时可能换成报废的，见 {@link #washResult}） */
    static void registerRecipe() {
        if (!WaferWasher.active()) {
            YouranMod.LOG.warn("IC2 洗矿机的 Mixin 没有生效，不加洗硅晶圆的配方（没有 Mixin 时洗出来不会报废）");
            return;
        }
        NBTTagCompound meta = new NBTTagCompound();
        meta.setInteger("amount", WaferRules.WASH_WATER);
        // 元数据写通配，IC2 就不比 NBT（晶圆都带纯度标记）
        ItemStack wafer = new ItemStack(ModItems.siliconWafer, 1, OreDictionary.WILDCARD_VALUE);
        if (!Recipes.oreWashing.addRecipe(Recipes.inputFactory.forStack(wafer), meta, false, new ItemStack(ModItems.precisionSiliconWafer))) {
            YouranMod.LOG.error("IC2 洗矿机配方（硅晶圆 → 高精硅晶圆）没加上：已有输入相同的配方");
        } else {
            YouranMod.LOG.info("已加 IC2 洗矿机配方：硅晶圆 + {} mB 水 → 高精硅晶圆（按水的纯净率可能报废）", WaferRules.WASH_WATER);
        }
    }

    /** 整合包里有的洗矿用水（JEI 说明用） */
    public static List<String> presentWaters() {
        List<String> list = new ArrayList<>();
        for (String name : WashWater.rates().keySet()) {
            if (FluidRegistry.isFluidRegistered(name)) {
                list.add(name);
            }
        }
        return Collections.unmodifiableList(list);
    }
}
