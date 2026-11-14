package moe.qingu.youran.api.network.tls;

/** 测试用：包内的方法 */
public final class TlsTrustAccess {
    private TlsTrustAccess() {
    }

    public static void onlyProtocols(java.util.List<String> protocols) {
        TlsConnection.testOnlyProtocols = protocols;
    }

    public static boolean dnsMatches(String pattern, String host) {
        return TlsTrust.dnsMatches(pattern, host);
    }
}
