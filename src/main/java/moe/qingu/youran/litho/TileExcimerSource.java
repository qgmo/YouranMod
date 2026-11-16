package moe.qingu.youran.litho;

/**
 * 准分子激光光源（服主 11-16，DUV 一档）的方块实体：同汞灯光源，数据是激光腔的（{@code item.LaserNbt}）。
 * 单独一个类是为了存档里的方块实体 id 不同（{@code youranmod:excimer_light_source}），读档时不靠方块就知道是哪一档。
 */
public class TileExcimerSource extends TileLightSource {
    @Override
    public boolean excimer() {
        return true;
    }
}
