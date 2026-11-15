package moe.qingu.youran.compat.immersivepetroleum;

/**
 * 沉浸原油的油井信息（{@code PumpjackHandler.OilWorldInfo}）由 {@code mixin.immersivepetroleum.OilWorldInfoMixin} 加上的东西：
 * 这一份是不是这次开服里现算出来的、算出来时是什么样（{@link ReservoirCacheHooks}）。
 */
public interface GeneratedReservoir {
    /** 刚现算出来：记下现在的类型与容量 */
    void youran$markGenerated();

    /** 删掉以后下次能再算出一模一样的一份（见 {@code logic.ip.ReservoirCache#regenerable}） */
    boolean youran$regenerable();
}
