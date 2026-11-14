package moe.qingu.youran.api.network.stack;

/**
 * 协议栈跑在上面的单线程事件循环：所有包处理、计时器、回调都在这一个线程上，按提交顺序执行。
 * 服务端是模组的网络线程，客户端是客户端的网络线程；单元测试用可以手动拨时间的实现。
 */
public interface EventLoop {
    /** 排进队列，尽快执行（任意线程都可以调用） */
    void execute(Runnable task);

    /** 过 {@code delayMillis} 毫秒后执行 */
    Timer schedule(Runnable task, long delayMillis);

    /** 单调时钟（毫秒），只用来算间隔 */
    long nowMillis();

    /** 当前线程是不是这个事件循环的线程 */
    boolean inEventLoop();

    interface Timer {
        void cancel();
    }
}
