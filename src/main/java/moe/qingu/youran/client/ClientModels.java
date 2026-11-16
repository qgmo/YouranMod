package moe.qingu.youran.client;

import moe.qingu.youran.YouranMod;
import moe.qingu.youran.registry.ModBlocks;
import moe.qingu.youran.registry.ModFluids;
import moe.qingu.youran.registry.ModItems;
import net.minecraft.block.Block;
import net.minecraft.block.state.IBlockState;
import net.minecraft.client.renderer.block.model.ModelBakery;
import net.minecraft.client.renderer.block.model.ModelResourceLocation;
import net.minecraft.client.renderer.block.statemap.StateMapperBase;
import net.minecraft.item.Item;
import net.minecraftforge.client.event.ModelRegistryEvent;
import net.minecraftforge.client.event.TextureStitchEvent;
import net.minecraftforge.client.model.ModelLoader;
import net.minecraftforge.fml.common.Mod;
import net.minecraftforge.fml.common.eventhandler.SubscribeEvent;
import net.minecraftforge.fluids.Fluid;
import net.minecraftforge.fml.relauncher.Side;

/**
 * 物品与流体方块的模型（只在客户端）。同 1.3.4：
 * <ul>
 *   <li>普通物品、方块物品用 {@code youranmod:<注册名>#inventory}；</li>
 *   <li>四种流体的方块与物品都用 blockstates 里 forge:fluid 的变种 {@code youranmod:<注册名>#<注册名>}；</li>
 *   <li>主城传送门的物品形式没有登记模型（按默认找到 blockstates 里的 inventory 变种）。</li>
 * </ul>
 * 没有流体方块的流体（乙酸、三氯氢硅）没有模型引用它们的贴图，Forge 不会把贴图拼进方块贴图集，
 * 通用桶、罐子界面、JEI 里就显示成紫黑格子：在拼贴图时自己登记（{@link #stitchFluids}）。
 */
@Mod.EventBusSubscriber(value = Side.CLIENT, modid = YouranMod.MODID)
public final class ClientModels {
    private ClientModels() {
    }

    @SubscribeEvent
    public static void registerModels(ModelRegistryEvent event) {
        for (Item item : ModItems.all()) {
            Block block = Block.getBlockFromItem(item);
            if (block == ModBlocks.mainCityPortal) {
                continue;
            }
            if (block == ModBlocks.mineralWater || block == ModBlocks.hydrargyrum || block == ModBlocks.gallium || block == ModBlocks.chlorine) {
                fluid(block, item);
                continue;
            }
            if (item instanceof moe.qingu.youran.item.MetaModelled) {
                moe.qingu.youran.item.MetaModelled m = (moe.qingu.youran.item.MetaModelled) item;
                for (int meta : m.modelMetas()) {
                    ModelLoader.setCustomModelResourceLocation(item, meta,
                            new ModelResourceLocation(new net.minecraft.util.ResourceLocation(YouranMod.MODID, m.modelName(meta)), "inventory"));
                }
                continue;
            }
            ModelLoader.setCustomModelResourceLocation(item, 0, new ModelResourceLocation(item.getRegistryName(), "inventory"));
        }
    }

    @SubscribeEvent
    public static void stitchFluids(TextureStitchEvent.Pre event) {
        for (Fluid fluid : new Fluid[]{ModFluids.aceticAcid, ModFluids.trichlorosilane, ModFluids.trichlorosilaneHydrogen}) {
            if (fluid != null) {
                event.getMap().registerSprite(fluid.getStill());
                event.getMap().registerSprite(fluid.getFlowing());
            }
        }
        for (Fluid fluid : ModFluids.pcbFluids()) {
            if (fluid != null) {
                event.getMap().registerSprite(fluid.getStill());
                event.getMap().registerSprite(fluid.getFlowing());
            }
        }
        for (Fluid fluid : ModFluids.lithoFluids()) {
            if (fluid != null) {
                event.getMap().registerSprite(fluid.getStill());
                event.getMap().registerSprite(fluid.getFlowing());
            }
        }
    }

    private static void fluid(Block block, Item item) {
        String name = String.valueOf(block.getRegistryName());
        ModelResourceLocation model = new ModelResourceLocation(name, block.getRegistryName().getPath());
        // 同 1.3.4：把这个物品的模型变种登记成空表，Forge 就不会再去找默认的 <注册名>#inventory（找不到会在日志里报错）
        ModelBakery.registerItemVariants(item);
        ModelLoader.setCustomMeshDefinition(item, stack -> model);
        ModelLoader.setCustomStateMapper(block, new StateMapperBase() {
            @Override
            protected ModelResourceLocation getModelResourceLocation(IBlockState state) {
                return model;
            }
        });
    }
}
