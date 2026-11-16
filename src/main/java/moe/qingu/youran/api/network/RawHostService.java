package moe.qingu.youran.api.network;

/** 原始主机启动时的回调（见 {@link YouranNetworkApi#registerRawHost}） */
@FunctionalInterface
public interface RawHostService {
    /**
     * 网络启动、地址分好以后在网络线程上调用一次（每次开服一次），不要阻塞。
     * 拿到的 {@link RawHost} 一直用到关服（关服后 {@link RawHost#isOpen()} 为 false）。
     */
    void start(RawHost host);
}
