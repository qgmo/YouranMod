package moe.qingu.youran.api.network.stack;

import java.util.PriorityQueue;

/** 单元测试用的事件循环：时间手动拨，任务按（时间，提交顺序）执行，全在调用测试的线程上 */
public final class ManualEventLoop implements EventLoop {
    private final PriorityQueue<Task> queue = new PriorityQueue<>();
    private long now;
    private long seq;
    private boolean running;

    private final class Task implements Comparable<Task>, Timer {
        final long at;
        final long order;
        final Runnable r;
        boolean cancelled;

        Task(long at, Runnable r) {
            this.at = at;
            this.order = seq++;
            this.r = r;
        }

        @Override
        public int compareTo(Task o) {
            return at != o.at ? Long.compare(at, o.at) : Long.compare(order, o.order);
        }

        @Override
        public void cancel() {
            cancelled = true;
        }
    }

    @Override
    public void execute(Runnable task) {
        queue.add(new Task(now, task));
    }

    @Override
    public Timer schedule(Runnable task, long delayMillis) {
        Task t = new Task(now + Math.max(0, delayMillis), task);
        queue.add(t);
        return t;
    }

    @Override
    public long nowMillis() {
        return now;
    }

    /** 测试线程就是“事件循环线程” */
    @Override
    public boolean inEventLoop() {
        return true;
    }

    /** 执行到 {@code until}（毫秒，绝对时间）为止的任务，时间拨到那里 */
    public void runUntil(long until) {
        if (running) {
            throw new IllegalStateException("重入");
        }
        running = true;
        try {
            while (!queue.isEmpty()) {
                Task t = queue.peek();
                if (t.at > until) {
                    break;
                }
                queue.poll();
                now = Math.max(now, t.at);
                if (!t.cancelled) {
                    t.r.run();
                }
            }
            now = Math.max(now, until);
        } finally {
            running = false;
        }
    }

    public void advance(long ms) {
        runUntil(now + ms);
    }

    /** 把眼下（不拨时间）能执行的都执行完 */
    public void drain() {
        runUntil(now);
    }

    public int pending() {
        int n = 0;
        for (Task t : queue) {
            if (!t.cancelled) {
                n++;
            }
        }
        return n;
    }
}
