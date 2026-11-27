package moe.qingu.youran.client.network;

import moe.qingu.youran.api.network.ClientHost;
import moe.qingu.youran.api.network.Ipv6Address;
import moe.qingu.youran.api.network.stack.DnsResolver;
import moe.qingu.youran.api.network.stack.HttpClient;
import moe.qingu.youran.api.network.stack.NetStack;
import moe.qingu.youran.api.network.stack.NetworkException;
import moe.qingu.youran.api.network.stack.NtpClient;
import moe.qingu.youran.api.network.stack.PingResult;
import net.minecraft.client.Minecraft;
import net.minecraft.command.CommandBase;
import net.minecraft.command.CommandException;
import net.minecraft.command.ICommandSender;
import net.minecraft.command.WrongUsageException;
import net.minecraft.server.MinecraftServer;
import net.minecraft.util.text.TextComponentTranslation;

import java.time.Instant;
import java.util.concurrent.CompletionException;

/**
 * 客户端测试命令 /ynetc：用本机（客户端）上的协议栈 ping、查 DNS、取网页、对时，验证客户端的网口。
 * {@code status} 列出现在的网口；{@code -i <网口号>} 绑一个网口（如 /ynet device bind 接上的测试网卡），不绑的走默认网口（玩家自己的）。
 * 谁都能用（只动自己客户端上的协议栈）。
 */
public final class ClientNetCommand extends CommandBase {
    private static final long TIMEOUT_MS = 15_000;

    @Override
    public String getName() {
        return "ynetc";
    }

    @Override
    public String getUsage(ICommandSender sender) {
        return "youranmod.net.client.usage";
    }

    @Override
    public int getRequiredPermissionLevel() {
        return 0;
    }

    @Override
    public boolean checkPermission(MinecraftServer server, ICommandSender sender) {
        return true;
    }

    /** 用哪个网口：绑了（-i）就是它，否则不绑（走默认网口），DNS 问默认网口（玩家自己的网口）的递归解析器 */
    private static final class Via {
        final NetStack stack;
        final NetStack.Interface iface;
        final DnsResolver dns;
        final Ipv6Address ntp;

        Via(NetStack stack, NetStack.Interface iface, DnsResolver dns, Ipv6Address ntp) {
            this.stack = stack;
            this.iface = iface;
            this.dns = dns;
            this.ntp = ntp;
        }
    }

    @Override
    public void execute(MinecraftServer server, ICommandSender sender, String[] args) throws CommandException {
        // /ynetc -i <网口号> ...：绑这个网口
        int bind = -1;
        if (args.length >= 2 && args[0].equals("-i")) {
            bind = parseInt(args[1], 0, 255);
            args = java.util.Arrays.copyOfRange(args, 2, args.length);
        }
        String sub = args.length > 0 ? args[0] : "status";
        NetStack stack = ClientHost.stack();
        if (sub.equals("status")) {
            java.util.List<ClientHost.Port> ports = ClientHost.ports();
            if (ports.isEmpty()) {
                say("youranmod.net.client.no_ports");
            }
            for (ClientHost.Port p : ports) {
                say("youranmod.net.client.port", p.id(), p.name(), p.address(), p.resolverAddress(), p.ntpAddress());
                NetStack.Interface i = p.iface();
                if (i.doesNeighborDiscovery()) {
                    // 玩家网口：链路本地地址、默认路由器、DHCPv6 租约（在客户端网络线程上读）
                    i.stack().loop().execute(() -> {
                        moe.qingu.youran.api.network.stack.Dhcpv6Client d = i.dhcp();
                        moe.qingu.youran.api.network.stack.Dhcpv6Client.Lease l = d == null ? null : d.lease();
                        say("youranmod.net.client.link", i.linkLocalAddress(), i.neighborDiscovery().defaultRouters(), i.neighborDiscovery().mtu());
                        if (l == null) {
                            say("youranmod.net.client.no_lease", d == null ? "-" : d.state().name());
                        } else {
                            say("youranmod.net.client.lease", l.address, seconds(l.validLifetime), seconds(l.t1), l.dnsServers, l.ntpServers, l.server);
                        }
                    });
                }
            }
            return;
        }
        Via v;
        if (bind >= 0) {
            ClientHost.Port p = ClientHost.port(bind);
            if (p == null || p.isRemoved()) {
                say("youranmod.net.client.no_port", bind);
                return;
            }
            v = new Via(p.stack(), p.iface(), p.dns(), p.ntpAddress());
        } else {
            ClientHost.Port p = ClientHost.playerPort();
            if (stack == null || p == null) {
                say("youranmod.net.client.no_default");
                return;
            }
            if (p.address() == null || p.resolverAddress() == null) {
                say("youranmod.net.client.not_configured");
                return;
            }
            v = new Via(stack, null, new DnsResolver(stack, p.resolverAddress()), p.ntpAddress());
        }
        switch (sub) {
            case "ping": {
                if (args.length < 2) {
                    throw new WrongUsageException("youranmod.net.client.usage");
                }
                String target = args[1];
                int count = args.length > 2 ? parseInt(args[2], 1, 20) : 4;
                v.dns.resolveAaaa(target).whenComplete((r, err) -> {
                    if (err != null || r.addresses.isEmpty()) {
                        say("youranmod.net.client.failed", err != null ? message(err) : "NXDOMAIN " + target);
                        return;
                    }
                    ping(v, r.addresses.get(0), 0, count);
                });
                break;
            }
            case "dns": {
                if (args.length < 2) {
                    throw new WrongUsageException("youranmod.net.client.usage");
                }
                String name = args[1];
                v.dns.resolveAaaa(name).whenComplete((r, err) -> {
                    if (err != null) {
                        say("youranmod.net.client.failed", message(err));
                    } else {
                        say("youranmod.net.client.dns.result", name, r.rcode, r.addresses.toString());
                    }
                });
                break;
            }
            case "get": {
                if (args.length < 2) {
                    throw new WrongUsageException("youranmod.net.client.usage");
                }
                HttpClient.fetch(v.stack, v.iface, v.dns, args[1], TIMEOUT_MS).whenComplete((r, err) -> {
                    if (err != null) {
                        say("youranmod.net.client.failed", message(err));
                        return;
                    }
                    say("youranmod.net.client.get.result", r.status, r.body.length, r.header("Content-Type"));
                    for (String line : r.bodyText().split("\n")) {
                        if (!line.trim().isEmpty()) {
                            say("youranmod.net.client.line", line);
                        }
                    }
                });
                break;
            }
            case "ntp":
                if (v.ntp == null) {
                    say("youranmod.net.client.failed", "NTP");
                    break;
                }
                NtpClient.query(v.stack, v.iface, v.ntp).whenComplete((r, err) -> {
                    if (err != null) {
                        say("youranmod.net.client.failed", message(err));
                    } else {
                        say("youranmod.net.client.ntp.result", Instant.ofEpochMilli(r.serverTimeMillis).toString(), r.offsetMillis, r.roundTripMillis);
                    }
                });
                break;
            default:
                throw new WrongUsageException("youranmod.net.client.usage");
        }
    }

    private static void ping(Via v, Ipv6Address addr, int i, int count) {
        (v.iface != null ? v.stack.pingVia(v.iface, addr, 56, 2000) : v.stack.ping(null, addr, 56, 2000)).whenComplete((r, err) -> {
            if (err != null) {
                say("youranmod.net.client.failed", message(err));
                return;
            }
            if (r.status == PingResult.Status.REPLY) {
                say("youranmod.net.client.ping.reply", r.bytes, r.from.toString(), i + 1, r.rttMillis);
            } else if (r.status == PingResult.Status.UNREACHABLE) {
                say("youranmod.net.client.ping.unreachable", r.from.toString(), r.code);
            } else if (r.status == PingResult.Status.TIME_EXCEEDED) {
                say("youranmod.net.client.ping.time_exceeded", r.from.toString());
            } else {
                say("youranmod.net.client.ping.timeout", i + 1);
            }
            if (i + 1 < count && !v.stack.isClosed() && (v.iface == null || !v.iface.isRemoved())) {
                v.stack.loop().schedule(() -> ping(v, addr, i + 1, count), 1000);
            }
        });
    }

    private static String seconds(long s) {
        return s == moe.qingu.youran.api.network.packet.Dhcpv6Message.INFINITY ? "∞" : String.valueOf(s);
    }

    private static String message(Throwable t) {
        Throwable c = t instanceof CompletionException && t.getCause() != null ? t.getCause() : t;
        return c instanceof NetworkException ? c.getMessage() + " (" + ((NetworkException) c).reason + ")" : String.valueOf(c);
    }

    /** 任意线程：转到客户端主线程，在聊天栏显示一行（按客户端语言） */
    private static void say(String key, Object... args) {
        Object[] a = new Object[args.length];
        for (int i = 0; i < args.length; i++) {
            a[i] = String.valueOf(args[i]);
        }
        Minecraft mc = Minecraft.getMinecraft();
        mc.addScheduledTask(() -> {
            if (mc.player != null) {
                mc.player.sendMessage(new TextComponentTranslation(key, a));
            }
        });
    }
}
