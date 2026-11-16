package moe.qingu.youran.client.litho;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Gui;
import net.minecraft.client.renderer.GlStateManager;
import net.minecraft.client.renderer.texture.TextureAtlasSprite;
import net.minecraft.client.renderer.texture.TextureMap;
import net.minecraftforge.fluids.FluidStack;
import net.minecraftforge.fml.relauncher.Side;
import net.minecraftforge.fml.relauncher.SideOnly;

/** 光刻机界面的画法：照原版界面的灰色面板、凹进去的格子，用色块画，不另做界面贴图 */
@SideOnly(Side.CLIENT)
final class LithoDraw {
    static final int BG = 0xFFC6C6C6;
    static final int LIGHT = 0xFFFFFFFF;
    static final int DARK = 0xFF555555;
    static final int SLOT = 0xFF8B8B8B;
    static final int SLOT_DARK = 0xFF373737;
    static final int TEXT = 0x404040;

    private LithoDraw() {
    }

    static void panel(int x, int y, int w, int h) {
        Gui.drawRect(x, y, x + w, y + h, 0xFF000000);
        Gui.drawRect(x + 1, y + 1, x + w - 1, y + h - 1, BG);
        Gui.drawRect(x + 1, y + 1, x + w - 2, y + 3, LIGHT);
        Gui.drawRect(x + 1, y + 1, x + 3, y + h - 2, LIGHT);
        Gui.drawRect(x + 2, y + h - 3, x + w - 1, y + h - 1, DARK);
        Gui.drawRect(x + w - 3, y + 2, x + w - 1, y + h - 1, DARK);
    }

    /** 凹进去的框（格子、计量条） */
    static void sunken(int x, int y, int w, int h) {
        Gui.drawRect(x, y, x + w, y + h, SLOT);
        Gui.drawRect(x, y, x + w - 1, y + 1, SLOT_DARK);
        Gui.drawRect(x, y, x + 1, y + h - 1, SLOT_DARK);
        Gui.drawRect(x + 1, y + h - 1, x + w, y + h, LIGHT);
        Gui.drawRect(x + w - 1, y + 1, x + w, y + h, LIGHT);
    }

    /** 画一段流体（按流体的静止贴图平铺），从下往上填 {@code filled} 像素高 */
    static void fluid(int x, int y, int w, int h, FluidStack stack, int filled) {
        if (stack == null || stack.getFluid() == null || filled <= 0) {
            return;
        }
        Minecraft mc = Minecraft.getMinecraft();
        TextureAtlasSprite sprite = mc.getTextureMapBlocks().getAtlasSprite(stack.getFluid().getStill(stack).toString());
        mc.getTextureManager().bindTexture(TextureMap.LOCATION_BLOCKS_TEXTURE);
        int color = stack.getFluid().getColor(stack);
        GlStateManager.color(((color >> 16) & 255) / 255F, ((color >> 8) & 255) / 255F, (color & 255) / 255F, 1F);
        GlStateManager.enableBlend();
        int top = y + h - filled;
        for (int yy = top; yy < y + h; yy += 16) {
            int hh = Math.min(16, y + h - yy);
            for (int xx = x; xx < x + w; xx += 16) {
                int ww = Math.min(16, x + w - xx);
                drawSprite(xx, yy, ww, hh, sprite);
            }
        }
        GlStateManager.color(1F, 1F, 1F, 1F);
    }

    private static void drawSprite(int x, int y, int w, int h, TextureAtlasSprite s) {
        net.minecraft.client.renderer.Tessellator t = net.minecraft.client.renderer.Tessellator.getInstance();
        net.minecraft.client.renderer.BufferBuilder b = t.getBuffer();
        double u0 = s.getMinU();
        double v0 = s.getMinV();
        double u1 = s.getInterpolatedU(w);
        double v1 = s.getInterpolatedV(h);
        b.begin(7, net.minecraft.client.renderer.vertex.DefaultVertexFormats.POSITION_TEX);
        b.pos(x, y + h, 0).tex(u0, v1).endVertex();
        b.pos(x + w, y + h, 0).tex(u1, v1).endVertex();
        b.pos(x + w, y, 0).tex(u1, v0).endVertex();
        b.pos(x, y, 0).tex(u0, v0).endVertex();
        t.draw();
    }

    /** 实心圆（按行画） */
    static void circle(int cx, int cy, int r, int color) {
        for (int dy = -r; dy <= r; dy++) {
            int half = (int) Math.floor(Math.sqrt(r * r - dy * dy));
            Gui.drawRect(cx - half, cy + dy, cx + half + 1, cy + dy + 1, color);
        }
    }

    /** 只画在圆里面的矩形（显微镜视野里的十字） */
    static void rectInCircle(double x0, double y0, double x1, double y1, int cx, int cy, int r, int color) {
        int top = (int) Math.floor(Math.max(y0, cy - r));
        int bottom = (int) Math.ceil(Math.min(y1, cy + r + 1));
        for (int y = top; y < bottom; y++) {
            int dy = y - cy;
            if (dy * dy > r * r) {
                continue;
            }
            int half = (int) Math.floor(Math.sqrt(r * r - dy * dy));
            int a = (int) Math.max(Math.floor(x0), cx - half);
            int b = (int) Math.min(Math.ceil(x1), cx + half + 1);
            if (a < b) {
                Gui.drawRect(a, y, b, y + 1, color);
            }
        }
    }
}
