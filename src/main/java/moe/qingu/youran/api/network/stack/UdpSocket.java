package moe.qingu.youran.api.network.stack;

import moe.qingu.youran.api.network.Ipv6Address;
import moe.qingu.youran.api.network.packet.UdpDatagram;

/** 打开的 UDP 端口（{@link NetStack#openUdp}）。发送可以从任意线程调用；回调在事件循环线程上。 */
public final class UdpSocket {
    private final NetStack stack;
    private final Ipv6Address local;
    private final int port;
    private final Handler handler;
    private boolean closed;

    UdpSocket(NetStack stack, Ipv6Address local, int port, Handler handler) {
        this.stack = stack;
        this.local = local;
        this.port = port;
        this.handler = handler;
    }

    /** 绑定的本机地址；null 表示所有地址 */
    public Ipv6Address localAddress() {
        return local;
    }

    public int port() {
        return port;
    }

    /**
     * 发一个数据报（超过 {@link UdpDatagram#MAX_DATA} 字节时丢掉并返回 false）。
     * 源地址：绑定了地址就用它，否则用 {@code source}（null 为本机第一个地址）。
     */
    public boolean send(Ipv6Address source, Ipv6Address destination, int destinationPort, byte[] data) {
        if (data.length > UdpDatagram.MAX_DATA) {
            return false;
        }
        byte[] copy = data.clone();
        stack.run(() -> {
            if (closed) {
                return;
            }
            Ipv6Address src = local != null ? local : source != null ? source : stack.primaryAddress();
            if (src == null) {
                return;
            }
            stack.send(new UdpDatagram(port, destinationPort, copy).toPacket(src, destination));
        });
        return true;
    }

    public boolean send(Ipv6Address destination, int destinationPort, byte[] data) {
        return send(null, destination, destinationPort, data);
    }

    public void close() {
        stack.run(this::closeQuietly);
    }

    void closeQuietly() {
        if (!closed) {
            closed = true;
            stack.unbindUdp(this);
        }
    }

    /** 绑着的网口去掉了：关掉并告诉 {@link Handler#onClosed} */
    void closeWith(NetworkException reason) {
        if (closed) {
            return;
        }
        closeQuietly();
        if (handler != null) {
            try {
                handler.onClosed(this, reason);
            } catch (Throwable t) {
                NetStack.LOG.error("UDP 回调出错", t);
            }
        }
    }

    void deliver(Ipv6Address from, int fromPort, Ipv6Address to, byte[] data) {
        if (!closed && handler != null) {
            try {
                handler.onDatagram(this, from, fromPort, to, data);
            } catch (Throwable t) {
                NetStack.LOG.error("UDP 回调出错", t);
            }
        }
    }

    void onError(Ipv6Address destination, int icmpType, int icmpCode, Ipv6Address reporter) {
        if (!closed && handler != null) {
            try {
                handler.onError(this, destination, icmpType, icmpCode, reporter);
            } catch (Throwable t) {
                NetStack.LOG.error("UDP 回调出错", t);
            }
        }
    }

    public interface Handler {
        /** 收到数据报（{@code to} 是本机哪个地址收到的，回包时用它当源地址） */
        void onDatagram(UdpSocket socket, Ipv6Address from, int fromPort, Ipv6Address to, byte[] data);

        /** 发出去的数据报收到 ICMPv6 差错（如端口不可达）。默认不管 */
        default void onError(UdpSocket socket, Ipv6Address destination, int icmpType, int icmpCode, Ipv6Address reporter) {
        }

        /** 不是自己关的、被关掉了（绑着的网口去掉了，见 {@link NetStack#removeInterface}）。默认不管 */
        default void onClosed(UdpSocket socket, NetworkException reason) {
        }
    }
}
