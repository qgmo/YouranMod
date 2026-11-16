package moe.qingu.youran.client.litho;

import moe.qingu.youran.litho.TileLightSource;
import net.minecraft.client.renderer.BufferBuilder;
import net.minecraft.client.renderer.GlStateManager;
import net.minecraft.client.renderer.OpenGlHelper;
import net.minecraft.client.renderer.Tessellator;
import net.minecraft.client.renderer.tileentity.TileEntitySpecialRenderer;
import net.minecraft.client.renderer.vertex.DefaultVertexFormats;
import net.minecraftforge.fml.relauncher.Side;
import net.minecraftforge.fml.relauncher.SideOnly;
import org.lwjgl.opengl.GL11;

/**
 * 汞灯光源曝光时的样子：灯管口发出蓝紫色的光（不受环境明暗影响），一道光往下照到对准台的掩模上。浓淡跟着灯现在的亮度
 * （{@code LampSpec.brightness}，越用越暗）。准分子激光光源（193 nm 本身看不见）画成更窄、偏品红的一束：照在掩模、晶圆上激发的荧光。方块本身的亮度（光照等级）不在这里，见 {@code BlockLightSource.getLightValue}。
 */
@SideOnly(Side.CLIENT)
public class LightSourceRenderer extends TileEntitySpecialRenderer<TileLightSource> {
    /** 灯口（方块底面）的范围、光斑范围与光束照到的深度（往下约半格，正好落在对准台的掩模上，见 {@code LithoShape.aligner}） */
    static final float LENS0 = 5 / 16F;
    static final float LENS1 = 11 / 16F;
    static final float SPOT0 = 4 / 16F;
    static final float SPOT1 = 12 / 16F;
    static final float DEPTH = -7.7F / 16F;

    @Override
    public void render(TileLightSource te, double x, double y, double z, float partialTicks, int destroyStage, float alpha) {
        if (!te.lit()) {
            return;
        }
        float k = (float) Math.max(0.2, Math.min(1, te.spec().brightness()));
        boolean laser = te.excimer();
        float cr = laser ? 0.85F : 0.7F;
        float cg = laser ? 0.35F : 0.62F;
        GlStateManager.pushMatrix();
        GlStateManager.translate(x, y, z);
        GlStateManager.disableTexture2D();
        GlStateManager.disableLighting();
        GlStateManager.disableCull();
        GlStateManager.enableBlend();
        GlStateManager.blendFunc(GlStateManager.SourceFactor.SRC_ALPHA, GlStateManager.DestFactor.ONE);
        GlStateManager.depthMask(false);
        // 光束上浓下淡靠顶点颜色插值：要平滑着色；淡的那头透明度低，关掉透明度测试免得被丢掉
        GlStateManager.shadeModel(GL11.GL_SMOOTH);
        GlStateManager.disableAlpha();
        float lx = OpenGlHelper.lastBrightnessX;
        float ly = OpenGlHelper.lastBrightnessY;
        OpenGlHelper.setLightmapTextureCoords(OpenGlHelper.lightmapTexUnit, 240F, 240F);
        Tessellator tes = Tessellator.getInstance();
        BufferBuilder b = tes.getBuffer();
        b.begin(GL11.GL_QUADS, DefaultVertexFormats.POSITION_COLOR);
        // 灯口
        float y0 = -0.004F;
        color(b, LENS0, y0, LENS0, 0.85F, 0.8F, 1F, k);
        color(b, LENS1, y0, LENS0, 0.85F, 0.8F, 1F, k);
        color(b, LENS1, y0, LENS1, 0.85F, 0.8F, 1F, k);
        color(b, LENS0, y0, LENS1, 0.85F, 0.8F, 1F, k);
        // 光束：灯口到掩模的四个侧面，上浓下淡
        float top = 0.5F * k;
        float bottom = 0.1F * k;
        float[][] side = {{LENS0, LENS0, LENS1, LENS0, SPOT0, SPOT0, SPOT1, SPOT0}, {LENS1, LENS0, LENS1, LENS1, SPOT1, SPOT0, SPOT1, SPOT1},
                {LENS1, LENS1, LENS0, LENS1, SPOT1, SPOT1, SPOT0, SPOT1}, {LENS0, LENS1, LENS0, LENS0, SPOT0, SPOT1, SPOT0, SPOT0}};
        for (float[] s : side) {
            color(b, s[0], y0, s[1], cr, cg, 1F, top);
            color(b, s[2], y0, s[3], cr, cg, 1F, top);
            color(b, s[6], DEPTH, s[7], cr, cg, 1F, bottom);
            color(b, s[4], DEPTH, s[5], cr, cg, 1F, bottom);
        }
        // 照在掩模上的光斑
        float ys = DEPTH + 0.004F;
        color(b, SPOT0, ys, SPOT0, 0.75F, 0.7F, 1F, 0.3F * k);
        color(b, SPOT1, ys, SPOT0, 0.75F, 0.7F, 1F, 0.3F * k);
        color(b, SPOT1, ys, SPOT1, 0.75F, 0.7F, 1F, 0.3F * k);
        color(b, SPOT0, ys, SPOT1, 0.75F, 0.7F, 1F, 0.3F * k);
        tes.draw();
        OpenGlHelper.setLightmapTextureCoords(OpenGlHelper.lightmapTexUnit, lx, ly);
        GlStateManager.enableAlpha();
        GlStateManager.shadeModel(GL11.GL_FLAT);
        GlStateManager.depthMask(true);
        GlStateManager.blendFunc(GlStateManager.SourceFactor.SRC_ALPHA, GlStateManager.DestFactor.ONE_MINUS_SRC_ALPHA);
        GlStateManager.disableBlend();
        GlStateManager.enableCull();
        GlStateManager.enableLighting();
        GlStateManager.enableTexture2D();
        GlStateManager.popMatrix();
    }

    private static void color(BufferBuilder b, float x, float y, float z, float r, float g, float bl, float a) {
        b.pos(x, y, z).color(r, g, bl, a).endVertex();
    }
}
