package moe.qingu.youran.registry;

import moe.qingu.youran.fluid.FluidWithPurity;
import moe.qingu.youran.fluid.FluidArene;
import moe.qingu.youran.fluid.FluidPhotoresist;
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
    /** 光刻胶线（服主 11-15，负胶）：四氯化锡、芳烃（带叠氮基的是 NBT 变种）、甲酚、双叠氮感光剂、光刻胶（负 / 正是 NBT 变种）；都没有流体方块 */
    public static Fluid tinTetrachloride;
    public static Fluid arene;
    public static Fluid cresol;
    public static Fluid bisazideSensitizer;
    public static Fluid photoresist;
    /** 液氩（服主 11-16）：沉浸原油精馏塔分离 IC2 空气得到，87 K，没有流体方块 */
    public static Fluid argon;
    /** 液氙（服主 11-16）：沉浸工程挤压机挤 IC2 枯竭燃料棒得到，165 K，没有流体方块 */
    public static Fluid xenon;

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
        // 光刻胶线：SnCl₄ 密度 2.226、黏度约 0.9 mPa·s；芳烃按二甲苯（0.86、0.6）；甲酚（1.03、约 10）；感光剂、光刻胶按溶在芳烃里的溶液估
        tinTetrachloride = noBlock(fluid("tin_tetrachloride", "tin_tetrachloride").setDensity(2226).setViscosity(900));
        arene = noBlock(new FluidArene("arene", texture("arene_still"), texture("arene_flow")).setDensity(860).setViscosity(600));
        cresol = noBlock(fluid("cresol", "cresol").setDensity(1030).setViscosity(10000));
        bisazideSensitizer = noBlock(fluid("bisazide_sensitizer", "bisazide_sensitizer").setDensity(1000).setViscosity(2000));
        photoresist = noBlock(new FluidPhotoresist("photoresist", texture("photoresist_still"), texture("photoresist_flow"))
                .setDensity(950).setViscosity(8000));
        argon = registerArgon();
        xenon = registerXenon();
    }

    /** 液氩：别的模组已经注册了 argon 时用它的 */
    private static Fluid registerArgon() {
        if (FluidRegistry.isFluidRegistered("argon")) {
            return FluidRegistry.getFluid("argon");
        }
        // 液氩密度 1.395 g/cm³、黏度约 0.25 mPa·s，沸点 87 K
        return noBlock(fluid("argon", "argon").setDensity(1395).setViscosity(250).setTemperature(87));
    }

    /** 液氙：别的模组已经注册了 xenon 时用它的 */
    private static Fluid registerXenon() {
        if (FluidRegistry.isFluidRegistered("xenon")) {
            return FluidRegistry.getFluid("xenon");
        }
        // 液氙密度 2.94 g/cm³、黏度约 0.5 mPa·s，沸点 165 K
        return noBlock(fluid("xenon", "xenon").setDensity(2940).setViscosity(500).setTemperature(165));
    }

    /** 光刻胶线的流体：都是液体，没有流体方块，只有通用桶 */
    public static Fluid[] lithoFluids() {
        return new Fluid[]{tinTetrachloride, arene, cresol, bisazideSensitizer, photoresist, argon, xenon};
    }

    private static Fluid noBlock(Fluid f) {
        f.setLuminosity(0).setGaseous(false);
        FluidRegistry.registerFluid(f);
        FluidRegistry.addBucketForFluid(f);
        return f;
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
