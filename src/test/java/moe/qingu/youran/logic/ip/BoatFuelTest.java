package moe.qingu.youran.logic.ip;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class BoatFuelTest {
    @Test
    void clampKeepsAmountsInsideCapacity() {
        assertEquals(500, BoatFuel.clamp(500, 8000));
        assertEquals(8000, BoatFuel.clamp(8000, 8000));
        assertEquals(0, BoatFuel.clamp(0, 8000));
    }

    @Test
    void clampCutsOverflowAndNegative() {
        assertEquals(8000, BoatFuel.clamp(1_000_100, 8000));
        assertEquals(16000, BoatFuel.clamp(Integer.MAX_VALUE, 16000));
        assertEquals(0, BoatFuel.clamp(-5, 8000));
        assertEquals(0, BoatFuel.clamp(Integer.MIN_VALUE, 8000));
        assertEquals(0, BoatFuel.clamp(100, -1));
    }

    @Test
    void acceptsNormalAndBoostedConsumption() {
        assertTrue(BoatFuel.acceptsConsumed(1, 1));
        assertTrue(BoatFuel.acceptsConsumed(3, 1));
        assertTrue(BoatFuel.acceptsConsumed(0, 1));
        assertTrue(BoatFuel.acceptsConsumed(15, 5));
    }

    @Test
    void rejectsNegativeAndTooLarge() {
        assertFalse(BoatFuel.acceptsConsumed(-1, 1));
        assertFalse(BoatFuel.acceptsConsumed(-1_000_000, 1));
        assertFalse(BoatFuel.acceptsConsumed(Integer.MIN_VALUE, 1));
        assertFalse(BoatFuel.acceptsConsumed(4, 1));
        assertFalse(BoatFuel.acceptsConsumed(Integer.MAX_VALUE, 1000));
        assertFalse(BoatFuel.acceptsConsumed(1, 0));
        assertTrue(BoatFuel.acceptsConsumed(0, 0));
    }
}
