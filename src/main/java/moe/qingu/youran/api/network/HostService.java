package moe.qingu.youran.api.network;

/**
 * 机房里一个 /128 主机上跑的服务（网站、DNS 服务器等）。网络启动时在网络线程上调用 {@link #start}，
 * 在 {@link HostContext#stack()} 上开 UDP 端口、监听 TCP 端口。关服时协议栈整个关掉，不用自己收拾。
 */
@FunctionalInterface
public interface HostService {
    void start(HostContext context);
}
