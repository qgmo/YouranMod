package moe.qingu.youran.logic.source;

import moe.qingu.youran.config.LostCityLootConfig;
import moe.qingu.youran.logic.litho.ChipModel;
import org.junit.jupiter.api.Test;

import java.util.Arrays;
import java.util.Collections;
import java.util.EnumMap;
import java.util.Map;
import java.util.Random;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** 失落的城市建筑箱子：1%、掩模 / 设计图各一半、型号等概率（服主 11-16） */
class ChipLootTest {
    private static final int BOXES = 2_000_000;

    /** 每个箱子 1%：两百万个箱子出的件数在期望 ±4.5σ 以内 */
    @Test
    void onePercentPerChest() {
        Random r = new Random(11_16L);
        int hits = 0;
        for (int i = 0; i < BOXES; i++) {
            if (ChipLoot.roll(r, 0.01, 0.5) != null) {
                hits++;
            }
        }
        double mean = BOXES * 0.01, sd = Math.sqrt(BOXES * 0.01 * 0.99);
        assertTrue(Math.abs(hits - mean) < 4.5 * sd, "出了 " + hits + " 件，期望 " + mean);
    }

    /** 出了的里面掩模、设计图各一半；型号等概率（卡方检验，自由度 = 型号数 − 1） */
    @Test
    void halfMasksAndUniformModels() {
        Random r = new Random(42);
        int n = 240_000;
        Map<ChipLoot.Kind, Integer> kinds = new EnumMap<>(ChipLoot.Kind.class);
        Map<ChipModel, Integer> models = new EnumMap<>(ChipModel.class);
        for (int i = 0; i < n; i++) {
            ChipLoot.Drop d = ChipLoot.roll(r, 1.0, 0.5);
            assertNotNull(d);
            kinds.merge(d.kind, 1, Integer::sum);
            models.merge(d.model, 1, Integer::sum);
        }
        int masks = kinds.getOrDefault(ChipLoot.Kind.MASK, 0);
        assertTrue(Math.abs(masks - n / 2.0) < 4.5 * Math.sqrt(n * 0.25), "掩模 " + masks + " / " + n);
        assertEquals(ChipModel.values().length, models.size(), "每个型号都抽得到");
        double expected = (double) n / ChipModel.values().length, chi2 = 0;
        for (ChipModel m : ChipModel.values()) {
            double d = models.get(m) - expected;
            chi2 += d * d / expected;
        }
        // 自由度 11 时 p = 0.001 的临界值约 31.3
        assertTrue(chi2 < 31.3, "型号分布不均匀：χ² = " + chi2 + "，" + models);
    }

    /** 抽的是全部型号（以后加的型号自动算进去），包括 YR-2000 和激光对准控制程序 */
    @Test
    void allModelsAreCandidates() {
        assertEquals(Arrays.asList(ChipModel.values()), ChipLoot.MODELS);
        assertTrue(ChipLoot.MODELS.contains(ChipModel.YR2000));
        assertTrue(ChipLoot.MODELS.contains(ChipModel.ROM_LASER_ALIGN));
    }

    @Test
    void edges() {
        Random r = new Random(1);
        for (int i = 0; i < 10_000; i++) {
            assertNull(ChipLoot.roll(r, 0, 0.5), "概率 0 不出");
            assertNull(ChipLoot.roll(r, -1, 0.5), "负数当 0");
            assertNull(ChipLoot.roll(r, Double.NaN, 0.5), "NaN 当 0");
            assertEquals(ChipLoot.Kind.MASK, ChipLoot.roll(r, 1, 1).kind, "全是掩模");
            assertEquals(ChipLoot.Kind.DESIGN, ChipLoot.roll(r, 2, 0).kind, "全是设计图，大于 1 当 1");
        }
        assertNull(ChipLoot.roll(r, 1, 0.5, Collections.<ChipModel>emptyList()), "没有型号时不出");
        assertEquals(ChipModel.Z80, ChipLoot.roll(r, 1, 0.5, Collections.singletonList(ChipModel.Z80)).model);
    }

    /** 配置的默认值（服主 11-16）：1%、一半、维度 111、建筑表与原版表；铁路地牢不算 */
    @Test
    void configDefaults() {
        assertTrue(LostCityLootConfig.enabled);
        assertEquals(0.01, LostCityLootConfig.chance);
        assertEquals(0.5, LostCityLootConfig.maskShare);
        assertEquals(1, LostCityLootConfig.dimensions.length);
        assertEquals(111, LostCityLootConfig.dimensions[0]);
        String[] b = LostCityLootConfig.buildingTables, v = LostCityLootConfig.vanillaTables;
        assertEquals(ChipLoot.Target.ANY_DIMENSION, ChipLoot.target("lostcities:chests/lostcitychest", b, v));
        for (String t : new String[]{"minecraft:chests/nether_bridge", "minecraft:chests/abandoned_mineshaft", "minecraft:chests/village_blacksmith",
                "minecraft:chests/desert_pyramid", "minecraft:chests/jungle_temple", "minecraft:chests/simple_dungeon"}) {
            assertEquals(ChipLoot.Target.LOST_CITY_ONLY, ChipLoot.target(t, b, v), t);
        }
        assertEquals(ChipLoot.Target.NONE, ChipLoot.target("lostcities:chests/raildungeonchest", b, v), "铁路地牢不在建筑里");
        assertEquals(ChipLoot.Target.NONE, ChipLoot.target("minecraft:chests/stronghold_corridor", b, v));
        assertEquals(ChipLoot.Target.NONE, ChipLoot.target("minecraft:entities/zombie", b, v));
        assertTrue(ChipLoot.inDimensions(111, LostCityLootConfig.dimensions));
        assertFalse(ChipLoot.inDimensions(0, LostCityLootConfig.dimensions), "主世界的原版结构不刷");
    }

    /** 配置里写的表名：去空白、不分大小写、没写命名空间的当 minecraft */
    @Test
    void tableNamesInConfig() {
        String[] b = {" LostCities:Chests/LostCityChest "};
        String[] v = {"chests/simple_dungeon", ""};
        assertEquals(ChipLoot.Target.ANY_DIMENSION, ChipLoot.target("lostcities:chests/lostcitychest", b, v));
        assertEquals(ChipLoot.Target.LOST_CITY_ONLY, ChipLoot.target("minecraft:chests/simple_dungeon", b, v));
        assertEquals(ChipLoot.Target.NONE, ChipLoot.target("", b, v));
        assertEquals(ChipLoot.Target.NONE, ChipLoot.target("minecraft:chests/simple_dungeon", null, null));
        assertFalse(ChipLoot.inDimensions(111, null));
        assertTrue(ChipLoot.inDimensions(-7, new int[]{111, -7}));
    }
}
