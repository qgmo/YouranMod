package moe.qingu.youran.client.litho;

import moe.qingu.youran.YouranMod;
import moe.qingu.youran.litho.BlockLithoPart;
import moe.qingu.youran.litho.TileLightSource;
import moe.qingu.youran.logic.litho.LithoShape;
import moe.qingu.youran.registry.ModBlocks;
import net.minecraft.block.state.IBlockState;
import net.minecraft.client.renderer.GlStateManager;
import net.minecraft.client.renderer.RenderGlobal;
import net.minecraft.client.renderer.block.model.ModelResourceLocation;
import net.minecraft.client.renderer.block.statemap.StateMapperBase;
import net.minecraft.entity.player.EntityPlayer;
import net.minecraft.util.ResourceLocation;
import net.minecraft.util.math.AxisAlignedBB;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.RayTraceResult;
import net.minecraftforge.client.event.DrawBlockHighlightEvent;
import net.minecraftforge.client.event.ModelBakeEvent;
import net.minecraftforge.client.event.ModelRegistryEvent;
import net.minecraftforge.client.event.TextureStitchEvent;
import net.minecraftforge.client.model.ModelLoader;
import net.minecraftforge.fml.client.registry.ClientRegistry;
import net.minecraftforge.fml.common.Loader;
import net.minecraftforge.fml.common.Mod;
import net.minecraftforge.fml.common.eventhandler.SubscribeEvent;
import net.minecraftforge.fml.relauncher.Side;

import java.util.List;

/**
 * 光刻机整机模型的客户端登记：所有状态映射到一个模型位置，烘焙时换成 {@link LithoBakedModel}；拼贴图时把模型用到的贴图拼进去
 * （沉浸工程的贴图按它注册的资源路径引用）；光源的发光与光束（{@link LightSourceRenderer}）；选取框按格画多个（照沉浸工程）。
 */
@Mod.EventBusSubscriber(value = Side.CLIENT, modid = YouranMod.MODID)
public final class ClientLithoModels {
    static final ModelResourceLocation MODEL = new ModelResourceLocation(YouranMod.MODID + ":lithography_machine", "normal");

    private ClientLithoModels() {
    }

    @SubscribeEvent
    public static void registerModels(ModelRegistryEvent event) {
        ModelLoader.setCustomStateMapper(ModBlocks.lithoPart, new StateMapperBase() {
            @Override
            protected ModelResourceLocation getModelResourceLocation(IBlockState state) {
                return MODEL;
            }
        });
        ClientRegistry.bindTileEntitySpecialRenderer(TileLightSource.class, new LightSourceRenderer());
    }

    @SubscribeEvent
    public static void stitch(TextureStitchEvent.Pre event) {
        boolean ie = Loader.isModLoaded("immersiveengineering");
        for (String s : LithoShape.sprites()) {
            if (ie || !s.startsWith("immersiveengineering:")) {
                event.getMap().registerSprite(new ResourceLocation(s));
            }
        }
    }

    @SubscribeEvent
    public static void bake(ModelBakeEvent event) {
        event.getModelRegistry().putObject(MODEL, new LithoBakedModel(name -> ModelLoader.defaultTextureGetter().apply(new ResourceLocation(name))));
    }

    /** 选取框：成形后每格按外形画几个框（没成形时照原版画整格） */
    @SubscribeEvent
    public static void highlight(DrawBlockHighlightEvent event) {
        RayTraceResult t = event.getTarget();
        if (t == null || t.typeOfHit != RayTraceResult.Type.BLOCK) {
            return;
        }
        EntityPlayer player = event.getPlayer();
        BlockPos pos = t.getBlockPos();
        if (player.world.getBlockState(pos).getBlock() != ModBlocks.lithoPart) {
            return;
        }
        List<AxisAlignedBB> boxes = BlockLithoPart.selectionBoxes(player.world, pos);
        if (boxes.isEmpty()) {
            return;
        }
        float pt = event.getPartialTicks();
        double px = player.lastTickPosX + (player.posX - player.lastTickPosX) * pt;
        double py = player.lastTickPosY + (player.posY - player.lastTickPosY) * pt;
        double pz = player.lastTickPosZ + (player.posZ - player.lastTickPosZ) * pt;
        GlStateManager.enableBlend();
        GlStateManager.tryBlendFuncSeparate(GlStateManager.SourceFactor.SRC_ALPHA, GlStateManager.DestFactor.ONE_MINUS_SRC_ALPHA,
                GlStateManager.SourceFactor.ONE, GlStateManager.DestFactor.ZERO);
        GlStateManager.glLineWidth(2.0F);
        GlStateManager.disableTexture2D();
        GlStateManager.depthMask(false);
        for (AxisAlignedBB b : boxes) {
            RenderGlobal.drawSelectionBoundingBox(b.grow(0.002).offset(-px, -py, -pz), 0, 0, 0, 0.4F);
        }
        GlStateManager.depthMask(true);
        GlStateManager.enableTexture2D();
        GlStateManager.disableBlend();
        event.setCanceled(true);
    }
}
