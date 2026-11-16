package moe.qingu.youran.api.network;

/** 原始主机的接收方（见 {@link RawHost#setReceiver}） */
@FunctionalInterface
public interface RawPacketReceiver {
    /**
     * 收到一个发往这台主机的完整 IPv6 包。在网络线程上调用，<b>不能阻塞</b>（排进自己的队列就返回）。
     * {@code packet} 是给你的副本，可以留着。返回 false 表示现在收不了（例如隧道没连上），路由器回“目标不可达”。
     * 抛出异常按返回 false 算。
     */
    boolean receive(byte[] packet);
}
