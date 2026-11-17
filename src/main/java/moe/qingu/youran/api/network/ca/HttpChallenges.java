package moe.qingu.youran.api.network.ca;

import moe.qingu.youran.api.network.stack.NetStack;

import java.util.HashMap;
import java.util.Map;
import java.util.WeakHashMap;

/**
 * HTTP 认证要回的内容，按主机（协议栈）放：{@link CertificateClient} 申请证书时放进来，认证完拿走；
 * 网站的 80 口在处理请求之前问一下 {@link #answer}，是认证请求就照回。
 * <p>
 * YouranMod 的 {@code api.network.http.HttpServer} 在不加密的口上自动这样做；自己写 HTTP 服务器的网站（如家具模组的 WebServer）
 * 要自己在处理请求前调用 {@link #answer}。任意线程都可以调用。
 */
public final class HttpChallenges {
    private static final Map<NetStack, Map<String, String>> STORE = new WeakHashMap<>();

    private HttpChallenges() {
    }

    /**
     * 一个 GET / HEAD 请求是不是这台主机正在等的 HTTP 认证：是的话返回要回的内容（回 200、{@code text/plain}），不是返回 null（照常处理）。
     *
     * @param path 请求的路径（不含 {@code ?} 以后的部分）
     */
    public static String answer(NetStack stack, String path) {
        if (stack == null || path == null || !path.startsWith(Challenge.HTTP_PATH)) {
            return null;
        }
        String token = path.substring(Challenge.HTTP_PATH.length());
        synchronized (STORE) {
            Map<String, String> m = STORE.get(stack);
            return m == null ? null : m.get(token);
        }
    }

    static void put(NetStack stack, String token, String content) {
        synchronized (STORE) {
            STORE.computeIfAbsent(stack, s -> new HashMap<>()).put(token, content);
        }
    }

    static void remove(NetStack stack, String token) {
        synchronized (STORE) {
            Map<String, String> m = STORE.get(stack);
            if (m != null) {
                m.remove(token);
                if (m.isEmpty()) {
                    STORE.remove(stack);
                }
            }
        }
    }
}
