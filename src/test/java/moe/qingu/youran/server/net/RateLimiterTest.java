package moe.qingu.youran.server.net;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** 令牌桶 */
class RateLimiterTest {
    @Test
    void packetsPerSecond() {
        RateLimiter r = new RateLimiter(10, 1_000_000);
        for (int i = 0; i < 10; i++) {
            assertTrue(r.tryAcquire(100, 0));
        }
        assertFalse(r.tryAcquire(100, 0));
        assertFalse(r.tryAcquire(100, 50));
        assertTrue(r.tryAcquire(100, 100), "100 毫秒补一个");
        assertFalse(r.tryAcquire(100, 100));
    }

    @Test
    void bytesPerSecond() {
        RateLimiter r = new RateLimiter(1000, 2560);
        assertTrue(r.tryAcquire(1280, 0));
        assertTrue(r.tryAcquire(1280, 0));
        assertFalse(r.tryAcquire(1280, 0));
        assertTrue(r.tryAcquire(1280, 500));
    }

    @Test
    void timeGoingBackwardsDoesNotRefill() {
        RateLimiter r = new RateLimiter(1, 10_000);
        assertTrue(r.tryAcquire(1, 1000));
        assertFalse(r.tryAcquire(1, 500));
        assertFalse(r.tryAcquire(1, 1000));
    }
}
