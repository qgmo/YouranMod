package moe.qingu.youran.logic.litho;

import org.junit.jupiter.api.Test;

import java.util.Random;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class AlignmentTest {
    static final double EPS = 1e-9;

    @Test
    void translationMovesBothSides() {
        Alignment a = new Alignment(0, 0, 0);
        a.turn(Alignment.Axis.X, 1, false);
        assertEquals(LithoNumbers.STEP_COARSE_UM, a.left()[0], EPS);
        assertEquals(LithoNumbers.STEP_COARSE_UM, a.right()[0], EPS);
    }

    /** 旋转让左右两边的 y 往相反方向动 */
    @Test
    void rotationMovesSidesOppositely() {
        Alignment a = new Alignment(0, 0, 0);
        a.turn(Alignment.Axis.THETA, 1, false);
        double l = a.left()[1];
        double r = a.right()[1];
        assertTrue(l < 0 && r > 0);
        assertEquals(-l, r, EPS);
        assertEquals(LithoNumbers.MARK_HALF_SPAN_UM * LithoNumbers.STEP_COARSE_URAD * 1e-6, r, EPS);
    }

    /** 换方向时头一段空转（回差） */
    @Test
    void backlashEatsFirstStepsAfterReversal() {
        Alignment a = new Alignment(0, 0, 0);
        a.turn(Alignment.Axis.X, 1, true);
        double before = a.x();
        double moved = a.turn(Alignment.Axis.X, -1, true);
        assertEquals(0, moved, EPS);
        assertEquals(before, a.x(), EPS);
        int fineSteps = (int) Math.round(LithoNumbers.BACKLASH_UM / LithoNumbers.STEP_FINE_UM);
        for (int i = 1; i < fineSteps; i++) {
            a.turn(Alignment.Axis.X, -1, true);
        }
        assertEquals(before, a.x(), 1e-6);
        a.turn(Alignment.Axis.X, -1, true);
        assertEquals(before - LithoNumbers.STEP_FINE_UM, a.x(), 1e-6);
    }

    @Test
    void sameDirectionHasNoBacklash() {
        Alignment a = new Alignment(5, 0, 0);
        a.turn(Alignment.Axis.Y, 1, false);
        assertEquals(LithoNumbers.STEP_COARSE_UM, a.turn(Alignment.Axis.Y, 1, false), EPS);
    }

    @Test
    void rateFromResidual() {
        assertEquals(1000, Alignment.ratePermille(0, 2));
        assertEquals(500, Alignment.ratePermille(1, 2));
        assertEquals(0, Alignment.ratePermille(3, 2));
        Alignment a = new Alignment(0.3, 0.4, 0);
        assertEquals(0.5, a.residual(), EPS);
        assertEquals(750, a.ratePermille(2));
    }

    /** 一个会玩的人（先平移、再旋转、再细调）能对到 90% 以上 */
    @Test
    void reachableByPlay() {
        Alignment a = new Alignment();
        a.randomize(new Random(3));
        for (int round = 0; round < 6; round++) {
            for (boolean fine : new boolean[]{false, true}) {
                double step = fine ? LithoNumbers.STEP_FINE_UM : LithoNumbers.STEP_COARSE_UM;
                double rstep = LithoNumbers.MARK_HALF_SPAN_UM * (fine ? LithoNumbers.STEP_FINE_URAD : LithoNumbers.STEP_COARSE_URAD) * 1e-6;
                for (int i = 0; i < 400 && Math.abs(a.x()) > step / 2; i++) {
                    a.turn(Alignment.Axis.X, a.x() > 0 ? -1 : 1, fine);
                }
                for (int i = 0; i < 400 && Math.abs(a.y()) > step / 2; i++) {
                    a.turn(Alignment.Axis.Y, a.y() > 0 ? -1 : 1, fine);
                }
                for (int i = 0; i < 2000 && Math.abs(a.right()[1] - a.y()) > rstep / 2; i++) {
                    a.turn(Alignment.Axis.THETA, a.theta() > 0 ? -1 : 1, fine);
                }
            }
        }
        assertTrue(a.ratePermille(ChipModel.Z80.toleranceUm()) > 900, "对准率 " + a.ratePermille(ChipModel.Z80.toleranceUm()));
    }

    @Test
    void driftIsSmall() {
        Alignment a = new Alignment(0, 0, 0);
        Random r = new Random(1);
        for (int i = 0; i < 10; i++) {
            a.drift(r);
        }
        assertTrue(a.residual() < 0.5, "漂移 " + a.residual());
    }
}
