package moe.qingu.youran.registry;

import moe.qingu.youran.fluid.FluidWithPurity;
import net.minecraft.item.EnumRarity;
import net.minecraft.util.ResourceLocation;
import net.minecraftforge.fluids.Fluid;
import net.minecraftforge.fluids.FluidRegistry;

/**
 * 流体。名字是存档、通用桶的 NBT（FluidName）与 CraftTweaker 脚本（{@code <liquid:fuild_mineral_water>}）里用的，不能改。
 * 数值照 1.3.4。
 */
public final class ModFluids {
    public static Fluid mineralWater;
    public static Fluid hydrargyrum;
    public static Fluid gallium;
    public static Fluid chlorine;
    /** 乙酸（服主 11-14）：没有流体方块（倒不到世界里），只有通用桶 */
    public static Fluid aceticAcid;
    /** 三氯氢硅（服主 11-15，芯片线“硅”）：纯度记在 NBT 里（见 fluid.FluidPurity），没有流体方块，只有通用桶 */
    public static Fluid trichlorosilane;
    /** 三氯氢硅氢气混合气（服主 11-15，晶圆第 6 步）：纯度随做它的三氯氢硅（NBT，见 fluid.FluidPurity），气体，没有流体方块，只有通用桶 */
    public static Fluid trichlorosilaneHydrogen;

    private ModFluids() {
    }

    /** 预初始化时调用（方块注册事件之前） */
    public static void register() {
        mineralWater = fluid("fuild_mineral_water", "mineral_water").setDensity(1200).setViscosity(1000);
        hydrargyrum = fluid("hydrargyrum_youran", "hydrargyrum").setDensity(13534).setViscosity(13000);
        gallium = fluid("gallium_youran", "gallium").setDensity(5910).setViscosity(2300);
        chlorine = fluid("chlorine_youran", "chlorine").setDensity(3).setViscosity(15000).setGaseous(true)
                .setRarity(EnumRarity.UNCOMMON).setUnlocalizedName("chlorine_youran");
        // 顺序同 1.3.4
        for (Fluid f : new Fluid[]{mineralWater, hydrargyrum, gallium, chlorine}) {
            FluidRegistry.registerFluid(f);
            FluidRegistry.addBucketForFluid(f);
        }
        aceticAcid = fluid("acetic_acid", "acetic_acid").setDensity(1049).setViscosity(1200);
        FluidRegistry.registerFluid(aceticAcid);
        FluidRegistry.addBucketForFluid(aceticAcid);
        // 密度 1.342 g/cm³；黏度约 0.33 mPa·s（水为 1000 时记 330）；沸点 31.8 ℃，常温下按液体算
        trichlorosilane = new FluidWithPurity("trichlorosilane", texture("trichlorosilane_still"), texture("trichlorosilane_flow"))
                .setDensity(1342).setViscosity(330).setLuminosity(0).setGaseous(false);
        FluidRegistry.registerFluid(trichlorosilane);
        FluidRegistry.addBucketForFluid(trichlorosilane);
        // 气体：按氢气过量的混合气算，密度取个小的正数（同本模组的氯气，不往上飘），黏度很低
        trichlorosilaneHydrogen = new FluidWithPurity("trichlorosilane_hydrogen", texture("trichlorosilane_hydrogen_still"),
                texture("trichlorosilane_hydrogen_flow")).setDensity(2).setViscosity(20).setLuminosity(0).setGaseous(true);
        FluidRegistry.registerFluid(trichlorosilaneHydrogen);
        FluidRegistry.addBucketForFluid(trichlorosilaneHydrogen);
    }

    private static ResourceLocation texture(String name) {
        return new ResourceLocation("youranmod", "blocks/" + name);
    }

    private static Fluid fluid(String name, String texture) {
        return new Fluid(name, new ResourceLocation("youranmod", "blocks/" + texture + "_still"),
                new ResourceLocation("youranmod", "blocks/" + texture + "_flow"))
                .setLuminosity(0).setGaseous(false);
    }
}
