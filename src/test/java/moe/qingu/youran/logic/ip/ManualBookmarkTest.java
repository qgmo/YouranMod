package moe.qingu.youran.logic.ip;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ManualBookmarkTest {
    @Test
    void acceptsMultiblockNames() {
        assertTrue(ManualBookmark.plausible("IE:ArcFurnace"));
        assertTrue(ManualBookmark.plausible("IP:DistillationTower"));
        assertTrue(ManualBookmark.plausible("IE:SheetmetalTank"));
        assertTrue(ManualBookmark.plausible("industrialwires:marx_generator"));
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < ManualBookmark.MAX_LENGTH; i++) {
            sb.append('a');
        }
        assertTrue(ManualBookmark.plausible(sb.toString()));
    }

    @Test
    void rejectsLongOrOddNames() {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i <= ManualBookmark.MAX_LENGTH; i++) {
            sb.append('a');
        }
        assertFalse(ManualBookmark.plausible(sb.toString()));
        StringBuilder huge = new StringBuilder();
        for (int i = 0; i < 32000; i++) {
            huge.append('x');
        }
        assertFalse(ManualBookmark.plausible(huge.toString()));
        assertFalse(ManualBookmark.plausible(""));
        assertFalse(ManualBookmark.plausible(null));
        assertFalse(ManualBookmark.plausible("IE:Arc Furnace"));
        assertFalse(ManualBookmark.plausible("IE:电弧炉"));
        assertFalse(ManualBookmark.plausible("IE:Arc\nFurnace"));
        assertFalse(ManualBookmark.plausible("§cIE:ArcFurnace"));
    }
}
