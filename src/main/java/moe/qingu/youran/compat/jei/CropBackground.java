package moe.qingu.youran.compat.jei;

import mezz.jei.api.gui.IDrawable;
import net.minecraft.client.Minecraft;

import javax.annotation.Nullable;

/**
 * 一块界面贴图的裁剪，放在比它宽、高的页面里：横向居中、贴着上边，底下留出位置给说明。
 * 可以再补一小块（界面里贴着主块、但中间夹着不要的东西的部分）。
 */
final class CropBackground implements IDrawable {
    private final IDrawable crop;
    private final int width;
    private final int height;
    private final int padX;
    @Nullable
    private final IDrawable extra;
    private final int extraX;
    private final int extraY;

    CropBackground(IDrawable crop, int width, int height) {
        this(crop, null, 0, 0, crop.getWidth(), width, height);
    }

    /**
     * @param extra  补的那一小块；坐标 {@code extraX}、{@code extraY} 相对主块左上角
     * @param fullWidth 主块加补块合起来的宽（用来居中）
     */
    CropBackground(IDrawable crop, @Nullable IDrawable extra, int extraX, int extraY, int fullWidth, int width, int height) {
        this.crop = crop;
        this.extra = extra;
        this.extraX = extraX;
        this.extraY = extraY;
        this.width = width;
        this.height = height;
        this.padX = (width - fullWidth) / 2;
    }

    /** 裁剪块相对页面左边的偏移 */
    int padX() {
        return padX;
    }

    @Override
    public int getWidth() {
        return width;
    }

    @Override
    public int getHeight() {
        return height;
    }

    @Override
    public void draw(Minecraft minecraft, int xOffset, int yOffset) {
        crop.draw(minecraft, xOffset + padX, yOffset);
        if (extra != null) {
            extra.draw(minecraft, xOffset + padX + extraX, yOffset + extraY);
        }
    }
}
