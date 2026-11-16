package moe.qingu.youran.litho;

import moe.qingu.youran.logic.litho.LithoShape;
import net.minecraft.util.EnumFacing;
import net.minecraft.util.math.AxisAlignedBB;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 成形后每一格的碰撞箱、选取框（照沉浸工程 {@code getAdvancedCollisionBounds} / {@code getAdvancedSelectionBounds}）：
 * 从整机外形（{@link LithoShape}）按格切出来，按朝向、镜像换到世界方向。方块坐标内（0–1），用时加上方块位置。
 *
 * <p>“形状键”把格编号、朝向、镜像合成一个数：{@code 格编号 × 8 + 水平朝向 × 2 + 镜像}，模型也按它缓存。
 */
public final class LithoBounds {
    private static final Map<Integer, List<AxisAlignedBB>> COLLISION = new ConcurrentHashMap<>();
    private static final Map<Integer, List<AxisAlignedBB>> SELECTION = new ConcurrentHashMap<>();

    private LithoBounds() {
    }

    public static int key(int index, EnumFacing facing, boolean mirrored) {
        return index * 8 + facing.getHorizontalIndex() * 2 + (mirrored ? 1 : 0);
    }

    public static int index(int key) {
        return key >> 3;
    }

    /** 水平编号（0 南、1 西、2 北、3 东） */
    public static int facing(int key) {
        return (key >> 1) & 3;
    }

    public static boolean mirrored(int key) {
        return (key & 1) != 0;
    }

    public static List<AxisAlignedBB> collision(int key) {
        return COLLISION.computeIfAbsent(key, k -> convert(LithoShape.collision(index(k)), k));
    }

    public static List<AxisAlignedBB> selection(int key) {
        return SELECTION.computeIfAbsent(key, k -> convert(LithoShape.selection(index(k)), k));
    }

    /** 选取框合起来的外包盒（{@code getBoundingBox} 用） */
    public static AxisAlignedBB outline(int key) {
        AxisAlignedBB out = null;
        for (AxisAlignedBB b : selection(key)) {
            out = out == null ? b : out.union(b);
        }
        return out == null ? net.minecraft.block.Block.FULL_BLOCK_AABB : out;
    }

    private static List<AxisAlignedBB> convert(List<float[]> boxes, int key) {
        List<AxisAlignedBB> out = new ArrayList<>(boxes.size());
        for (float[] b : boxes) {
            double[] w = LithoShape.boxToWorld(b, facing(key), mirrored(key));
            out.add(new AxisAlignedBB(w[0], w[1], w[2], w[3], w[4], w[5]));
        }
        return Collections.unmodifiableList(out);
    }
}
