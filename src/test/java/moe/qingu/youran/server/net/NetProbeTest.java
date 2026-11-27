package moe.qingu.youran.server.net;

import moe.qingu.youran.api.network.Ipv6Address;
import moe.qingu.youran.api.network.dns.DnsServer;
import moe.qingu.youran.api.network.stack.DnsResolver;
import moe.qingu.youran.api.network.stack.NetStack;
import moe.qingu.youran.network.NetProbe;
import moe.qingu.youran.server.net.service.NtpServer;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Random;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@code /ynet} 的 ping、trace、dns、ntp 从执行者自己的玩家地址发出（服主 11-27）：在玩家客户端的协议栈上跑 {@link NetProbe}，
 * 绑他的玩家网口、用 DHCPv6 给的 DNS 与 NTP。能用 /ynet 的人不过防火墙；普通玩家照旧受限。权限判断和命令本身是同一个。
 */
class NetProbeTest {
    final TestNetwork net = new TestNetwork();
    final Ipv6Address webAddr = net.layout.hostAddress(0, 0x80);
    final NetStack web;

    NetProbeTest() {
        net.enablePlayers(new Random(8), 50, 400);
        NetStack dns = net.host(net.resolverAddr);
        NetStack ntp = net.host(net.ntpAddr);
        web = net.host(webAddr);
        net.settle();
        // 递归解析器那台换成一台管 test.yr 的 DNS（查询带 RD，它照样按区数据回答）
        DnsServer d = DnsServer.start(dns);
        d.addZone("test.yr");
        d.setAddresses("test.yr", Collections.singletonList(webAddr));
        new NtpServer(() -> 1_794_355_200_000L).start(ntp);
        net.loop.drain();
    }

    List<String> run(TestNetwork.Player p, String op, String... args) {
        List<String> out = new ArrayList<>();
        boolean[] done = new boolean[1];
        NetStack.Interface iface = p.port().iface();
        new NetProbe(p.stack(), iface, new DnsResolver(p.stack(), iface, iface.dnsServer()), iface.ntpServer()).run(op, args, new NetProbe.Out() {
            @Override
            public void line(String text) {
                out.add(text);
            }

            @Override
            public void done() {
                done[0] = true;
            }
        });
        net.loop.advance(20_000);
        assertTrue(done[0], "跑完了：" + out);
        return out;
    }

    static boolean any(List<String> lines, String part) {
        for (String l : lines) {
            if (l.contains(part)) {
                return true;
            }
        }
        return false;
    }

    @Test
    void adminRunsFromHisOwnPlayerAddress() {
        TestNetwork.Player admin = net.online("Admin", true);
        List<String> ping = run(admin, "ping", "test.yr", "2");
        assertTrue(any(ping, "从 " + admin.address()), "从他的玩家地址发出：" + ping);
        assertTrue(any(ping, "字节来自 " + webAddr), String.valueOf(ping));
        assertTrue(any(ping, "收到 2 个回应"), String.valueOf(ping));
        List<String> trace = run(admin, "trace", webAddr.toString());
        assertTrue(any(trace, "  1  " + net.address(RouterNetwork.PLAYERS)), String.valueOf(trace));
        assertTrue(any(trace, "  2  " + net.gameplay().address), String.valueOf(trace));
        assertTrue(any(trace, "  3  " + net.routerAddr), String.valueOf(trace));
        assertTrue(any(trace, "  4  " + webAddr) && any(trace, "到了"), String.valueOf(trace));
        List<String> dns = run(admin, "dns", "test.yr");
        assertTrue(any(dns, "NOERROR") && any(dns, webAddr.toString()), String.valueOf(dns));
        List<String> ntp = run(admin, "ntp");
        assertTrue(any(ntp, "层级 3"), String.valueOf(ntp));
    }

    @Test
    void plainPlayerIsStillFirewalled() {
        TestNetwork.Player plain = net.online("Plain");
        List<String> ping = run(plain, "ping", webAddr.toString(), "1");
        assertTrue(any(ping, "被禁止"), "普通玩家不能 ping：" + ping);
        List<String> dns = run(plain, "dns", "test.yr");
        assertTrue(any(dns, webAddr.toString()), "DNS 照样能查：" + dns);
        assertTrue(any(run(plain, "ntp"), "层级 3"), "NTP 也放行了");
    }

    @Test
    void noAddressYetSaysSo() {
        TestNetwork.Player early = net.new Player("Early", false);
        net.loop.drain();
        List<String> out = new ArrayList<>();
        NetStack.Interface iface = early.port().iface();
        new NetProbe(early.stack(), iface, new DnsResolver(early.stack(), iface, net.resolverAddr), null).run("ping", new String[]{"test.yr"}, new NetProbe.Out() {
            @Override
            public void line(String text) {
                out.add(text);
            }

            @Override
            public void done() {
            }
        });
        assertTrue(any(out, "没有可用的源地址"), String.valueOf(out));
    }

    @Test
    void permissionIsTheCommandsOwn() {
        NetCommand c = new NetCommand(null);
        assertEquals(NetCommand.PERMISSION_LEVEL, c.getRequiredPermissionLevel(), "上线时判断“不过防火墙”用的就是命令的权限");
        assertEquals(NetCommand.NAME, c.getName());
        assertEquals(2, NetCommand.PERMISSION_LEVEL, "管理员");
    }
}
