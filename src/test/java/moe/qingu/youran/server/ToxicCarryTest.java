package moe.qingu.youran.server;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

class ToxicCarryTest {
    @Test
    void capsAtThreeNines() {
        assertEquals(1, ToxicCarry.cap(1));
        assertEquals(3, ToxicCarry.cap(3));
        assertEquals(3, ToxicCarry.cap(9));
    }
}
