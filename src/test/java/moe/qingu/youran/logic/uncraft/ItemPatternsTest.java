package moe.qingu.youran.logic.uncraft;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ItemPatternsTest {
    @Test
    void matchesIdMetaAndWholeMod() {
        ItemPatterns p = ItemPatterns.parse("minecraft:rail", "minecraft:stone@5", "industrialwires:*", "  ", "# 注释", "IC2:Upgrade@3");
        assertEquals(4, p.size());
        assertTrue(p.errors.isEmpty());
        assertTrue(p.matches("minecraft:rail", 0));
        assertTrue(p.matches("minecraft:rail", 7), "不写数据值的是所有数据值");
        assertTrue(p.matches("minecraft:stone", 5));
        assertFalse(p.matches("minecraft:stone", 0), "写了数据值只认这一个");
        assertTrue(p.matches("industrialwires:ic2_wire_coil", 3));
        assertFalse(p.matches("industrialwire:x", 0));
        assertTrue(p.matches("ic2:upgrade", 3), "不分大小写");
        assertFalse(p.matches("ic2:upgrade", 2));
        assertFalse(p.matches(null, 0));
        assertFalse(p.matches("nocolon", 0));
    }

    @Test
    void badLinesAreReportedAndSkipped() {
        ItemPatterns p = ItemPatterns.parse("rail", "minecraft:", ":rail", "a:b:c", "minecraft:stone@x", "minecraft:stone@-1",
                "minecraft:*@3", "minecraft:st*ne", "minecraft:dirt");
        assertEquals(1, p.size());
        assertEquals(8, p.errors.size());
        assertTrue(p.matches("minecraft:dirt", 0));
        assertFalse(ItemPatterns.parse((String[]) null).matches("minecraft:dirt", 0));
    }

    @Test
    void concatKeepsAllEntriesAndErrors() {
        ItemPatterns a = ItemPatterns.parse("minecraft:rail", "bad");
        ItemPatterns b = ItemPatterns.parse("minecraft:dirt");
        ItemPatterns c = ItemPatterns.concat(a, b, ItemPatterns.EMPTY);
        assertTrue(c.matches("minecraft:rail", 0));
        assertTrue(c.matches("minecraft:dirt", 0));
        assertEquals(2, c.size());
        assertEquals(1, c.errors.size());
    }
}
