package moe.qingu.youran.server.net.nav;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

class TodayTest {
    @Test
    void clock() {
        Today t = Today.of(0, Today.Weather.CLEAR, 1, 20);
        assertEquals(1, t.day);
        assertEquals("06:00", t.clock());
        assertEquals("早上", t.period());
        Today n = Today.of(24000 * 9 + 18000, Today.Weather.RAIN, 0, 20);
        assertEquals(10, n.day);
        assertEquals("00:00", n.clock());
        assertEquals("凌晨", n.period());
        assertEquals("14:30", Today.of(8500, Today.Weather.CLEAR, 0, 0).clock());
    }
}
