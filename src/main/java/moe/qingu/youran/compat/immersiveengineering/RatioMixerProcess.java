package moe.qingu.youran.compat.immersiveengineering;

/**
 * 标记：{@code mixin.immersiveengineering.MultiblockProcessMixerMixin} 给搅拌机的处理过程加上这个接口。
 * 加比例配方前先看它在不在（{@link #active()}），没有 Mixin 时不加，免得按 1:1 出流体。
 */
public interface RatioMixerProcess {
    /** 搅拌机的 Mixin 生效了没有（会加载沉浸工程的 {@code TileEntityMixer$MultiblockProcessMixer}） */
    static boolean active() {
        try {
            return RatioMixerProcess.class.isAssignableFrom(
                    Class.forName("blusunrize.immersiveengineering.common.blocks.metal.TileEntityMixer$MultiblockProcessMixer"));
        } catch (ClassNotFoundException | LinkageError e) {
            return false;
        }
    }
}
