package moe.qingu.youran.api.network;

import moe.qingu.youran.api.network.stack.NetStack;
import moe.qingu.youran.api.network.tls.TlsServerContext;

import java.util.concurrent.CompletableFuture;
import java.util.function.Supplier;

/** 机房主机的运行环境（在网络线程上） */
public interface HostContext {
    /** 登记名（如 {@code youranmod:test}） */
    String key();

    Ipv6Address address();

    /** 这台主机的协议栈（只有这一个地址，跑在网络线程上） */
    NetStack stack();

    /**
     * 要动游戏状态时（扣物品、发货……）交给服务端主线程做；做完 future 在网络线程上完成，再回包。
     * 服务器正在关时以异常结束。
     */
    <T> CompletableFuture<T> onMainThread(Supplier<T> task);

    /**
     * 这台主机做 HTTPS（443）用的 TLS：证书由服务器的中间 CA 按客户端要的名字（SNI）现签，只签 {@code names} 里的名字
     * （可以写 Unicode，转成 A-label）。名字由这台主机自己给，和 DNS 注册无关。服务器没配中间 CA、或 {@code names} 为空时为 null（只开 HTTP）。
     */
    default TlsServerContext tls(java.util.Collection<String> names) {
        return null;
    }
}
