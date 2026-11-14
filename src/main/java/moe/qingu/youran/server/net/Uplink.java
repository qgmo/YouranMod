package moe.qingu.youran.server.net;

/**
 * /48 路由器的“上行口”：目的地址在 /48 以外的包交给它；从它进来的包（{@link Router#fromUplink}）进路由器。
 * 现在没有实现（为 null 时发往 /48 以外的包回“无路由”）。以后由单独的 Go 小进程实现（TUN 设备、netlink 加路由，
 * 要 CAP_NET_ADMIN；游戏服务器本身不提权），通过本机套接字与模组相连。原始包格式与套接字协议见 temp 仓库
 * {@code docs/网络系统实现.md} 的“上行口”一节。
 */
public interface Uplink {
    /** 交出一个原始 IPv6 包（在网络线程上调用，不要阻塞） */
    void send(byte[] packet);
}
