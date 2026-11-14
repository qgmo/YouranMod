package moe.qingu.youran.api.network.stack;

/** 协议栈发出的原始 IPv6 包交给谁（服务端是路由器，客户端是发往服务器的 Minecraft 网络包）。在事件循环线程上调用。 */
@FunctionalInterface
public interface PacketOutput {
    void send(byte[] packet);
}
