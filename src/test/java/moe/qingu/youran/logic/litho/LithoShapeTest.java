package moe.qingu.youran.logic.litho;

import moe.qingu.youran.logic.litho.LithoShape.Box;
import moe.qingu.youran.logic.litho.LithoShape.Piece;
import moe.qingu.youran.logic.litho.LithoShape.Side;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class LithoShapeTest {
    private static final float EPS = 1e-3f;

    @Test
    void boxesStayInsideTheStructure() {
        for (Box b : LithoShape.boxes()) {
            float[] size = {LithoShape.SIZE_X, LithoShape.SIZE_Y, LithoShape.SIZE_Z};
            for (int a = 0; a < 3; a++) {
                assertTrue(b.min[a] >= 0 && b.max[a] <= size[a], "长方体出了结构外框");
                assertTrue(b.max[a] - b.min[a] >= 0.5f - EPS, "长方体太薄");
            }
        }
    }

    @Test
    void everyBlockHasSomethingToClickAndNothingElseDraws() {
        for (int h = 0; h < LithoStructure.HEIGHT; h++) {
            for (int l = 0; l < LithoStructure.LENGTH; l++) {
                for (int w = 0; w < LithoStructure.WIDTH; w++) {
                    int i = LithoStructure.index(h, l, w);
                    if (LithoShape.hasBlock(h, l, w)) {
                        assertFalse(LithoShape.selection(i).isEmpty(), "这一格点不中：" + h + "," + l + "," + w);
                        assertFalse(LithoShape.pieces(i).isEmpty(), "这一格什么都不画：" + h + "," + l + "," + w);
                    } else {
                        assertTrue(LithoShape.pieces(i).isEmpty(), "空格、光源格不该画东西");
                        assertTrue(LithoShape.collision(i).isEmpty(), "空格、光源格不该有碰撞箱");
                    }
                }
            }
        }
    }

    @Test
    void collisionBoxesStayInTheirCell() {
        for (int i = 0; i < LithoStructure.HEIGHT * LithoStructure.LENGTH * LithoStructure.WIDTH; i++) {
            for (float[] c : LithoShape.collision(i)) {
                for (int a = 0; a < 3; a++) {
                    assertTrue(c[a] >= -EPS && c[a + 3] <= LithoShape.PX + EPS && c[a] < c[a + 3]);
                }
            }
        }
    }

    /** 黄光小间：门洞两格是空的；小间里（正面一排 w2–w4、地面到灯箱下沿）没有挡人的东西，人能走进去站在对准台前 */
    @Test
    void boothIsWalkable() {
        assertEquals(LithoStructure.EMPTY, LithoStructure.at(0, 0, 3));
        assertEquals(LithoStructure.EMPTY, LithoStructure.at(1, 0, 3));
        float[] room = {33, 0, 2, 78, 34, 16};
        for (Box b : LithoShape.boxes()) {
            if (b.solid) {
                boolean overlap = b.min[0] < room[3] && b.max[0] > room[0] && b.min[1] < room[4] && b.max[1] > room[1]
                        && b.min[2] < room[5] && b.max[2] > room[2];
                assertFalse(overlap, "小间里有挡人的东西：" + java.util.Arrays.toString(b.min) + " " + java.util.Arrays.toString(b.max));
            }
        }
        // 对准台齐胸高（台面在第 1 层底），目镜朝着小间
        int aligner = LithoStructure.index(LithoStructure.MASTER_H, LithoStructure.MASTER_L, LithoStructure.MASTER_W);
        assertFalse(LithoShape.pieces(aligner).isEmpty());
    }

    /** 光源那格不画（光源方块自己画灯罩），它上面是排气管 */
    @Test
    void lightSourceCellIsLeftToTheLightSource() {
        assertEquals(LithoStructure.LIGHT_SOURCE, LithoStructure.at(2, 1, 3));
        assertTrue(LithoShape.pieces(LithoStructure.index(2, 1, 3)).isEmpty());
        assertFalse(LithoShape.pieces(LithoStructure.index(3, 1, 3)).isEmpty());
    }

    /** 每块面要么在自己那格里，要么所在的格没有方块（由附近一格代画） */
    @Test
    void piecesAreDrawnByTheirOwnCellWhenItExists() {
        for (int i = 0; i < LithoStructure.HEIGHT * LithoStructure.LENGTH * LithoStructure.WIDTH; i++) {
            int h = LithoStructure.hOf(i);
            int l = LithoStructure.lOf(i);
            int w = LithoStructure.wOf(i);
            for (Piece p : LithoShape.pieces(i)) {
                float[] lo = {w * 16f, h * 16f, l * 16f};
                boolean inside = true;
                for (int a = 0; a < 3; a++) {
                    inside &= p.min[a] >= lo[a] - EPS && p.max[a] <= lo[a] + 16 + EPS;
                }
                if (!inside) {
                    int[] c = new int[3];
                    for (int a = 0; a < 3; a++) {
                        float mid = (p.min[a] + p.max[a]) / 2;
                        if (a == p.side.axis) {
                            mid -= p.side.sign * 0.01f;
                        }
                        c[a] = (int) Math.floor(mid / 16);
                    }
                    assertFalse(LithoShape.hasBlock(c[1], c[2], c[0]), "这块面所在的格有方块，却交给了别的格画");
                }
            }
        }
    }

    @Test
    void transformRoundTripsAndKeepsTheCell() {
        for (int f = 0; f < 4; f++) {
            for (boolean m : new boolean[]{false, true}) {
                double[] p = LithoShape.toWorld(0.25, 0.5, 0.125, f, m);
                for (double v : p) {
                    assertTrue(v >= 0 && v <= 1);
                }
                assertArrayEquals(new double[]{0.25, 0.5, 0.125}, LithoShape.fromWorld(p[0], p[1], p[2], f, m), 1e-9);
            }
        }
        // 朝北（往背面是北）：w 往东，l 往北
        assertArrayEquals(new double[]{0.25, 0, 0.75}, LithoShape.toWorld(0.25, 0, 0.25, 2, false), 1e-9);
        // 镜像：w 往西
        assertArrayEquals(new double[]{0.75, 0, 0.75}, LithoShape.toWorld(0.25, 0, 0.25, 2, true), 1e-9);
        // 朝东：w 往南，l 往东
        assertArrayEquals(new double[]{0.25, 0, 0.25}, LithoShape.toWorld(0.25, 0, 0.25, 3, false), 1e-9);
    }

    @Test
    void sidesFollowFacingAndMirror() {
        // 朝北：背面朝北（2）、正面朝南（0）、右边朝东（3）；镜像时右边朝西（1）
        assertEquals(2, LithoShape.worldSide(Side.BACK, 2, false));
        assertEquals(0, LithoShape.worldSide(Side.FRONT, 2, false));
        assertEquals(3, LithoShape.worldSide(Side.RIGHT, 2, false));
        assertEquals(1, LithoShape.worldSide(Side.RIGHT, 2, true));
        assertEquals(1, LithoShape.worldSide(Side.LEFT, 2, false));
        assertEquals(-1, LithoShape.worldSide(Side.UP, 1, true));
    }

    /** 贴图坐标都在 0–16 里（平铺按块所在的周期算，不会在格边上跳），镜像时翻过来 */
    @Test
    void uvStayInRangeAndMirrorFlips() {
        for (int i = 0; i < LithoStructure.HEIGHT * LithoStructure.LENGTH * LithoStructure.WIDTH; i++) {
            for (Piece p : LithoShape.pieces(i)) {
                for (boolean m : new boolean[]{false, true}) {
                    for (float[] v : new float[][]{p.min, p.max}) {
                        float[] uv = LithoShape.uv(p, v, m);
                        assertTrue(uv[0] >= -EPS && uv[0] <= 16 + EPS && uv[1] >= -EPS && uv[1] <= 16 + EPS, "贴图坐标出界");
                    }
                }
                if (p.side == Side.FRONT && p.tex().period > 0 && p.max[0] - p.min[0] > 1) {
                    float a = LithoShape.uv(p, p.min, false)[0];
                    float b = LithoShape.uv(p, p.min, true)[0];
                    float width = (p.max[0] - p.min[0]) * 16 / p.tex().period;
                    // 不镜像时左边是小的那端；镜像时左右对调
                    assertTrue(Math.abs(a + width - LithoShape.uv(p, p.max, false)[0]) < EPS);
                    assertTrue(Math.abs(b - width - LithoShape.uv(p, p.max, true)[0]) < EPS);
                }
            }
        }
    }

    /** 接口格在模型上能看出来：背面五个流体口、左右两个物品口、能量口、红石口都有自己的贴图 */
    @Test
    void portsAreVisible() {
        assertTrue(LithoShape.sprites().contains("youranmod:blocks/litho/port_in"));
        assertTrue(LithoShape.sprites().contains("youranmod:blocks/litho/port_out"));
        for (LithoStructure.Port port : LithoStructure.Port.values()) {
            boolean marked = false;
            for (Piece p : LithoShape.pieces(LithoStructure.index(port.h, port.l, port.w))) {
                String s = p.tex().sprite;
                marked |= s.endsWith("port_in") || s.endsWith("port_out") || s.endsWith("hatch_in") || s.endsWith("hatch_out")
                        || s.endsWith("light_engineering") || s.endsWith("redstone_engineering");
                if (port.isFluid() && (s.endsWith("port_in") || s.endsWith("port_out"))) {
                    assertEquals(port.input, s.endsWith("port_in"), "流体口的颜色（蓝入橙出）不对：" + port);
                    assertEquals(Side.BACK, p.side);
                }
            }
            assertTrue(marked, "接口在模型上看不出来：" + port);
        }
    }
}
