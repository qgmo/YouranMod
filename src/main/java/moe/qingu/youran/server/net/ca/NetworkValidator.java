package moe.qingu.youran.server.net.ca;

import moe.qingu.youran.api.network.Ipv6Address;
import moe.qingu.youran.api.network.ca.Challenge;
import moe.qingu.youran.api.network.packet.DnsMessage;
import moe.qingu.youran.api.network.stack.DnsResolver;
import moe.qingu.youran.api.network.stack.HttpClient;
import moe.qingu.youran.api.network.stack.NetStack;
import moe.qingu.youran.api.network.stack.NetworkException;

import java.nio.charset.StandardCharsets;
import java.util.Collections;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;

/**
 * 经真的网络核对认证：在证书服务那台主机（{@code ca.yr}）的协议栈上，像普通客户端一样问递归解析器、发 HTTP 请求。
 * 不直接看 DNS 服务、网站的数据。在网络线程上用。
 */
public final class NetworkValidator implements Validator {
    /** 一次 HTTP 认证最多等这么久 */
    static final long HTTP_TIMEOUT_MS = 10_000;
    /** 认证页最多这么长 */
    static final int MAX_CONTENT = 1024;

    private final NetStack stack;
    private final DnsResolver resolver;

    public NetworkValidator(NetStack stack, Ipv6Address resolverAddress) {
        this.stack = stack;
        this.resolver = new DnsResolver(stack, resolverAddress);
    }

    @Override
    public CompletableFuture<String> http(String name, String token, String expected) {
        HttpClient.Url url = HttpClient.Url.parse("http://" + name + Challenge.HTTP_PATH + token);
        if (url == null) {
            return CompletableFuture.completedFuture("网址不对");
        }
        return HttpClient.send(stack, resolver, url, "GET", Collections.singletonMap("Accept", "text/plain"), null, HTTP_TIMEOUT_MS, null)
                .handle((r, err) -> {
                    if (err != null) {
                        return describe(err, "取 " + url);
                    }
                    if (r.status != 200) {
                        return url + " 回了 HTTP " + r.status;
                    }
                    if (r.body.length > MAX_CONTENT) {
                        return url + " 的内容太长（" + r.body.length + " 字节）";
                    }
                    String got = new String(r.body, StandardCharsets.UTF_8).trim();
                    return got.equals(expected) ? null : url + " 的内容不对";
                });
    }

    @Override
    public CompletableFuture<String> dns(String recordName, String expected) {
        return resolver.query(recordName, DnsMessage.TYPE_TXT).handle((m, err) -> {
            if (err != null) {
                return describe(err, "查 " + recordName + " 的 TXT");
            }
            if (m.rcode == DnsMessage.RCODE_NXDOMAIN) {
                return "没有 " + recordName + " 这个名字";
            }
            if (m.rcode != DnsMessage.RCODE_OK) {
                return "查 " + recordName + " 的 TXT 出错（DNS 响应码 " + m.rcode + "）";
            }
            int n = 0;
            for (DnsMessage.Record rec : m.answers) {
                if (rec.type == DnsMessage.TYPE_TXT) {
                    n++;
                    if (expected.equals(rec.text())) {
                        return null;
                    }
                }
            }
            return n == 0 ? recordName + " 没有 TXT 记录" : recordName + " 的 TXT 记录不对（有 " + n + " 条，没有要的值）";
        });
    }

    static String describe(Throwable err, String what) {
        Throwable c = err instanceof CompletionException && err.getCause() != null ? err.getCause() : err;
        if (c instanceof NetworkException) {
            NetworkException e = (NetworkException) c;
            return e.reason == NetworkException.Reason.TIMEOUT ? what + " 超时" : what + " 失败：" + e.getMessage();
        }
        return what + " 失败：" + c;
    }
}
