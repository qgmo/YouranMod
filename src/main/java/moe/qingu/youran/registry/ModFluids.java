package moe.qingu.youran.registry;

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
    }

    private static Fluid fluid(String name, String texture) {
        return new Fluid(name, new ResourceLocation("youranmod", "blocks/" + texture + "_still"),
                new ResourceLocation("youranmod", "blocks/" + texture + "_flow"))
                .setLuminosity(0).setGaseous(false);
    }
}
