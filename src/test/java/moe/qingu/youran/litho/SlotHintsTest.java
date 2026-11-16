package moe.qingu.youran.litho;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** 光刻机空格子的提示与格子编号一一对应；三份语言文件都有这些键 */
class SlotHintsTest {
    /** 每个主方块物品格：输出格没有提示，其余都有；同一类（五个容器格、两个晶圆格）共用一条 */
    @Test
    void everyMachineSlotHasHintExceptOutputs() {
        for (int i = 0; i < LithoSlots.COUNT; i++) {
            if (LithoSlots.isOutput(i)) {
                assertNull(SlotHints.forMachineSlot(i), "输出格 " + i + " 不该有提示");
            } else {
                assertNotNull(SlotHints.forMachineSlot(i), "格 " + i + " 缺提示");
            }
        }
        assertNull(SlotHints.forMachineSlot(LithoSlots.COUNT));
        assertNull(SlotHints.forMachineSlot(-1));
    }

    @Test
    void groupedSlotsShareHints() {
        for (int i = 0; i < LithoSlots.CONTAINERS; i++) {
            assertEquals(SlotHints.CONTAINER, SlotHints.forMachineSlot(LithoSlots.CONTAINER_FIRST + i));
        }
        for (int i = 0; i < LithoSlots.WAFERS; i++) {
            assertEquals(SlotHints.WAFER, SlotHints.forMachineSlot(LithoSlots.WAFER_FIRST + i));
        }
    }

    /** 与 LithoItems.slotFor 的去向一致：它认的每一格（晶圆、六样辅料、两样碱）提示各不相同 */
    @Test
    void itemDestinationsHaveDistinctHints() {
        int[] dest = {LithoSlots.WAFER_FIRST, LithoSlots.BORON_INGOT, LithoSlots.GLOWSTONE, LithoSlots.BORON_ELEMENT,
                LithoSlots.PHOSPHORUS, LithoSlots.ALUMINUM, LithoSlots.CASING, LithoSlots.HYDROXIDE, LithoSlots.CARBONATE};
        Set<String> seen = new HashSet<>();
        for (int slot : dest) {
            assertTrue(seen.add(SlotHints.forMachineSlot(slot)), "提示重复：格 " + slot);
        }
        assertEquals(SlotHints.MASK, SlotHints.forMachineSlot(LithoSlots.MASK));
    }

    @Test
    void laserSlots() {
        assertEquals(SlotHints.BOARD, SlotHints.forLaserSlot(LaserAligner.SLOT_BOARD));
        assertEquals(SlotHints.LASER, SlotHints.forLaserSlot(LaserAligner.SLOT_LASER));
        assertNull(SlotHints.forLaserSlot(LaserAligner.SLOTS));
    }

    @Test
    void langFilesHaveAllHints() throws IOException {
        Set<String> ids = new HashSet<>();
        for (int i = 0; i < LithoSlots.COUNT; i++) {
            if (SlotHints.forMachineSlot(i) != null) {
                ids.add(SlotHints.forMachineSlot(i));
            }
        }
        ids.add(SlotHints.BOARD);
        ids.add(SlotHints.LASER);
        for (String lang : new String[]{"en_us", "zh_cn", "zh_tw"}) {
            List<String> lines = Files.readAllLines(Paths.get("src/main/resources/assets/youranmod/lang/" + lang + ".lang"), StandardCharsets.UTF_8);
            for (String id : ids) {
                String key = SlotHints.key(id) + "=";
                assertTrue(lines.stream().anyMatch(l -> l.startsWith(key)), lang + " 缺 " + key);
            }
        }
    }
}
