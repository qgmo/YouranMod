package moe.qingu.youran.server.net;

import moe.qingu.youran.api.network.ConfirmText;
import moe.qingu.youran.api.network.ClientRelayNic;
import moe.qingu.youran.api.network.NetworkInterface;
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
        return "/ynet <table|stats|players|device|confirm|location|operator|ping|dns|whois|name|get|post|ca|ntp|sim|stress|flush>";
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
            "§6/ynet device add|remove <x> <y> <z> [维度] §f在一格上插、拔一张测试网卡（背后是服务端的协议栈）",
            "§6/ynet device list §f插着的网卡（地址、种类、收发了多少）",
            "§6/ynet device bind <玩家> <x> <y> <z> [维度] §f在一格上插一张接玩家客户端的测试网卡（他的客户端多一个网口，/ynetc -i 用）",
            "§6/ynet device unbind <玩家>",
            "§6/ynet device refuse <x> <y> <z> on|off [维度] §f测试网卡收不收包（off 时路由器回“目标不可达”）",
            "§6/ynet confirm <玩家> §f给他弹一个测试用的系统确认框，回应后显示结果",
            "§6/ynet location <x> <y> <z> <网站域名> <随机数> §f用这一格的测试网卡签位置（问你自己），显示证明",
            "§6/ynet operator <x> <y> <z> <网站域名> <随机数> §f用这一格的测试网卡签操作者证明（玩家是你自己），显示证明",
            "§6/ynet ping <x> <y> <z> <目标> [次数] [字节数] §f目标：IPv6 地址、坐标 x,y,z[,维度]、主机登记名、域名",
            "§6/ynet dns <x> <y> <z> <域名> [AAAA|TXT|NS|SOA|ANY] §f经递归解析器查（默认 AAAA）",
            "§6/ynet whois <域名或顶级域> §f注册局、所属模组、DNS 服务器",
            "§6/ynet name add <域名.yr> <地址|主机登记名> §f以 youranmod 的身份注册（还没注册时）并在 YouranMod 网站的 DNS 上设 AAAA",
            "§6/ynet name txt <域名.yr> <文字> §f同上，设 TXT",
            "§6/ynet name remove <域名.yr> §f删掉 YouranMod 网站 DNS 上这个名字的记录（注册还在）",
            "§6/ynet get <x> <y> <z> <网址> §f用 TCP 取网页（如 http://test.yr/）",
            "§6/ynet post <x> <y> <z> <网址> <JSON> §f发 POST（正文是 JSON，如向 ca.yr 申请证书）",
            "§6/ynet ca [订单号] §f证书签发服务：订单、最近签出去的证书；带订单号（前几位也行）时看这张订单的认证要求",
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
            case "operator":
                operator(sender, args);
                break;
            case "confirm":
                confirm(server, sender, args);
                break;
            case "location":
                location(sender, args);
                break;
            case "ping":
                ping(sender, args);
                break;
            case "dns":
                dns(sender, args);
                break;
            case "whois":
                whois(sender, args);
                break;
            case "name":
                name(sender, args);
                break;
            case "get":
                get(sender, args);
                break;
            case "post":
                post(sender, args);
                break;
            case "ca":
                ca(sender, args);
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
            tell(sender, "  主机 " + e.getKey() + " → " + addr + (a != null ? "" : " §7（这次没登记，保留）")
                    + (net.rawHosts().containsKey(e.getKey()) ? " §7（原始主机）" : ""));
        }
        tell(sender, "§6DNS §f根 " + moe.qingu.youran.network.DnsState.rootServers() + "；顶级域 ."
                + String.join(" .", moe.qingu.youran.api.network.dns.DnsRegistry.tlds()));
        for (moe.qingu.youran.api.network.dns.DefaultRegistrar r : Arrays.asList(net.yrRegistrar(), net.comRegistrar())) {
            if (r == null) {
                continue;
            }
            for (moe.qingu.youran.api.network.dns.Whois w : r.list()) {
                String shown = moe.qingu.youran.api.network.DomainNames.toDisplay(w.name);
                tell(sender, "  " + w.name + (shown.equals(w.name) ? "" : "（" + shown + "）") + " → " + w.owner + "，DNS " + w.servers);
            }
        }
    }

    private void stats(ICommandSender sender) {
        Router r = net.router();
        tell(sender, "§e游戏内网络统计");
        tell(sender, "转发 " + r.forwarded + "，交给设备 " + r.deliveredToDevices + "，交给机房主机 " + r.deliveredToHosts
                + "，回目标不可达 " + r.unreachableSent + "，回超时 " + r.timeExceededSent + "，坏包 " + r.malformed);
        tell(sender, "插着的网卡 " + net.nicTable().all().size() + " 张，发出收下 " + net.nicTable().accepted + "、交给网卡 " + net.nicTable().delivered
                + "；中转到客户端 " + net.relays().toClient + "、客户端发来 " + net.relays().fromClient + "，丢掉 " + net.rejects().total()
                + "；测试开关：丢包 " + Math.round(r.lossRate() * 100) + "%（已丢 " + r.simulatedDrops + "），乱序 "
                + Math.round(r.reorderRate() * 100) + "%（已推迟 " + r.simulatedDelays + "）");
        for (RawHostPort h : net.rawHosts().values()) {
            tell(sender, "原始主机 " + h.key() + "：" + (h.isOpen() ? "" : "已关，") + "收下 " + h.received + "、没人收（回不可达）" + h.refused
                    + "；发出 " + h.sent + "、不合格或排不进 " + h.rejected);
        }
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
        TestNics tn = net.testNics();
        switch (op) {
            case "add": {
                Place p = place(sender, args, 2);
                TestNics.Emulated e = tn.add(p.dim, p.pos.getX(), p.pos.getY(), p.pos.getZ());
                if (e == null) {
                    tell(sender, "§c" + p + " 插不上：没有地址（维度没有全局 Wi-Fi，或坐标超出范围），或这一格已经插着别的网卡");
                } else {
                    tell(sender, "§a在 " + p + " 插了测试网卡，地址 " + e.address());
                }
                break;
            }
            case "remove": {
                Place p = place(sender, args, 2);
                tell(sender, tn.remove(p.dim, p.pos.getX(), p.pos.getY(), p.pos.getZ()) ? "§a拔掉了 " + p + " 的测试网卡"
                        : "§c" + p + " 没有测试网卡（别的模组插的网卡要由它自己拔）");
                break;
            }
            case "list": {
                tell(sender, "§e插着的网卡");
                for (NicTable.Port port : new ArrayList<>(net.nicTable().all())) {
                    NetworkInterface n = port.nic;
                    String extra = "";
                    if (n instanceof TestNics.Emulated) {
                        TestNics.Emulated e = (TestNics.Emulated) n;
                        extra = "，测试网卡（服务端协议栈）" + (e.refuse ? "§c 收不了§f" : "") + "，网卡收 " + e.received + "、发 " + e.sent + "、发出被拒 " + e.sendRejected;
                    } else if (n instanceof ClientRelayNic) {
                        EntityPlayerMP pl = ((ClientRelayNic) n).connectedPlayer();
                        extra = "，接玩家客户端：" + (pl == null ? "没接" : pl.getName());
                    }
                    tell(sender, "  " + port.label() + "：交给它 " + port.received + "、它发出 " + port.sent + extra);
                }
                ClientRelays r = net.relays();
                for (String line : r.describe()) {
                    tell(sender, "  §7中转：" + line);
                }
                break;
            }
            case "bind": {
                if (args.length < 3) {
                    throw new WrongUsageException("/ynet device bind <玩家> <x> <y> <z> [维度]");
                }
                EntityPlayerMP player = getPlayer(server, sender, args[2]);
                Place p = place(sender, args, 3);
                TestNics.Relay r = tn.bindPlayer(player, p.dim, p.pos.getX(), p.pos.getY(), p.pos.getZ());
                if (r == null) {
                    tell(sender, "§c" + p + " 插不上：没有地址，或这一格已经插着别的网卡");
                } else {
                    tell(sender, "§a在 " + p + " 插了一张接 " + player.getName() + " 客户端的测试网卡，地址 " + r.address()
                            + "，网口 " + net.relays().portOf(player.getUniqueID()) + "（/ynetc status 能看到）");
                }
                break;
            }
            case "unbind": {
                if (args.length < 3) {
                    throw new WrongUsageException("/ynet device unbind <玩家>");
                }
                EntityPlayerMP player = getPlayer(server, sender, args[2]);
                tell(sender, tn.unbindPlayer(player.getUniqueID()) ? "§a拔掉了接 " + player.getName() + " 的测试网卡" : "§c" + player.getName() + " 没有接着测试网卡");
                break;
            }
            case "refuse": {
                if (args.length < 6) {
                    throw new WrongUsageException("/ynet device refuse <x> <y> <z> on|off [维度]");
                }
                Place p = place(sender, args, 2, false);
                TestNics.Emulated e = tn.get(p.dim, p.pos.getX(), p.pos.getY(), p.pos.getZ());
                if (e == null) {
                    tell(sender, "§c" + p + " 没有服务端测试网卡");
                } else {
                    e.refuse = args[5].equals("on");
                    tell(sender, "§a" + p + " 的测试网卡现在" + (e.refuse ? "收不了包（路由器回目标不可达）" : "照常收包"));
                }
                break;
            }
            default:
                throw new WrongUsageException("/ynet device add|remove|list|bind|unbind|refuse ...");
        }
    }

    // ---- 确认框、位置、操作者证明 ----

    private void confirm(MinecraftServer server, ICommandSender sender, String[] args) throws CommandException {
        if (args.length < 2) {
            throw new WrongUsageException("/ynet confirm <玩家>");
        }
        EntityPlayerMP player = getPlayer(server, sender, args[1]);
        moe.qingu.youran.api.network.ConfirmRequest r = new moe.qingu.youran.api.network.ConfirmRequest(
                ConfirmText.translate("youranmod.confirm.test.title", "测试确认框"), "test.yr",
                Arrays.asList(ConfirmText.translate("youranmod.confirm.test.item1", "这是 /ynet confirm 发的测试"),
                        ConfirmText.translate("youranmod.confirm.test.item2", "点同意或拒绝都可以")),
                30, moe.qingu.youran.api.network.ConfirmRequest.Purpose.ACTION);
        net.confirm(player.getUniqueID(), r).thenAccept(res -> tell(sender, "§e测试确认框的结果：" + res));
        tell(sender, "§a已给 " + player.getName() + " 弹框（30 秒）");
    }

    private void location(ICommandSender sender, String[] args) throws CommandException {
        if (args.length < 6) {
            throw new WrongUsageException("/ynet location <x> <y> <z> <网站域名> <随机数>");
        }
        EntityPlayerMP me = getCommandSenderAsPlayer(sender);
        Place p = place(sender, args, 1, false);
        TestNics.Emulated e = emulated(sender, p);
        if (e == null) {
            return;
        }
        moe.qingu.youran.api.network.location.LocationProofs.sign(e, me.getGameProfile(), p.dim, p.pos.getX(), p.pos.getY(), p.pos.getZ(), args[4], args[5])
                .thenAccept(res -> {
                    tell(sender, "§e位置签名：" + res.status.code);
                    if (res.ok()) {
                        for (String line : res.proof.split("\n")) {
                            tell(sender, "  " + line);
                        }
                    }
                });
    }

    private void operator(ICommandSender sender, String[] args) throws CommandException {
        if (args.length < 6) {
            throw new WrongUsageException("/ynet operator <x> <y> <z> <网站域名> <随机数>");
        }
        EntityPlayerMP me = getCommandSenderAsPlayer(sender);
        Place p = place(sender, args, 1, false);
        TestNics.Emulated e = emulated(sender, p);
        if (e == null) {
            return;
        }
        moe.qingu.youran.api.network.identity.OperatorProofs.sign(e, me.getGameProfile(), args[4], args[5]).thenAccept(res -> {
            tell(sender, "§e操作者证明：" + res.status.code);
            if (res.ok()) {
                for (String line : res.proof.split("\n")) {
                    tell(sender, "  " + line);
                }
            }
        });
    }

    // ---- ping、DNS、HTTP、NTP ----

    private void ping(ICommandSender sender, String[] args) throws CommandException {
        if (args.length < 5) {
            throw new WrongUsageException("/ynet ping <x> <y> <z> <目标> [次数] [字节数]");
        }
        Place p = place(sender, args, 1, false);
        TestNics.Emulated dev = emulated(sender, p);
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
            later(sender, "§ePING " + target + (target.equals(addr.toString()) ? "" : "（" + addr + "）") + "，从 " + dev.address()
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
            throw new WrongUsageException("/ynet dns <x> <y> <z> <域名> [AAAA|TXT|NS|SOA|ANY]");
        }
        int type = args.length > 5 ? typeOf(args[5]) : DnsMessage.TYPE_AAAA;
        if (type < 0) {
            throw new WrongUsageException("/ynet dns <x> <y> <z> <域名> [AAAA|TXT|NS|SOA|ANY]");
        }
        Place p = place(sender, args, 1, false);
        TestNics.Emulated dev = emulated(sender, p);
        if (dev == null) {
            return;
        }
        String name = args[4];
        if (!moe.qingu.youran.api.network.DomainNames.isAscii(name)) {
            try {
                tell(sender, "§7" + name + " 的 A-label 是 " + moe.qingu.youran.api.network.DomainNames.toAscii(name));
            } catch (IllegalArgumentException e) {
                tell(sender, "§c" + e.getMessage());
                return;
            }
        }
        long start = System.nanoTime();
        dev.dns.query(name, type).whenComplete((m, err) -> {
            if (err != null) {
                later(sender, "§c查 " + name + " 失败：" + message(err));
                return;
            }
            long ms = (System.nanoTime() - start) / 1_000_000;
            later(sender, "§e" + name + " " + typeText(type) + "：响应码 " + rcodeText(m.rcode) + "（问的是 " + dev.dns.server() + "，" + ms + " 毫秒）");
            for (DnsMessage.Record r : m.answers) {
                later(sender, "  " + r.name + " " + r.ttl + " " + typeText(r.type) + " " + (r.address != null ? r.address
                        : r.target != null ? r.target : r.type == DnsMessage.TYPE_TXT ? "\"" + r.text() + "\"" : r.soa != null ? r.soa.mname + " " + r.soa.serial : ""));
            }
        });
    }

    static int typeOf(String s) {
        switch (s.toUpperCase(java.util.Locale.ROOT)) {
            case "AAAA":
                return DnsMessage.TYPE_AAAA;
            case "TXT":
                return DnsMessage.TYPE_TXT;
            case "NS":
                return DnsMessage.TYPE_NS;
            case "SOA":
                return DnsMessage.TYPE_SOA;
            case "ANY":
                return DnsMessage.TYPE_ANY;
            default:
                return -1;
        }
    }

    private void whois(ICommandSender sender, String[] args) throws CommandException {
        if (args.length < 2) {
            throw new WrongUsageException("/ynet whois <域名或顶级域>");
        }
        String n = args[1].startsWith(".") ? args[1].substring(1) : args[1];
        moe.qingu.youran.api.network.dns.Whois w = moe.qingu.youran.api.network.dns.DnsRegistry.whois(n);
        if (w == null) {
            tell(sender, "§c" + args[1] + " 没有注册");
            return;
        }
        tell(sender, "§e" + w.name + (w.name.contains(".") ? "" : "（顶级域）"));
        tell(sender, "  注册局：" + (w.registrar == null ? "?" : w.registrar.modId()));
        tell(sender, "  所属模组：" + w.owner + (w.registrarIdentity ? "（注册局身份，可以往下分发）" : "（模组身份）"));
        tell(sender, "  DNS 服务器：" + w.servers);
        tell(sender, "  注册时间：" + Instant.ofEpochMilli(w.registeredAt));
    }

    private void name(ICommandSender sender, String[] args) throws CommandException {
        String usage = "/ynet name add <域名.yr> <地址|主机登记名> | txt <域名.yr> <文字> | remove <域名.yr>";
        if (args.length < 3) {
            throw new WrongUsageException(usage);
        }
        moe.qingu.youran.api.network.dns.DnsServer sites = net.sitesDns();
        moe.qingu.youran.api.network.dns.DefaultRegistrar yr = net.yrRegistrar();
        if (sites == null || yr == null) {
            tell(sender, "§cYouranMod 的 DNS 没起来");
            return;
        }
        String n;
        try {
            // 记录名可以带下划线（如 _acme-challenge）：ASCII 的照写（由 DNS 服务检查），只有非 ASCII 的按国际化域名转
            n = moe.qingu.youran.api.network.DomainNames.isAscii(args[2]) ? args[2].trim().toLowerCase(java.util.Locale.ROOT)
                    : moe.qingu.youran.api.network.DomainNames.toAscii(args[2]);
        } catch (IllegalArgumentException e) {
            tell(sender, "§c" + e.getMessage());
            return;
        }
        if (!n.endsWith(".yr")) {
            tell(sender, "§c只能是 .yr 下的名字");
            return;
        }
        try {
            switch (args[1]) {
                case "remove":
                    sites.remove(n);
                    tell(sender, "§a已删掉 " + n + " 的记录（按缓存时间传开：记录 " + moe.qingu.youran.api.network.dns.DnsServer.TTL_RECORD + " 秒）");
                    return;
                case "add":
                case "txt": {
                    if (args.length < 4) {
                        throw new WrongUsageException(usage);
                    }
                    Ipv6Address a = null;
                    if (args[1].equals("add")) {
                        a = Ipv6Address.tryParse(args[3]);
                        if (a == null) {
                            a = net.hostAddress(args[3]);
                        }
                        if (a == null) {
                            tell(sender, "§c" + args[3] + " 不是地址，也不是登记过的主机");
                            return;
                        }
                    }
                    String zone = NetworkServer.secondLevel(n);
                    if (!sites.zones().contains(zone)) {
                        moe.qingu.youran.api.network.dns.Registration r = yr.register(zone, NetworkServer.MOD_ID,
                                Collections.singletonList(sites.address()));
                        if (r.status() != moe.qingu.youran.api.network.dns.Registration.Status.SUCCESS) {
                            tell(sender, "§c注册 " + zone + " " + r);
                            return;
                        }
                        sites.addZone(zone);
                        tell(sender, "§a以 " + NetworkServer.MOD_ID + " 的身份注册了 " + zone + "，委派给 " + sites.address());
                    }
                    if (a != null) {
                        sites.setAddresses(n, Collections.singletonList(a));
                        tell(sender, "§a" + n + " AAAA → " + a);
                    } else {
                        String text = String.join(" ", Arrays.copyOfRange(args, 3, args.length));
                        sites.setTexts(n, Collections.singletonList(text));
                        tell(sender, "§a" + n + " TXT \"" + text + "\"");
                    }
                    tell(sender, "§7按缓存时间传开（新名字最多等“查无此名”的 " + moe.qingu.youran.api.network.dns.DnsServer.TTL_NEGATIVE
                            + " 秒，改记录最多 " + moe.qingu.youran.api.network.dns.DnsServer.TTL_RECORD + " 秒）");
                    return;
                }
                default:
                    throw new WrongUsageException(usage);
            }
        } catch (IllegalArgumentException e) {
            tell(sender, "§c" + e.getMessage());
        }
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
        return type == DnsMessage.TYPE_AAAA ? "AAAA" : type == DnsMessage.TYPE_CNAME ? "CNAME" : type == DnsMessage.TYPE_NS ? "NS"
                : type == DnsMessage.TYPE_TXT ? "TXT" : type == DnsMessage.TYPE_SOA ? "SOA" : type == DnsMessage.TYPE_ANY ? "ANY" : String.valueOf(type);
    }

    private void get(ICommandSender sender, String[] args) throws CommandException {
        if (args.length < 5) {
            throw new WrongUsageException("/ynet get <x> <y> <z> <网址>");
        }
        Place p = place(sender, args, 1, false);
        TestNics.Emulated dev = emulated(sender, p);
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

    private void post(ICommandSender sender, String[] args) throws CommandException {
        if (args.length < 6) {
            throw new WrongUsageException("/ynet post <x> <y> <z> <网址> <JSON>");
        }
        Place p = place(sender, args, 1, false);
        TestNics.Emulated dev = emulated(sender, p);
        if (dev == null) {
            return;
        }
        String url = args[4];
        HttpClient.Url u = HttpClient.Url.parse(url);
        if (u == null) {
            tell(sender, "§c不认识的网址：" + url);
            return;
        }
        String body = String.join(" ", Arrays.asList(args).subList(5, args.length));
        Map<String, String> headers = Collections.singletonMap("Content-Type", "application/json");
        HttpClient.send(dev.stack, dev.dns, u, "POST", headers, body.getBytes(java.nio.charset.StandardCharsets.UTF_8), HTTP_TIMEOUT_MS, null)
                .whenComplete((r, err) -> {
                    if (err != null) {
                        later(sender, "§c POST " + url + " 失败：" + message(err));
                        return;
                    }
                    later(sender, "§eHTTP " + r.status + " " + r.reason + "，" + r.body.length + " 字节，" + r.header("Content-Type"));
                    for (String line : r.bodyText().split("\n")) {
                        if (!line.trim().isEmpty()) {
                            later(sender, "§7" + line);
                        }
                    }
                });
    }

    private void ca(ICommandSender sender, String[] args) {
        moe.qingu.youran.server.net.ca.CaService ca = net.caService();
        if (ca == null) {
            tell(sender, "§c证书签发服务没在运行");
            return;
        }
        String reason = ca.unavailableReason();
        if (args.length >= 2) {
            String prefix = args[1];
            ca.orders().whenComplete((list, err) -> {
                if (err != null) {
                    later(sender, "§c" + message(err));
                    return;
                }
                int n = 0;
                for (moe.qingu.youran.server.net.ca.CaService.OrderInfo i : list) {
                    if (!i.order.id.startsWith(prefix)) {
                        continue;
                    }
                    n++;
                    moe.qingu.youran.api.network.ca.CertificateOrder o = i.order;
                    later(sender, "§e订单 " + o.id + "：" + o.status.id + "，来源 " + i.requester + "，申请于 " + Instant.ofEpochMilli(i.created)
                            + "，过期 " + Instant.ofEpochMilli(o.expires) + (o.method == null ? "" : "，认证 " + o.method.id));
                    if (o.error != null) {
                        later(sender, "§c  " + o.error);
                    }
                    for (moe.qingu.youran.api.network.ca.Challenge c : o.challenges) {
                        later(sender, "§6  " + c.name + " §fHTTP " + c.httpUrl + " 内容 " + c.httpContent);
                        later(sender, "§f    DNS " + c.dnsName + " TXT " + c.dnsValue);
                    }
                    if (!o.certificate.isEmpty()) {
                        java.security.cert.X509Certificate leaf = o.certificate.get(0);
                        later(sender, "§a  证书序列号 " + leaf.getSerialNumber().toString(16) + "，有效到 " + leaf.getNotAfter().toInstant());
                    }
                }
                if (n == 0) {
                    later(sender, "§c没有订单号以 " + prefix + " 开头的订单（订单 1 小时后过期）");
                }
            });
            return;
        }
        if (reason != null) {
            tell(sender, "§c证书签发服务没开：" + reason);
        } else {
            tell(sender, "§e证书签发服务 ca.yr：中间 CA " + ca.intermediate().certificate().getSubjectX500Principal().getName()
                    + "（有效到 " + ca.intermediate().certificate().getNotAfter().toInstant() + "），能签 " + ca.constraintsText()
                    + "；开服以来签了 " + ca.issuedTotal() + " 张");
        }
        ca.orders().thenCombine(ca.issued(), (orders, issued) -> {
            later(sender, "§6订单（内存里 " + orders.size() + " 张，1 小时后过期）");
            int from = Math.max(0, orders.size() - 15);
            for (moe.qingu.youran.server.net.ca.CaService.OrderInfo i : orders.subList(from, orders.size())) {
                moe.qingu.youran.api.network.ca.CertificateOrder o = i.order;
                later(sender, "  " + moe.qingu.youran.server.net.ca.CaService.shortId(o.id) + " " + o.status.id + " " + o.names + " 来源 " + i.requester
                        + (o.method == null ? "" : " " + o.method.id) + (o.error == null ? "" : " §c" + o.error));
            }
            later(sender, "§6最近签出去的证书（" + issued.size() + " 张）");
            for (moe.qingu.youran.server.net.ca.CaService.Issued c : issued.subList(0, Math.min(15, issued.size()))) {
                later(sender, "  " + c.names + " 序列号 " + c.serial + " 到 " + Instant.ofEpochMilli(c.notAfter) + " 来源 " + c.requester
                        + " 认证 " + c.method + " §7（" + Instant.ofEpochMilli(c.at) + "）");
            }
            return null;
        }).exceptionally(err -> {
            later(sender, "§c" + message(err));
            return null;
        });
    }

    private void ntp(ICommandSender sender, String[] args) throws CommandException {
        if (args.length < 4) {
            throw new WrongUsageException("/ynet ntp <x> <y> <z>");
        }
        Place p = place(sender, args, 1, false);
        TestNics.Emulated dev = emulated(sender, p);
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

    private TestNics.Emulated emulated(ICommandSender sender, Place p) {
        TestNics.Emulated e = net.testNics().get(p.dim, p.pos.getX(), p.pos.getY(), p.pos.getZ());
        if (e == null) {
            tell(sender, "§c" + p + " 没有服务端测试网卡，先用 /ynet device add " + p.pos.getX() + " " + p.pos.getY() + " " + p.pos.getZ() + " " + p.dim);
        }
        return e;
    }

    /** 目标：IPv6 地址、坐标 x,y,z[,维度]、主机登记名、域名（经这台设备的递归解析器查） */
    private CompletableFuture<Ipv6Address> resolveTarget(ICommandSender sender, TestNics.Emulated dev, String target, int dim) {
        Ipv6Address literal = Ipv6Address.tryParse(target);
        if (literal != null) {
            return CompletableFuture.completedFuture(literal);
        }
        if (target.matches("-?\\d+,-?\\d+,-?\\d+(,-?\\d+)?")) {
            String[] parts = target.split(",");
            int d = parts.length > 3 ? Integer.parseInt(parts[3]) : dim;
            Ipv6Address a = net.addressAt(d, Integer.parseInt(parts[0]), Integer.parseInt(parts[1]), Integer.parseInt(parts[2]));
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
            return getListOfStringsMatchingLastWord(args, "table", "stats", "players", "device", "confirm", "location", "operator", "ping", "dns", "whois", "name",
                    "get", "post", "ca", "ntp", "sim", "stress", "flush");
        }
        if (args.length == 2 && args[0].equals("name")) {
            return getListOfStringsMatchingLastWord(args, "add", "txt", "remove");
        }
        if (args.length == 2 && args[0].equals("device")) {
            return getListOfStringsMatchingLastWord(args, "add", "remove", "list", "bind", "unbind", "refuse");
        }
        if (args.length == 3 && args[0].equals("device") && (args[1].equals("bind") || args[1].equals("unbind"))) {
            return getListOfStringsMatchingLastWord(args, server.getOnlinePlayerNames());
        }
        return Collections.emptyList();
    }
}
