package moe.qingu.youran.api.network.stack;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.ScheduledThreadPoolExecutor;
import java.util.concurrent.TimeUnit;

/**
 * 用一条自己的守护线程实现的 {@link EventLoop}。任务里抛出的异常记日志后继续；停下以后再提交的任务安静丢掉。
 */
public final class ExecutorEventLoop implements EventLoop {
    private static final Logger LOG = LogManager.getLogger("YouranMod");

    private final ScheduledThreadPoolExecutor executor;
    private final String name;
    private volatile Thread thread;

    public ExecutorEventLoop(String threadName) {
        this.name = threadName;
        this.executor = new ScheduledThreadPoolExecutor(1, r -> {
            Thread t = new Thread(r, threadName);
            t.setDaemon(true);
            thread = t;
            return t;
        });
        executor.setRemoveOnCancelPolicy(true);
        executor.setExecuteExistingDelayedTasksAfterShutdownPolicy(false);
        executor.setContinueExistingPeriodicTasksAfterShutdownPolicy(false);
    }

    @Override
    public void execute(Runnable task) {
        try {
            executor.execute(guard(task));
        } catch (RejectedExecutionException e) {
            // 已停下
        }
    }

    @Override
    public Timer schedule(Runnable task, long delayMillis) {
        try {
            ScheduledFuture<?> f = executor.schedule(guard(task), Math.max(0, delayMillis), TimeUnit.MILLISECONDS);
            return () -> f.cancel(false);
        } catch (RejectedExecutionException e) {
            return () -> {
            };
        }
    }

    @Override
    public long nowMillis() {
        return System.nanoTime() / 1_000_000L;
    }

    @Override
    public boolean inEventLoop() {
        return Thread.currentThread() == thread;
    }

    public boolean isShutdown() {
        return executor.isShutdown();
    }

    /**
     * 停下：先等已经排进来的任务（{@link #execute} 交的）做完，再不收新任务；还没到时间的计时器丢掉；超时就打断。
     * 返回是否在时限内停干净。
     */
    public boolean shutdown(long timeoutMillis) {
        if (!inEventLoop() && !executor.isShutdown()) {
            // ScheduledThreadPoolExecutor 的 execute 也是“延迟 0 的计划任务”，shutdown 时会被丢掉；先排一个标记等它执行到
            CountDownLatch drained = new CountDownLatch(1);
            execute(drained::countDown);
            try {
                drained.await(timeoutMillis, TimeUnit.MILLISECONDS);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        }
        executor.shutdown();
        try {
            if (executor.awaitTermination(timeoutMillis, TimeUnit.MILLISECONDS)) {
                return true;
            }
            executor.shutdownNow();
            return executor.awaitTermination(timeoutMillis, TimeUnit.MILLISECONDS);
        } catch (InterruptedException e) {
            executor.shutdownNow();
            Thread.currentThread().interrupt();
            return false;
        }
    }

    private Runnable guard(Runnable task) {
        return () -> {
            try {
                task.run();
            } catch (Throwable t) {
                LOG.error("线程 {} 里的网络任务出错", name, t);
            }
        };
    }
}
