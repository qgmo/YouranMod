package moe.qingu.youran.client.network;

import moe.qingu.youran.api.network.ClientNic;
import moe.qingu.youran.api.network.Ipv6Address;
import moe.qingu.youran.api.network.stack.HttpClient;
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
 * 客户端测试命令 /ynetc：用本机（客户端）上的协议栈 ping、查 DNS、取网页、对时，验证“网卡”这条路。
 * 只有服务器把这个玩家接到某台设备上（如 /ynet device bind）以后才能用。谁都能用（只动自己客户端上的协议栈）。
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

    @Override
    public void execute(MinecraftServer server, ICommandSender sender, String[] args) throws CommandException {
        ClientNic.Session s = ClientNic.current();
        String sub = args.length > 0 ? args[0] : "status";
        if (s == null) {
            say("youranmod.net.client.detached");
            return;
        }
        switch (sub) {
            case "status":
                say("youranmod.net.client.status", s.address(), s.resolverAddress(), s.ntpAddress());
                break;
            case "ping": {
                if (args.length < 2) {
                    throw new WrongUsageException("youranmod.net.client.usage");
                }
                String target = args[1];
                int count = args.length > 2 ? parseInt(args[2], 1, 20) : 4;
                s.dns().resolveAaaa(target).whenComplete((r, err) -> {
                    if (err != null || r.addresses.isEmpty()) {
                        say("youranmod.net.client.failed", err != null ? message(err) : "NXDOMAIN " + target);
                        return;
                    }
                    ping(s, r.addresses.get(0), 0, count);
                });
                break;
            }
            case "dns": {
                if (args.length < 2) {
                    throw new WrongUsageException("youranmod.net.client.usage");
                }
                String name = args[1];
                s.dns().resolveAaaa(name).whenComplete((r, err) -> {
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
                HttpClient.fetch(s.stack(), s.dns(), args[1], TIMEOUT_MS).whenComplete((r, err) -> {
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
                NtpClient.query(s.stack(), s.ntpAddress()).whenComplete((r, err) -> {
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

    private static void ping(ClientNic.Session s, Ipv6Address addr, int i, int count) {
        s.stack().ping(null, addr, 56, 2000).whenComplete((r, err) -> {
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
            if (i + 1 < count && !s.isClosed()) {
                s.stack().loop().schedule(() -> ping(s, addr, i + 1, count), 1000);
            }
        });
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
