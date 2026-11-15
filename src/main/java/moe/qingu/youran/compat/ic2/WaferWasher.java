package moe.qingu.youran.compat.ic2;

/** 标记接口：IC2 洗矿机的 Mixin（{@code mixin.ic2.TileEntityOreWashingMixin}）生效了，加洗晶圆的配方前据此确认 */
public interface WaferWasher {
    /** 洗矿机的 Mixin 生效了没有（会加载 IC2 的 {@code TileEntityOreWashing}） */
    static boolean active() {
        try {
            return WaferWasher.class.isAssignableFrom(Class.forName("ic2.core.block.machine.tileentity.TileEntityOreWashing"));
        } catch (ClassNotFoundException | LinkageError e) {
            return false;
        }
    }
}
