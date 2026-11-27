package moe.qingu.youran.client.network;

import moe.qingu.youran.api.network.ClientHost;
import moe.qingu.youran.api.network.stack.DnsResolver;
import moe.qingu.youran.api.network.stack.NetStack;
import moe.qingu.youran.net.ProbeMessages;
import moe.qingu.youran.net.YouranNetwork;
import moe.qingu.youran.network.NetProbe;

/**
 * 客户端：服务器叫跑的 {@code /ynet} 子命令（{@link ProbeMessages.Run}）。从玩家自己的网口跑（{@link NetProbe}），
 * DNS 问这个网口的递归解析器、NTP 问这个网口的 NTP 服务器（都是 DHCPv6 给的），结果一行一行回给服务器。
 */
public final class ClientProbes {
    /** 一次最多回多少行（服务器那边也有上限） */
    static final int MAX_LINES = 200;

    private ClientProbes() {
    }

    /** Netty 线程上收到：转到客户端网络线程上跑 */
    public static void run(long id, String op, String[] args) {
        ClientHost.Port p = ClientHost.playerPort();
        if (p == null || p.isRemoved() || !NetProbe.handles(op)) {
            send(id, "§c这个客户端没有玩家网口（服务器没开玩家地址，或还没接上）", true);
            return;
        }
        NetStack stack = p.stack();
        stack.loop().execute(() -> {
            NetStack.Interface iface = p.iface();
            if (iface.address() == null || iface.dnsServer() == null) {
                send(id, "§c玩家网口还没用 DHCPv6 租到地址，过一两秒再试", true);
                return;
            }
            int[] lines = new int[1];
            new NetProbe(stack, iface, new DnsResolver(stack, iface, iface.dnsServer()), iface.ntpServer()).run(op, args, new NetProbe.Out() {
                @Override
                public void line(String text) {
                    if (++lines[0] <= MAX_LINES) {
                        send(id, text, false);
                    }
                }

                @Override
                public void done() {
                    send(id, "", true);
                }
            });
        });
    }

    private static void send(long id, String text, boolean done) {
        YouranNetwork.CHANNEL.sendToServer(new ProbeMessages.Line(id, text, done));
    }
}
