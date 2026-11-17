package moe.qingu.youran.server.net.ca;

import java.util.concurrent.CompletableFuture;

/** 证书服务核对认证的办法（真的网络见 {@link NetworkValidator}；测试可以换）。future 完成时为 null 表示通过，否则是失败的原因 */
public interface Validator {
    /** GET {@code http://<name>/.well-known/acme-challenge/<token>}，内容要是 {@code expected} */
    CompletableFuture<String> http(String name, String token, String expected);

    /** 查 {@code recordName} 的 TXT，要有一条是 {@code expected} */
    CompletableFuture<String> dns(String recordName, String expected);
}
