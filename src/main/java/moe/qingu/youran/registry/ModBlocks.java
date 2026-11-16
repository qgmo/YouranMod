package moe.qingu.youran.registry;

import moe.qingu.youran.block.BlockHardIce;
import moe.qingu.youran.block.BlockPrimogemOre;
import moe.qingu.youran.block.BlockSphaleriteOre;
import moe.qingu.youran.block.BlockToxicFluid;
import moe.qingu.youran.block.BlockYouranLogo;
import moe.qingu.youran.block.FluidContact;
import moe.qingu.youran.block.MaterialGas;
import moe.qingu.youran.world.portal.BlockMainCityPortal;
import net.minecraft.block.Block;
import net.minecraft.block.material.Material;
import net.minecraftforge.fluids.BlockFluidClassic;
import net.minecraftforge.registries.IForgeRegistry;

/**
 * 方块。注册名是存档、CraftTweaker 脚本、TAN 配置、Dynmap renderdata 里用的，一个都不能改。
 */
public final class ModBlocks {
    public static Block youranLogo;
    public static BlockMainCityPortal mainCityPortal;
    public static Block hardIce;
    public static Block primogemOre;
    public static Block sphaleriteOre;
    public static BlockFluidClassic mineralWater;
    public static BlockFluidClassic hydrargyrum;
    public static BlockFluidClassic gallium;
    public static BlockFluidClassic chlorine;
    /** 光刻机（服主 11-16）：对准台（锤子触发格、成形后的主方块）、汞灯光源、成形后的光刻机方块（没有物品形式） */
    public static Block alignmentTable;
    public static Block mercuryLightSource;
    public static Block lithoPart;

    private ModBlocks() {
    }

    static void register(IForgeRegistry<Block> registry) {
        // 顺序同 1.3.4（只影响新世界里的数字 ID）
        youranLogo = new BlockYouranLogo();
        mainCityPortal = new BlockMainCityPortal();
        hardIce = new BlockHardIce();
        primogemOre = new BlockPrimogemOre();
        sphaleriteOre = new BlockSphaleriteOre();
        mineralWater = new BlockFluidClassic(ModFluids.mineralWater, Material.WATER);
        mineralWater.setRegistryName("fuild_mineral_water");
        mineralWater.setTranslationKey("fuild_mineral_water");
        hydrargyrum = new BlockToxicFluid(ModFluids.hydrargyrum, Material.WATER, "hydrargyrum_youran", FluidContact::mercury);
        gallium = new BlockToxicFluid(ModFluids.gallium, Material.WATER, "gallium_youran", FluidContact::gallium);
        chlorine = new BlockToxicFluid(ModFluids.chlorine, MaterialGas.GAS, "chlorine_youran", FluidContact::chlorine);
        registry.registerAll(youranLogo, mainCityPortal, hardIce, primogemOre, sphaleriteOre,
                mineralWater, hydrargyrum, gallium, chlorine);
        alignmentTable = new moe.qingu.youran.litho.BlockAlignmentTable("alignment_table");
        mercuryLightSource = new moe.qingu.youran.litho.BlockLightSource("mercury_light_source");
        lithoPart = new moe.qingu.youran.litho.BlockLithoPart("lithography_machine");
        registry.registerAll(alignmentTable, mercuryLightSource, lithoPart);
        net.minecraftforge.fml.common.registry.GameRegistry.registerTileEntity(moe.qingu.youran.litho.TileLightSource.class,
                new net.minecraft.util.ResourceLocation("youranmod", "mercury_light_source"));
        net.minecraftforge.fml.common.registry.GameRegistry.registerTileEntity(moe.qingu.youran.litho.TileLithoPart.class,
                new net.minecraft.util.ResourceLocation("youranmod", "lithography_part"));
        net.minecraftforge.fml.common.registry.GameRegistry.registerTileEntity(moe.qingu.youran.litho.TileLithoMaster.class,
                new net.minecraft.util.ResourceLocation("youranmod", "lithography_master"));
    }
}
