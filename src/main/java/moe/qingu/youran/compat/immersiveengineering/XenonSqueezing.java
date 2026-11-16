package moe.qingu.youran.compat.immersiveengineering;

import blusunrize.immersiveengineering.api.crafting.IngredientStack;
import blusunrize.immersiveengineering.api.crafting.SqueezerRecipe;
import moe.qingu.youran.YouranMod;
import moe.qingu.youran.logic.litho.LithoNumbers;
import moe.qingu.youran.registry.ModFluids;
import net.minecraft.item.Item;
import net.minecraft.item.ItemStack;
import net.minecraft.nbt.NBTTagCompound;
import net.minecraft.tileentity.TileEntity;
import net.minecraft.util.ResourceLocation;
import net.minecraftforge.fluids.FluidStack;
import net.minecraftforge.fml.common.registry.ForgeRegistries;

import java.util.Collections;
import java.util.List;
import java.util.Random;

/**
 * 氙（服主 11-16）：沉浸工程挤压机挤 IC2 枯竭燃料棒（铀、MOX 的单联、双联、四联，{@code ic2:nuclear} 元数据 11–16），
 * 每根随机出 {@value LithoNumbers#XENON_PER_ROD_MIN}–{@value LithoNumbers#XENON_PER_ROD_MAX} mB 液氙，
 * 棒子原样出来、NBT 里记“已脱气”（{@value #TAG}），不能再挤。IC2 热能离心机按元数据认枯竭燃料棒、不看多出来的 NBT，照样能分。
 * 挤压机的配方产物是写死的，所以随机量在 {@link Recipe#getActualFluidOutputs} 里给；JEI 显示的是最多的量。
 */
public final class XenonSqueezing {
    /** 枯竭燃料棒上的“已脱气”标记 */
    public static final String TAG = "YouranDegassed";
    static final ResourceLocation NUCLEAR = new ResourceLocation("ic2", "nuclear");
    /** 枯竭燃料棒的元数据：铀单 / 双 / 四联、MOX 单 / 双 / 四联 */
    static final int[] DEPLETED = {11, 12, 13, 14, 15, 16};

    private XenonSqueezing() {
    }

    public static boolean degassed(ItemStack s) {
        return !s.isEmpty() && s.hasTagCompound() && s.getTagCompound().getBoolean(TAG);
    }

    public static void register() {
        Item nuclear = ForgeRegistries.ITEMS.getValue(NUCLEAR);
        if (nuclear == null || ModFluids.xenon == null) {
            return;
        }
        for (int meta : DEPLETED) {
            ItemStack rod = new ItemStack(nuclear, 1, meta);
            ItemStack out = rod.copy();
            NBTTagCompound tag = new NBTTagCompound();
            tag.setBoolean(TAG, true);
            out.setTagCompound(tag);
            // 按 NBT 比：没有标记的才认（已脱气的带着标记，对不上）
            SqueezerRecipe.recipeList.add(new Recipe(new FluidStack(ModFluids.xenon, LithoNumbers.XENON_PER_ROD_MAX), out,
                    new IngredientStack(rod).setUseNBT(true), LithoNumbers.XENON_SQUEEZER_ENERGY));
        }
        SqueezerRecipe again = SqueezerRecipe.findRecipe(degassedSample(nuclear));
        if (again != null) {
            YouranMod.LOG.error("已脱气的枯竭燃料棒在挤压机里还能找到配方：{}", again.fluidOutput);
        }
        YouranMod.LOG.info("已加沉浸工程挤压机配方：{} 种 IC2 枯竭燃料棒 → {}–{} mB 液氙（棒子标记已脱气）", DEPLETED.length,
                LithoNumbers.XENON_PER_ROD_MIN, LithoNumbers.XENON_PER_ROD_MAX);
    }

    static ItemStack degassedSample(Item nuclear) {
        ItemStack s = new ItemStack(nuclear, 1, DEPLETED[0]);
        NBTTagCompound tag = new NBTTagCompound();
        tag.setBoolean(TAG, true);
        s.setTagCompound(tag);
        return s;
    }

    /** 一次挤多少：{@code min}–{@code max} 随机（含两端） */
    public static int amount(Random r) {
        return LithoNumbers.XENON_PER_ROD_MIN + r.nextInt(LithoNumbers.XENON_PER_ROD_MAX - LithoNumbers.XENON_PER_ROD_MIN + 1);
    }

    /** 液氙的量随机的挤压机配方 */
    static final class Recipe extends SqueezerRecipe {
        Recipe(FluidStack fluidOutput, ItemStack itemOutput, Object itemInput, int energy) {
            super(fluidOutput, itemOutput, itemInput, energy);
        }

        @Override
        public List<FluidStack> getActualFluidOutputs(TileEntity tile) {
            Random r = tile != null && tile.getWorld() != null ? tile.getWorld().rand : new Random();
            return Collections.singletonList(new FluidStack(ModFluids.xenon, amount(r)));
        }
    }
}
