package moe.qingu.youran.logic.litho;

import org.junit.jupiter.api.Test;

import java.util.Random;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** 激光对准（服主 11-16，半自动）：捕获范围、收敛、自动补漂移、漂出范围 */
class LaserAlignTest {
    static final int CPU_8088 = 4773;

    static void run(LaserAlign l, Alignment a) {
        for (int i = 0; i < 10_000 && l.state() == LaserAlign.State.CONVERGING; i++) {
            l.step(a);
        }
    }

    /** 捕获范围的边界：平移 ±1 μm、转角 ±75 μrad，边上算在里面，多一点就不算 */
    @Test
    void captureBoundary() {
        assertTrue(LaserAlign.inCapture(new Alignment(1.0, -1.0, 75)));
        assertTrue(LaserAlign.inCapture(new Alignment(-1.0, 1.0, -75)));
        assertFalse(LaserAlign.inCapture(new Alignment(1.001, 0, 0)));
        assertFalse(LaserAlign.inCapture(new Alignment(0, -1.001, 0)));
        assertFalse(LaserAlign.inCapture(new Alignment(0, 0, 75.1)));
        // 没进捕获范围按不了，状态不变
        LaserAlign l = new LaserAlign();
        Alignment far = new Alignment(2, 0, 0);
        assertFalse(l.engage(far, CPU_8088, 1, new Random(1)));
        assertSame(LaserAlign.State.OFF, l.state());
        assertEquals(2, far.x(), 0);
    }

    /** 按下后几秒内收到约 0.01 μm：满强度激光残差 0.005–0.01 μm，YR-2000 的公差（0.065 μm）内对准率过报废线 */
    @Test
    void convergesToAbout10nm() {
        for (long seed = 0; seed < 50; seed++) {
            Random r = new Random(seed);
            Alignment a = new Alignment(0.9, -0.7, 60);
            LaserAlign l = new LaserAlign();
            assertTrue(l.engage(a, CPU_8088, 1, r));
            assertSame(LaserAlign.State.CONVERGING, l.state());
            int ticks = 0;
            while (l.state() == LaserAlign.State.CONVERGING) {
                l.step(a);
                ticks++;
            }
            assertSame(LaserAlign.State.LOCKED, l.state());
            assertEquals(DuvNumbers.LASER_CONVERGE_TICKS, ticks);
            assertTrue(a.residual() <= 0.01 + 1e-9 && a.residual() >= 0.005 - 1e-9, String.valueOf(a.residual()));
            int rate = a.ratePermille(ChipModel.YR2000.toleranceUm());
            assertTrue(rate >= LithoNumbers.ALIGN_MIN_PERMILLE, "rate " + rate);
        }
    }

    /** CPU 越快收敛越快；激光越弱越慢、越不准 */
    @Test
    void cpuAndLaserStrength() {
        assertTrue(LaserAlign.convergeTicks(8000, 1) < LaserAlign.convergeTicks(CPU_8088, 1));
        assertTrue(LaserAlign.convergeTicks(CPU_8088, 0.5) > LaserAlign.convergeTicks(CPU_8088, 1));
        assertEquals(DuvNumbers.LASER_CONVERGE_MAX_TICKS, LaserAlign.convergeTicks(0, 1));
        assertTrue(LaserAlign.targetResidual(0.5, 1) > LaserAlign.targetResidual(1, 1));
        // 很弱的激光收不进 YR-2000 的公差
        Alignment a = new Alignment(0.5, 0.5, 10);
        LaserAlign l = new LaserAlign();
        assertTrue(l.engage(a, CPU_8088, 0.1, new Random(3)));
        run(l, a);
        assertTrue(a.ratePermille(ChipModel.YR2000.toleranceUm()) < LithoNumbers.ALIGN_MIN_PERMILLE);
        // 不出光的激光、坏的 CPU 按不了
        assertFalse(new LaserAlign().engage(new Alignment(0, 0, 0), CPU_8088, 0, new Random(1)));
        assertFalse(new LaserAlign().engage(new Alignment(0, 0, 0), 0, 1, new Random(1)));
    }

    /** 锁定后每片的漂移在捕获范围里就自动补回去；人手对准（公差 0.065 μm、漂移 0.03 μm/片）几片就掉出报废线，激光对准一直在线 */
    @Test
    void autoCompensatesDrift() {
        Random r = new Random(7);
        Alignment a = new Alignment(0.3, 0.2, 5);
        LaserAlign l = new LaserAlign();
        assertTrue(l.engage(a, CPU_8088, 1, r));
        run(l, a);
        for (int chip = 0; chip < 300; chip++) {
            a.drift(r);
            assertTrue(l.afterDrift(a, CPU_8088, 1, r));
            run(l, a);
            assertSame(LaserAlign.State.LOCKED, l.state());
            assertTrue(a.ratePermille(ChipModel.YR2000.toleranceUm()) >= LithoNumbers.ALIGN_MIN_PERMILLE);
        }
    }

    /** 漂出捕获范围就松开，要人重新粗对准 */
    @Test
    void driftOutOfCaptureReleases() {
        Alignment a = new Alignment(0, 0, 0);
        LaserAlign l = new LaserAlign();
        assertTrue(l.engage(a, CPU_8088, 1, new Random(1)));
        run(l, a);
        a.moveTo(1.5, 0, 0);
        assertFalse(l.afterDrift(a, CPU_8088, 1, new Random(2)));
        assertSame(LaserAlign.State.OFF, l.state());
        assertEquals(1.5, a.x(), 0);
        // 没开时漂移什么也不做
        assertFalse(new LaserAlign().afterDrift(new Alignment(0, 0, 0), CPU_8088, 1, new Random(1)));
    }

    /** 存档读回来接着走 */
    @Test
    void saveRestore() {
        Alignment a = new Alignment(0.8, 0.8, 40);
        LaserAlign l = new LaserAlign();
        l.engage(a, CPU_8088, 0.8, new Random(5));
        for (int i = 0; i < 10; i++) {
            l.step(a);
        }
        LaserAlign copy = new LaserAlign();
        copy.restore(l.save());
        Alignment b = new Alignment(a.x(), a.y(), a.theta());
        run(l, a);
        run(copy, b);
        assertEquals(a.x(), b.x(), 1e-12);
        assertEquals(a.theta(), b.theta(), 1e-12);
        assertSame(LaserAlign.State.LOCKED, copy.state());
    }
}
