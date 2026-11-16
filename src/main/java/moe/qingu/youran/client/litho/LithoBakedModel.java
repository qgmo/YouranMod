package moe.qingu.youran.client.litho;

import moe.qingu.youran.litho.BlockLithoPart;
import moe.qingu.youran.litho.LithoBounds;
import moe.qingu.youran.logic.litho.LithoShape;
import moe.qingu.youran.logic.litho.LithoShape.Piece;
import moe.qingu.youran.logic.litho.LithoStructure;
import net.minecraft.block.state.IBlockState;
import net.minecraft.client.renderer.block.model.BakedQuad;
import net.minecraft.client.renderer.block.model.IBakedModel;
import net.minecraft.client.renderer.block.model.ItemCameraTransforms;
import net.minecraft.client.renderer.block.model.ItemOverrideList;
import net.minecraft.client.renderer.texture.TextureAtlasSprite;
import net.minecraft.client.renderer.vertex.DefaultVertexFormats;
import net.minecraft.client.renderer.vertex.VertexFormat;
import net.minecraft.client.renderer.vertex.VertexFormatElement;
import net.minecraft.util.BlockRenderLayer;
import net.minecraft.util.EnumFacing;
import net.minecraftforge.client.MinecraftForgeClient;
import net.minecraftforge.client.model.pipeline.UnpackedBakedQuad;
import net.minecraftforge.common.property.IExtendedBlockState;
import net.minecraftforge.fml.relauncher.Side;
import net.minecraftforge.fml.relauncher.SideOnly;

import javax.annotation.Nullable;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Function;

/**
 * 光刻机的整机模型（照沉浸工程多方块）：每一格只画 {@link LithoShape} 切给它的那些面，按扩展状态里的形状键（格编号、朝向、镜像）
 * 换到世界方向；贴图按结构坐标平铺，格与格之间接得上，看不出格子。按渲染层分开（实心、镂空、半透明的玻璃），按形状键缓存。
 * 还没收到方块实体数据时（形状键没有）画一个整格的钢框。
 */
@SideOnly(Side.CLIENT)
public class LithoBakedModel implements IBakedModel {
    private static final VertexFormat FORMAT = DefaultVertexFormats.ITEM;
    /** 每个世界方向的四个顶点（按原版 {@code EnumFaceDirection} 的顺序，从外面看逆时针）：0 取 min、1 取 max */
    private static final int[][][] CORNERS = new int[6][][];

    static {
        CORNERS[EnumFacing.DOWN.ordinal()] = new int[][]{{0, 0, 1}, {0, 0, 0}, {1, 0, 0}, {1, 0, 1}};
        CORNERS[EnumFacing.UP.ordinal()] = new int[][]{{0, 1, 0}, {0, 1, 1}, {1, 1, 1}, {1, 1, 0}};
        CORNERS[EnumFacing.NORTH.ordinal()] = new int[][]{{1, 1, 0}, {1, 0, 0}, {0, 0, 0}, {0, 1, 0}};
        CORNERS[EnumFacing.SOUTH.ordinal()] = new int[][]{{0, 1, 1}, {0, 0, 1}, {1, 0, 1}, {1, 1, 1}};
        CORNERS[EnumFacing.WEST.ordinal()] = new int[][]{{0, 1, 0}, {0, 0, 0}, {0, 0, 1}, {0, 1, 1}};
        CORNERS[EnumFacing.EAST.ordinal()] = new int[][]{{1, 1, 1}, {1, 0, 1}, {1, 0, 0}, {1, 1, 0}};
    }

    private final Function<String, TextureAtlasSprite> sprites;
    private final TextureAtlasSprite particle;
    private final Map<Integer, List<List<BakedQuad>>> cache = new ConcurrentHashMap<>();
    private final List<BakedQuad> fallback;

    public LithoBakedModel(Function<String, TextureAtlasSprite> sprites) {
        this.sprites = sprites;
        this.particle = sprites.apply("youranmod:blocks/litho/panel");
        TextureAtlasSprite frame = sprites.apply("youranmod:blocks/litho/frame");
        List<BakedQuad> f = new ArrayList<>();
        for (EnumFacing face : EnumFacing.values()) {
            f.add(cubeFace(face, frame));
        }
        this.fallback = Collections.unmodifiableList(f);
    }

    @Override
    public List<BakedQuad> getQuads(@Nullable IBlockState state, @Nullable EnumFacing side, long rand) {
        if (side != null) {
            return Collections.emptyList();
        }
        BlockRenderLayer layer = MinecraftForgeClient.getRenderLayer();
        Integer key = state instanceof IExtendedBlockState ? ((IExtendedBlockState) state).getValue(BlockLithoPart.SHAPE) : null;
        if (key == null || key < 0) {
            return layer == null || layer == BlockRenderLayer.SOLID ? fallback : Collections.emptyList();
        }
        List<List<BakedQuad>> byLayer = cache.computeIfAbsent(key, this::bake);
        if (layer == null) {
            List<BakedQuad> all = new ArrayList<>();
            byLayer.forEach(all::addAll);
            return all;
        }
        switch (layer) {
            case SOLID:
                return byLayer.get(LithoShape.Layer.SOLID.ordinal());
            case CUTOUT:
                return byLayer.get(LithoShape.Layer.CUTOUT.ordinal());
            case TRANSLUCENT:
                return byLayer.get(LithoShape.Layer.TRANSLUCENT.ordinal());
            default:
                return Collections.emptyList();
        }
    }

    private List<List<BakedQuad>> bake(int key) {
        int index = LithoBounds.index(key);
        int facing = LithoBounds.facing(key);
        boolean mirrored = LithoBounds.mirrored(key);
        int h = LithoStructure.hOf(index);
        int l = LithoStructure.lOf(index);
        int w = LithoStructure.wOf(index);
        List<List<BakedQuad>> out = new ArrayList<>();
        for (int i = 0; i < LithoShape.Layer.values().length; i++) {
            out.add(new ArrayList<>());
        }
        for (Piece p : LithoShape.pieces(index)) {
            out.get(p.layer().ordinal()).add(quad(p, h, l, w, facing, mirrored));
        }
        for (int i = 0; i < out.size(); i++) {
            out.set(i, Collections.unmodifiableList(out.get(i)));
        }
        return Collections.unmodifiableList(out);
    }

    /** 一块面 → 一个四边形：顶点换到世界方向，贴图坐标按结构坐标算 */
    private BakedQuad quad(Piece p, int h, int l, int w, int facing, boolean mirrored) {
        double[] a = LithoShape.toWorld(p.min[0] / LithoShape.PX - w, p.min[1] / LithoShape.PX - h, p.min[2] / LithoShape.PX - l, facing, mirrored);
        double[] b = LithoShape.toWorld(p.max[0] / LithoShape.PX - w, p.max[1] / LithoShape.PX - h, p.max[2] / LithoShape.PX - l, facing, mirrored);
        double[] min = {Math.min(a[0], b[0]), Math.min(a[1], b[1]), Math.min(a[2], b[2])};
        double[] max = {Math.max(a[0], b[0]), Math.max(a[1], b[1]), Math.max(a[2], b[2])};
        int ws = LithoShape.worldSide(p.side, facing, mirrored);
        EnumFacing face = ws == -1 ? EnumFacing.UP : ws == -2 ? EnumFacing.DOWN : EnumFacing.byHorizontalIndex(ws);
        TextureAtlasSprite sprite = sprites.apply(p.tex().sprite);
        UnpackedBakedQuad.Builder q = new UnpackedBakedQuad.Builder(FORMAT);
        q.setQuadOrientation(face);
        q.setTexture(sprite);
        q.setApplyDiffuseLighting(true);
        for (int[] c : CORNERS[face.ordinal()]) {
            float x = (float) (c[0] == 0 ? min[0] : max[0]);
            float y = (float) (c[1] == 0 ? min[1] : max[1]);
            float z = (float) (c[2] == 0 ? min[2] : max[2]);
            double[] s = LithoShape.fromWorld(x, y, z, facing, mirrored);
            float[] structure = {(float) ((s[0] + w) * LithoShape.PX), (float) ((s[1] + h) * LithoShape.PX), (float) ((s[2] + l) * LithoShape.PX)};
            float[] uv = LithoShape.uv(p, structure, mirrored);
            vertex(q, face, x, y, z, sprite.getInterpolatedU(uv[0]), sprite.getInterpolatedV(uv[1]));
        }
        return q.build();
    }

    private static BakedQuad cubeFace(EnumFacing face, TextureAtlasSprite sprite) {
        UnpackedBakedQuad.Builder q = new UnpackedBakedQuad.Builder(FORMAT);
        q.setQuadOrientation(face);
        q.setTexture(sprite);
        q.setApplyDiffuseLighting(true);
        int[][] c = CORNERS[face.ordinal()];
        float[][] uv = {{0, 0}, {0, 16}, {16, 16}, {16, 0}};
        for (int i = 0; i < 4; i++) {
            vertex(q, face, c[i][0], c[i][1], c[i][2], sprite.getInterpolatedU(uv[i][0]), sprite.getInterpolatedV(uv[i][1]));
        }
        return q.build();
    }

    private static void vertex(UnpackedBakedQuad.Builder q, EnumFacing face, float x, float y, float z, float u, float v) {
        for (int e = 0; e < FORMAT.getElementCount(); e++) {
            VertexFormatElement el = FORMAT.getElement(e);
            switch (el.getUsage()) {
                case POSITION:
                    q.put(e, x, y, z, 1);
                    break;
                case COLOR:
                    q.put(e, 1, 1, 1, 1);
                    break;
                case UV:
                    if (el.getIndex() == 0) {
                        q.put(e, u, v, 0, 1);
                    } else {
                        q.put(e);
                    }
                    break;
                case NORMAL:
                    q.put(e, face.getXOffset(), face.getYOffset(), face.getZOffset(), 0);
                    break;
                default:
                    q.put(e);
                    break;
            }
        }
    }

    @Override
    public boolean isAmbientOcclusion() {
        return true;
    }

    @Override
    public boolean isGui3d() {
        return false;
    }

    @Override
    public boolean isBuiltInRenderer() {
        return false;
    }

    @Override
    public TextureAtlasSprite getParticleTexture() {
        return particle;
    }

    @SuppressWarnings("deprecation")
    @Override
    public ItemCameraTransforms getItemCameraTransforms() {
        return ItemCameraTransforms.DEFAULT;
    }

    @Override
    public ItemOverrideList getOverrides() {
        return ItemOverrideList.NONE;
    }
}
