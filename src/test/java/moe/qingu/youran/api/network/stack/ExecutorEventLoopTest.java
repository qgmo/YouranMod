package moe.qingu.youran.api.network.stack;

import org.junit.jupiter.api.Test;

import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** 网络线程的启停 */
class ExecutorEventLoopTest {
    @Test
    void queuedTasksRunBeforeShutdownAndTimersAreDropped() throws Exception {
        ExecutorEventLoop loop = new ExecutorEventLoop("测试网络线程");
        AtomicInteger ran = new AtomicInteger();
        AtomicBoolean timer = new AtomicBoolean();
        loop.schedule(() -> timer.set(true), 60_000);
        for (int i = 0; i < 100; i++) {
            loop.execute(ran::incrementAndGet);
        }
        assertTrue(loop.shutdown(5000));
        assertEquals(100, ran.get());
        assertFalse(timer.get());
        assertTrue(loop.isShutdown());
        // 停下以后再交的任务安静丢掉
        loop.execute(ran::incrementAndGet);
        loop.schedule(ran::incrementAndGet, 0).cancel();
        assertEquals(100, ran.get());
        for (Thread t : Thread.getAllStackTraces().keySet()) {
            if (t.getName().equals("测试网络线程")) {
                // 线程池结束后工作线程还要走完退出的最后几步
                t.join(2000);
                assertFalse(t.isAlive(), "线程已结束");
            }
        }
    }

    @Test
    void exceptionsDoNotKillTheThread() throws Exception {
        ExecutorEventLoop loop = new ExecutorEventLoop("测试网络线程 2");
        AtomicInteger ran = new AtomicInteger();
        loop.execute(() -> {
            throw new IllegalStateException("测试用的异常");
        });
        loop.execute(ran::incrementAndGet);
        assertTrue(loop.shutdown(5000));
        assertEquals(1, ran.get());
    }
}
