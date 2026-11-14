package moe.qingu.youran.server.net;

import moe.qingu.youran.api.network.DeviceHandle;
import moe.qingu.youran.api.network.Ipv6Address;
import moe.qingu.youran.api.network.NetworkLayout;
import moe.qingu.youran.api.network.packet.DnsMessage;
import moe.qingu.youran.api.network.stack.DnsResolver;
import moe.qingu.youran.api.network.stack.EventLoop;
import moe.qingu.youran.api.network.stack.HttpClient;
import moe.qingu.youran.api.network.stack.NetStack;
import moe.qingu.youran.api.network.stack.NetworkException;
import moe.qingu.youran.api.network.stack.NtpClient;
import moe.qingu.youran.api.network.stack.PingResult;
import net.minecraft.command.CommandBase;
import net.minecraft.command.CommandException;
import net.minecraft.command.ICommandSender;
import net.minecraft.command.WrongUsageException;
import net.minecraft.entity.Entity;
import net.minecraft.entity.player.EntityPlayerMP;
import net.minecraft.server.MinecraftServer;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.text.TextComponentString;

import javax.annotation.Nullable;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.atomic.AtomicLong;

/**
 * 测试命令 /ynet（权限 2，管理员）。从某一格上的测试设备 ping、查 DNS、用 TCP 取网页、对时；列地址分配表；
 * 放、拿测试设备，把玩家当成某一格上的电脑；丢包与乱序的测试开关；压包。
 * 结果在网络线程上算出来，转回主线程再发给执行者。坐标可以写 ~（相对执行者）；维度不写时用执行者所在的维度（控制台为 0）。
 */
final class NetCommand extends CommandBase {
    private static final long PING_TIMEOUT_MS = 2000;
    private static final long HTTP_TIMEOUT_MS = 15_000;
    private final NetworkServer net;

    NetCommand(NetworkServer net) {
        this.net = net;
    }

    @Override
    public String getName() {
        return "ynet";
    }

    @Override
    public String getUsage(ICommandSender sender) {
        return "/ynet <table|stats|device|ping|dns|get|ntp|sim|stress|flush>";
    }

    @Override
    public int getRequiredPermissionLevel() {
        return 2;
    }

    private static final List<String> HELP = Arrays.asList(
            "§e/ynet —— 游戏内网络的测试命令",
            "§6/ynet table §f地址分配表",
            "§6/ynet stats §f路由器与网卡的统计",
            "§6/ynet players §f在线玩家的临时地址、连接数、放行的用途",
            "§6/ynet device add|remove <x> <y> <z> [维度] §f放、拿服务端测试设备",
            "§6/ynet device list §f列出测试设备",
            "§6/ynet device bind <玩家> <x> <y> <z> [维度] §f把玩家当成这一格上的电脑（协议栈在他的客户端上）",
            "§6/ynet device unbind <玩家>",
            "§6/ynet device power <x> <y> <z> on|off [维度]",
            "§6/ynet ping <x> <y> <z> <目标> [次数] [字节数] §f目标：IPv6 地址、坐标 x,y,z[,维度]、主机登记名、域名",
            "§6/ynet dns <x> <y> <z> <域名> §f经递归解析器查 AAAA",
            "§6/ynet get <x> <y> <z> <网址> §f用 TCP 取网页（如 http://test.yr/）",
            "§6/ynet ntp <x> <y> <z> §f向 NTP 服务器对时",
            "§6/ynet sim <丢包%> <乱序%> | off §f测试开关：路由器按概率丢包、推迟包",
            "§6/ynet stress <秒> <每秒包数> §f在网络线程上压包（机房里两台主机互相 ping）",
            "§6/ynet flush §f清掉递归解析器的缓存");

    @Override
    public void execute(MinecraftServer server, ICommandSender sender, String[] args) throws CommandException {
        if (!net.running()) {
            tell(sender, "§c游戏内网络没在运行");
            return;
        }
        String sub = args.length > 0 ? args[0] : "help";
        switch (sub) {
            case "table":
                table(sender);
                break;
            case "stats":
                stats(sender);
                break;
            case "players":
                players(sender);
                break;
            case "device":
                device(server, sender, args);
                break;
            case "ping":
                ping(sender, args);
                break;
            case "dns":
                dns(sender, args);
                break;
            case "get":
                get(sender, args);
                break;
            case "ntp":
                ntp(sender, args);
                break;
            case "sim":
                sim(sender, args);
                break;
            case "stress":
                stress(sender, args);
                break;
            case "flush":
                onLoop(() -> net.resolver().flush());
                tell(sender, "§a已清掉递归解析器的缓存");
                break;
            default:
                for (String line : HELP) {
                    tell(sender, line);
                }
                break;
        }
    }

    // ---- 分配表与统计 ----

    private void table(ICommandSender sender) {
        AddressBook b = net.book();
        AllocationTable t = net.table();
        tell(sender, "§e游戏内网络地址分配表");
        tell(sender, "§6前缀 §f" + b.layout.prefix() + "/48（全局 ID " + String.format("%010x", t.globalId) + "）");
        tell(sender, "§6坐标路由 §f" + new Ipv6Address(b.layout.prefix().high() | NetworkLayout.SUBNET_COORDINATES, 0) + "/64");
        for (Map.Entry<Integer, Integer> e : t.dimensions.entrySet()) {
            boolean active = b.byDimension.containsKey(e.getKey());
            tell(sender, "  维度 " + e.getKey() + " → 序号 " + e.getValue() + "：" + b.layout.dimensionPrefix(e.getValue()) + "/72"
                    + (active ? "" : " §7（不在全局 Wi-Fi 清单里，保留）"));
        }
        tell(sender, "§6玩法 §f" + new Ipv6Address(b.layout.prefix().high() | NetworkLayout.SUBNET_GAMEPLAY, 0) + "/64");
        for (Map.Entry<String, Long> e : t.datacenters.entrySet()) {
            boolean active = b.datacenters.containsKey(e.getKey());
            tell(sender, "  机房 " + e.getKey() + " → 序号 " + e.getValue() + "：" + b.layout.datacenterPrefix(e.getValue()) + "/96"
                    + (active ? "" : " §7（这次没登记，保留）"));
        }
        for (Map.Entry<String, AllocationTable.Host> e : t.hosts.entrySet()) {
            Ipv6Address a = b.hosts.get(e.getKey());
            Long dc = t.datacenters.get(e.getValue().datacenter);
            String addr = a != null ? a.toString() : dc != null ? b.layout.hostAddress(dc, e.getValue().hostPart).toString() : "?";
            tell(sender, "  主机 " + e.getKey() + " → " + addr + (a != null ? "" : " §7（这次没登记，保留）"));
        }
        tell(sender, "§6DNS §f根 root.yr；YouranMod 的顶级域 .yr .com" + (net.tlds().isEmpty() ? "" : "；其他模组的 ." + String.join(" .", net.tlds())));
        for (Map.Entry<String, String> e : net.names().entrySet()) {
            tell(sender, "  " + e.getKey() + " → " + e.getValue());
        }
    }

    private void stats(ICommandSender sender) {
        Router r = net.router();
        tell(sender, "§e游戏内网络统计");
        tell(sender, "转发 " + r.forwarded + "，交给设备 " + r.deliveredToDevices + "，交给机房主机 " + r.deliveredToHosts
                + "，回目标不可达 " + r.unreachableSent + "，回超时 " + r.timeExceededSent + "，坏包 " + r.malformed);
        tell(sender, "网卡收下 " + net.nic().accepted + "，丢掉 " + net.rejects().total()
                + "；测试开关：丢包 " + Math.round(r.lossRate() * 100) + "%（已丢 " + r.simulatedDrops + "），乱序 "
                + Math.round(r.reorderRate() * 100) + "%（已推迟 " + r.simulatedDelays + "）");
        RecursiveResolverStats.tell(this, sender, net);
    }

    private void players(ICommandSender sender) {
        PlayerNics p = net.players();
        if (p == null) {
            tell(sender, "§c玩家临时地址没开");
            return;
        }
        tell(sender, "§e玩家临时地址 §f" + net.book().layout.playersPrefix() + "/96，在线 " + p.online()
                + "；往外收下 " + p.outAccepted + "、丢掉 " + p.outRejected + "，往里交给玩家 " + p.inDelivered + "、丢掉 " + p.inRejected);
        onLoop(() -> {
            for (String line : p.describe()) {
                later(sender, "  " + line);
            }
        });
    }

    /** 递归解析器的统计要在网络线程上读 */
    private static final class RecursiveResolverStats {
        static void tell(NetCommand c, ICommandSender sender, NetworkServer net) {
            c.onLoop(() -> {
                String s = "递归解析器：查询 " + net.resolver().queries + "，命中缓存 " + net.resolver().cacheHits
                        + "，缓存条数 " + net.resolver().cacheSize();
                c.later(sender, s);
            });
        }
    }

    // ---- 设备 ----

    private void device(MinecraftServer server, ICommandSender sender, String[] args) throws CommandException {
        String op = args.length > 1 ? args[1] : "";
        TestDevices td = net.testDevices();
        switch (op) {
            case "add": {
                Place p = place(sender, args, 2);
                TestDevices.Emulated e = td.add(p.dim, p.pos.getX(), p.pos.getY(), p.pos.getZ());
                if (e == null) {
                    tell(sender, "§c" + p + " 没有地址（维度没有全局 Wi-Fi，或坐标超出范围）");
                } else {
                    tell(sender, "§a在 " + p + " 放了测试设备，地址 " + e.handle.address()
                            + (((DeviceTable.Device) e.handle).isLoaded() ? "" : " §e（区块没加载，加载前收发不了包）"));
                }
                break;
            }
            case "remove": {
                Place p = place(sender, args, 2);
                if (td.remove(p.dim, p.pos.getX(), p.pos.getY(), p.pos.getZ())) {
                    tell(sender, "§a拿掉了 " + p + " 的测试设备");
                } else {
                    DeviceHandle h = net.deviceTable().at(p.dim, p.pos.getX(), p.pos.getY(), p.pos.getZ());
                    if (h != null) {
                        h.remove();
                        tell(sender, "§a拿掉了 " + p + " 的设备");
                    } else {
                        tell(sender, "§c" + p + " 没有设备");
                    }
                }
                break;
            }
            case "list": {
                tell(sender, "§e设备表");
                for (DeviceTable.Device d : new ArrayList<>(net.deviceTable().all())) {
                    tell(sender, "  " + d.label() + "：" + (d.isPowered() ? "通电" : "断电") + "，" + (d.isLoaded() ? "区块已加载" : "区块没加载")
                            + "，网卡 " + d.attachmentDescription());
                }
                break;
            }
            case "bind": {
                if (args.length < 3) {
                    throw new WrongUsageException("/ynet device bind <玩家> <x> <y> <z> [维度]");
                }
                EntityPlayerMP player = getPlayer(server, sender, args[2]);
                Place p = place(sender, args, 3);
                DeviceHandle h = td.bindPlayer(player, p.dim, p.pos.getX(), p.pos.getY(), p.pos.getZ());
                if (h == null) {
                    tell(sender, "§c" + p + " 没有地址（维度没有全局 Wi-Fi，或坐标超出范围）");
                } else {
                    tell(sender, "§a把 " + player.getName() + " 当成 " + p + " 上的电脑，地址 " + h.address() + "（协议栈在他的客户端上）");
                }
                break;
            }
            case "unbind": {
                if (args.length < 3) {
                    throw new WrongUsageException("/ynet device unbind <玩家>");
                }
                EntityPlayerMP player = getPlayer(server, sender, args[2]);
                tell(sender, td.unbindPlayer(player.getUniqueID()) ? "§a已断开 " + player.getName() : "§c" + player.getName() + " 没有接着测试设备");
                break;
            }
            case "power": {
                Place p = place(sender, args, 2);
                if (args.length < 6) {
                    throw new WrongUsageException("/ynet device power <x> <y> <z> on|off [维度]");
                }
                DeviceHandle h = net.deviceTable().at(p.dim, p.pos.getX(), p.pos.getY(), p.pos.getZ());
                if (h == null) {
                    tell(sender, "§c" + p + " 没有设备");
                } else {
                    h.setPowered(args[5].equals("on"));
                    tell(sender, "§a" + p + " 的设备" + (h.isPowered() ? "通电了" : "断电了"));
                }
                break;
            }
            default:
                throw new WrongUsageException("/ynet device add|remove|list|bind|unbind|power ...");
        }
    }

    // ---- ping、DNS、HTTP、NTP ----

    private void ping(ICommandSender sender, String[] args) throws CommandException {
        if (args.length < 5) {
            throw new WrongUsageException("/ynet ping <x> <y> <z> <目标> [次数] [字节数]");
        }
        Place p = place(sender, args, 1, false);
        TestDevices.Emulated dev = emulated(sender, p);
        if (dev == null) {
            return;
        }
        String target = args[4];
        int count = args.length > 5 ? parseInt(args[5], 1, 100) : 4;
        int size = args.length > 6 ? parseInt(args[6], 0, 1232) : 56;
        resolveTarget(sender, dev, target, p.dim).thenAccept(addr -> {
            if (addr == null) {
                return;
            }
            later(sender, "§ePING " + target + (target.equals(addr.toString()) ? "" : "（" + addr + "）") + "，从 " + dev.handle.address()
                    + "，" + size + " 字节数据");
            pingLoop(sender, dev.stack, addr, size, count, 0, new int[1]);
        });
    }

    private void pingLoop(ICommandSender sender, NetStack stack, Ipv6Address addr, int size, int count, int i, int[] ok) {
        stack.ping(null, addr, size, PING_TIMEOUT_MS).whenComplete((r, err) -> {
            if (err != null) {
                later(sender, "§c" + message(err));
                return;
            }
            String line;
            switch (r.status) {
                case REPLY:
                    ok[0]++;
                    line = "§a" + r.bytes + " 字节来自 " + r.from + "：序号 " + (i + 1) + "，时间 " + r.rttMillis + " 毫秒";
                    break;
                case UNREACHABLE:
                    line = "§c来自 " + r.from + "：目标不可达（" + unreachText(r.code) + "）";
                    break;
                case TIME_EXCEEDED:
                    line = "§c来自 " + r.from + "：超时（跳数用完）";
                    break;
                default:
                    line = "§c序号 " + (i + 1) + "：" + PING_TIMEOUT_MS + " 毫秒内没有回应";
                    break;
            }
            later(sender, line);
            if (i + 1 < count) {
                stack.loop().schedule(() -> pingLoop(sender, stack, addr, size, count, i + 1, ok), 1000);
            } else {
                later(sender, "§e发了 " + count + " 个，收到 " + ok[0] + " 个回应");
            }
        });
    }

    static String unreachText(int code) {
        switch (code) {
            case 0:
                return "没有路由";
            case 1:
                return "被禁止";
            case 3:
                return "地址不可达";
            case 4:
                return "端口不可达";
            default:
                return "代码 " + code;
        }
    }

    private void dns(ICommandSender sender, String[] args) throws CommandException {
        if (args.length < 5) {
            throw new WrongUsageException("/ynet dns <x> <y> <z> <域名>");
        }
        Place p = place(sender, args, 1, false);
        TestDevices.Emulated dev = emulated(sender, p);
        if (dev == null) {
            return;
        }
        String name = args[4];
        long start = System.nanoTime();
        dev.dns.query(name, DnsMessage.TYPE_AAAA).whenComplete((m, err) -> {
            if (err != null) {
                later(sender, "§c查 " + name + " 失败：" + message(err));
                return;
            }
            long ms = (System.nanoTime() - start) / 1_000_000;
            later(sender, "§e" + name + " AAAA：响应码 " + rcodeText(m.rcode) + "（问的是 " + dev.dns.server() + "，" + ms + " 毫秒）");
            for (DnsMessage.Record r : m.answers) {
                later(sender, "  " + r.name + " " + r.ttl + " " + typeText(r.type) + " " + (r.address != null ? r.address : r.target));
            }
        });
    }

    static String rcodeText(int rcode) {
        switch (rcode) {
            case 0:
                return "NOERROR";
            case 1:
                return "FORMERR";
            case 2:
                return "SERVFAIL";
            case 3:
                return "NXDOMAIN（不存在）";
            case 4:
                return "NOTIMP";
            case 5:
                return "REFUSED";
            default:
                return String.valueOf(rcode);
        }
    }

    static String typeText(int type) {
        return type == DnsMessage.TYPE_AAAA ? "AAAA" : type == DnsMessage.TYPE_CNAME ? "CNAME" : type == DnsMessage.TYPE_NS ? "NS" : String.valueOf(type);
    }

    private void get(ICommandSender sender, String[] args) throws CommandException {
        if (args.length < 5) {
            throw new WrongUsageException("/ynet get <x> <y> <z> <网址>");
        }
        Place p = place(sender, args, 1, false);
        TestDevices.Emulated dev = emulated(sender, p);
        if (dev == null) {
            return;
        }
        String url = args[4];
        long start = System.nanoTime();
        long[] before = new long[1];
        onLoop(() -> before[0] = dev.stack.tcpRetransmissions());
        HttpClient.fetch(dev.stack, dev.dns, url, HTTP_TIMEOUT_MS).whenComplete((r, err) -> {
            long ms = (System.nanoTime() - start) / 1_000_000;
            long re = dev.stack.tcpRetransmissions() - before[0];
            if (err != null) {
                later(sender, "§c取 " + url + " 失败：" + message(err) + "（" + ms + " 毫秒，TCP 重传 " + re + " 次）");
                return;
            }
            later(sender, "§eHTTP " + r.status + " " + r.reason + "，" + r.body.length + " 字节，" + r.header("Content-Type")
                    + "（" + ms + " 毫秒，TCP 重传 " + re + " 次）");
            String text = r.bodyText();
            for (String line : text.split("\n")) {
                if (!line.trim().isEmpty()) {
                    later(sender, "§7" + line);
                }
            }
        });
    }

    private void ntp(ICommandSender sender, String[] args) throws CommandException {
        if (args.length < 4) {
            throw new WrongUsageException("/ynet ntp <x> <y> <z>");
        }
        Place p = place(sender, args, 1, false);
        TestDevices.Emulated dev = emulated(sender, p);
        if (dev == null) {
            return;
        }
        Ipv6Address server = net.ntpAddress();
        NtpClient.query(dev.stack, server).whenComplete((r, err) -> {
            if (err != null) {
                later(sender, "§c对时失败：" + message(err));
                return;
            }
            later(sender, "§eNTP " + server + "：UTC " + Instant.ofEpochMilli(r.serverTimeMillis) + "，层级 " + r.stratum
                    + "，偏差 " + r.offsetMillis + " 毫秒，往返 " + r.roundTripMillis + " 毫秒");
        });
    }

    // ---- 测试开关与压包 ----

    private void sim(ICommandSender sender, String[] args) throws CommandException {
        if (args.length >= 2 && args[1].equals("off")) {
            onLoop(() -> net.router().setSimulation(0, 0));
            tell(sender, "§a测试开关已关：不丢包、不乱序");
            return;
        }
        if (args.length < 3) {
            throw new WrongUsageException("/ynet sim <丢包%> <乱序%> | off");
        }
        double loss = parseDouble(args[1], 0, 100) / 100;
        double reorder = parseDouble(args[2], 0, 100) / 100;
        onLoop(() -> net.router().setSimulation(loss, reorder));
        tell(sender, "§e测试开关：路由器丢掉 " + args[1] + "% 的包，推迟 " + args[2] + "% 的包 20–80 毫秒（造成乱序）");
    }

    private void stress(ICommandSender sender, String[] args) throws CommandException {
        if (args.length < 3) {
            throw new WrongUsageException("/ynet stress <秒> <每秒包数>");
        }
        int seconds = parseInt(args[1], 1, 600);
        int pps = parseInt(args[2], 1, 200_000);
        NetStack from = net.hostStack(NetworkServer.HOST_ROUTER);
        Ipv6Address to = net.resolverAddress();
        EventLoop loop = net.loop();
        AtomicLong sent = new AtomicLong();
        AtomicLong replies = new AtomicLong();
        long end = loop.nowMillis() + seconds * 1000L;
        int perTick = Math.max(1, pps / 100);
        tell(sender, "§e开始压包：" + seconds + " 秒，每秒约 " + perTick * 100 + " 个回显请求（机房里 " + NetworkServer.HOST_ROUTER + " → resolver）");
        Runnable[] tick = new Runnable[1];
        tick[0] = () -> {
            if (loop.nowMillis() >= end) {
                loop.schedule(() -> later(sender, "§e压包结束：发了 " + sent.get() + " 个，收到 " + replies.get() + " 个回应"), 2500);
                return;
            }
            for (int i = 0; i < perTick; i++) {
                sent.incrementAndGet();
                from.ping(null, to, 56, 2000).thenAccept(r -> {
                    if (r.status == PingResult.Status.REPLY) {
                        replies.incrementAndGet();
                    }
                });
            }
            loop.schedule(tick[0], 10);
        };
        loop.execute(tick[0]);
    }

    // ---- 工具 ----

    /** 坐标与维度 */
    private static final class Place {
        final BlockPos pos;
        final int dim;

        Place(BlockPos pos, int dim) {
            this.pos = pos;
            this.dim = dim;
        }

        @Override
        public String toString() {
            return "维度 " + dim + " (" + pos.getX() + ", " + pos.getY() + ", " + pos.getZ() + ")";
        }
    }

    private Place place(ICommandSender sender, String[] args, int i) throws CommandException {
        return place(sender, args, i, true);
    }

    /** args[i..i+2] 是坐标；{@code dimAfter} 为 true 时 args[i+3] 可以是维度 */
    private Place place(ICommandSender sender, String[] args, int i, boolean dimAfter) throws CommandException {
        if (args.length < i + 3) {
            throw new WrongUsageException(getUsage(sender));
        }
        BlockPos pos = parseBlockPos(sender, args, i, false);
        Entity e = sender.getCommandSenderEntity();
        int dim = e != null ? e.dimension : sender.getEntityWorld().provider.getDimension();
        if (dimAfter && args.length > i + 3 && !args[i + 3].equals("on") && !args[i + 3].equals("off")) {
            dim = parseInt(args[i + 3]);
        } else if (dimAfter && args.length > i + 4) {
            dim = parseInt(args[i + 4]);
        }
        return new Place(pos, dim);
    }

    private TestDevices.Emulated emulated(ICommandSender sender, Place p) {
        TestDevices.Emulated e = net.testDevices().get(p.dim, p.pos.getX(), p.pos.getY(), p.pos.getZ());
        if (e == null) {
            tell(sender, "§c" + p + " 没有服务端测试设备，先用 /ynet device add " + p.pos.getX() + " " + p.pos.getY() + " " + p.pos.getZ() + " " + p.dim);
        }
        return e;
    }

    /** 目标：IPv6 地址、坐标 x,y,z[,维度]、主机登记名、域名（经这台设备的递归解析器查） */
    private CompletableFuture<Ipv6Address> resolveTarget(ICommandSender sender, TestDevices.Emulated dev, String target, int dim) {
        Ipv6Address literal = Ipv6Address.tryParse(target);
        if (literal != null) {
            return CompletableFuture.completedFuture(literal);
        }
        if (target.matches("-?\\d+,-?\\d+,-?\\d+(,-?\\d+)?")) {
            String[] parts = target.split(",");
            int d = parts.length > 3 ? Integer.parseInt(parts[3]) : dim;
            Ipv6Address a = net.deviceTable().addressAt(d, Integer.parseInt(parts[0]), Integer.parseInt(parts[1]), Integer.parseInt(parts[2]));
            if (a == null) {
                tell(sender, "§c坐标 " + target + "（维度 " + d + "）没有地址：维度没有全局 Wi-Fi，或坐标超出范围（x、z 在 −524288 到 524287，y 在 0 到 255）");
            }
            return CompletableFuture.completedFuture(a);
        }
        Ipv6Address host = net.hostAddress(target);
        if (host != null) {
            return CompletableFuture.completedFuture(host);
        }
        CompletableFuture<Ipv6Address> f = new CompletableFuture<>();
        dev.dns.resolveAaaa(target).whenComplete((r, err) -> {
            if (err != null) {
                later(sender, "§c查 " + target + " 失败：" + message(err));
                f.complete(null);
            } else if (r.addresses.isEmpty()) {
                later(sender, "§c" + target + " 没有地址（DNS 响应码 " + rcodeText(r.rcode) + "）");
                f.complete(null);
            } else {
                f.complete(r.addresses.get(0));
            }
        });
        return f;
    }

    static String message(Throwable t) {
        Throwable c = t instanceof CompletionException && t.getCause() != null ? t.getCause() : t;
        if (c instanceof NetworkException) {
            return c.getMessage() + "（" + ((NetworkException) c).reason + "）";
        }
        return String.valueOf(c);
    }

    private void onLoop(Runnable r) {
        EventLoop l = net.loop();
        if (l != null) {
            l.execute(r);
        }
    }

    private static void tell(ICommandSender sender, String text) {
        sender.sendMessage(new TextComponentString(text));
    }

    /** 网络线程上算出的结果：转回主线程再发 */
    void later(ICommandSender sender, String text) {
        MinecraftServer s = net.server();
        if (s != null) {
            s.addScheduledTask(() -> tell(sender, text));
        }
    }

    @Override
    public List<String> getTabCompletions(MinecraftServer server, ICommandSender sender, String[] args, @Nullable BlockPos targetPos) {
        if (args.length == 1) {
            return getListOfStringsMatchingLastWord(args, "table", "stats", "players", "device", "ping", "dns", "get", "ntp", "sim", "stress", "flush");
        }
        if (args.length == 2 && args[0].equals("device")) {
            return getListOfStringsMatchingLastWord(args, "add", "remove", "list", "bind", "unbind", "power");
        }
        if (args.length == 3 && args[0].equals("device") && (args[1].equals("bind") || args[1].equals("unbind"))) {
            return getListOfStringsMatchingLastWord(args, server.getOnlinePlayerNames());
        }
        return Collections.emptyList();
    }
}
