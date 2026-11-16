package moe.qingu.youran.logic.litho;

/**
 * 光刻机光源格里的光源（换档 = 换方块，见设计稿 3.2）：汞灯（{@link LampSpec}）或 ArF 准分子激光（{@link ExcimerSpec}）。
 * 光刻机只通过这几样用光源：能不能曝光、亮度（曝光时间、报废）、分辨率、电耗、曝光一次以后。纯逻辑。
 */
public interface LightSpec {
    /** 现在的亮度（0–1）；不亮时为 0 */
    double brightness();

    /** 这个光源最细能做到多少 μm */
    double resolutionUm();

    /** 寿命到了（汞灯烧坏、激光腔窗口坏了） */
    boolean burntOut();

    /** 寿命没到但不出光（汞灯没汞、激光腔没氟或配比不出光） */
    boolean noFuel();

    /** 曝光时每 tick 耗电（FE） */
    int exposeFePerTick();

    /** 曝光一层之后 */
    LightSpec afterExposure();

    /** 曝光一层要多少 tick：基准 ÷ 亮度；不亮时返回 -1 */
    default int exposeTicks() {
        double b = brightness();
        return b <= 0 ? -1 : (int) Math.ceil(LithoNumbers.EXPOSE_TICKS / b);
    }
}
