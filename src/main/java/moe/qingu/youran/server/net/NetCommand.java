package moe.qingu.youran.server.net;

import moe.qingu.youran.api.network.ConfirmText;
import moe.qingu.youran.api.network.ClientRelayNic;
import moe.qingu.youran.api.network.NetworkInterface;
import moe.qingu.youran.api.network.Ipv6Address;
import moe.qingu.youran.api.network.NetworkLayout;
import moe.qingu.youran.api.network.packet.DnsMessage;
import moe.qingu.youran.api.network.stack.DnsResolver;
import moe.qingu.youran.api.network.stack.EventLoop;
import moe.qingu.youran.api.network.stack.NetStack;
import moe.qingu.youran.api.network.stack.NetworkException;
import moe.qingu.youran.net.ProbeMessages;
import moe.qingu.youran.net.YouranNetwork;
import moe.qingu.youran.network.NetProbe;
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
 * 测试命令 /ynet（权限 2，管理员）。ping、trace、dns、get、post、ntp 从执行者自己的玩家地址发出（服主 11-27）：服务端查过权限后交给他的
 * 客户端跑（{@link ProbeMessages}），结果一行一行回来、显示给他；控制台、RCON 没有玩家地址，从机房里的运维主机（{@code youranmod:ops}）发出。
 * 另有：列地址分配表、路由器；在一格上插、拔测试网卡；丢包与乱序的测试开关；压包。
 * 网络线程上算出的结果转回主线程再发给执行者。坐标可以写 ~（相对执行者）；维度不写时用执行者所在的维度（控制台为 0）。
 */
final class NetCommand extends CommandBase {
    static final String NAME = "ynet";
    static final int PERMISSION_LEVEL = 2;
    /** 交给客户端跑的子命令最多等多久（过了不再收它的结果） */
    static final long PROBE_TIMEOUT_MS = 180_000;
    /** 交给客户端跑的子命令最多收多少行 */
    static final int PROBE_MAX_LINES = 200;
    private final NetworkServer net;

    NetCommand(NetworkServer net) {
        this.net = net;
    }

    @Override
    public String getName() {
        return NAME;
    }

    @Override
    public String getUsage(ICommandSender sender) {
        return "/ynet <table|routers|stats|players|device|confirm|location|operator|ping|trace|dns|whois|name|get|post|ca|ntp|sim|stress|flush>";
    }

    @Override
    public int getRequiredPermissionLevel() {
        return PERMISSION_LEVEL;
    }

    private static final List<String> HELP = Arrays.asList(
            "§e/ynet —— 游戏内网络的测试命令",
            "§6/ynet table §f地址分配表",
            "§6/ynet routers §f路由器：地址、路由表、邻居",
            "§6/ynet stats §f路由器与网卡的统计",
            "§6/ynet players §f在线玩家的地址（DHCPv6 租约）、连接数、放行的用途",
            "§6/ynet device add|remove <x> <y> <z> [维度] §f在一格上插、拔一张测试网卡（背后是服务端的协议栈）",
            "§6/ynet device list §f插着的网卡（地址、种类、收发了多少）",
            "§6/ynet device bind <玩家> <x> <y> <z> [维度] §f在一格上插一张接玩家客户端的测试网卡（他的客户端多一个网口，/ynetc -i 用）",
            "§6/ynet device unbind <玩家>",
            "§6/ynet device refuse <x> <y> <z> on|off [维度] §f测试网卡收不收包（off 时路由器回“目标不可达”）",
            "§6/ynet confirm <玩家> §f给他弹一个测试用的系统确认框，回应后显示结果",
            "§6/ynet location <x> <y> <z> <网站域名> <随机数> §f用这一格的测试网卡签位置（问你自己），显示证明",
            "§6/ynet operator <x> <y> <z> <网站域名> <随机数> §f用这一格的测试网卡签操作者证明（玩家是你自己），显示证明",
            "§7下面六个从你自己的玩家地址发出（你的客户端跑；控制台从运维主机发出）",
            "§6/ynet ping <目标> [次数] [字节数] §f目标：IPv6 地址、坐标 x,y,z[,维度]、主机登记名、域名",
            "§6/ynet trace <目标> §f逐跳探测（每一跳的路由器回“超时”）",
            "§6/ynet dns <域名> [AAAA|TXT|NS|SOA|ANY] §f经递归解析器查（默认 AAAA；截断时改用 TCP）",
            "§6/ynet whois <域名或顶级域> §f注册局、所属模组、DNS 服务器",
            "§6/ynet name add <域名.yr> <地址|主机登记名> §f以 youranmod 的身份注册（还没注册时）并在 YouranMod 网站的 DNS 上设 AAAA",
            "§6/ynet name txt <域名.yr> <文字> §f同上，设 TXT",
            "§6/ynet name remove <域名.yr> §f删掉 YouranMod 网站 DNS 上这个名字的记录（注册还在）",
            "§6/ynet get <网址> §f用 TCP 取网页（如 http://test.yr/）",
            "§6/ynet post <网址> <JSON> §f发 POST（正文是 JSON，如向 ca.yr 申请证书）",
            "§6/ynet ca [订单号] §f证书签发服务：订单、最近签出去的证书；带订单号（前几位也行）时看这张订单的认证要求",
            "§6/ynet ntp §f向 NTP 服务器对时",
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
            case "routers":
                routers(sender);
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
            case "trace":
            case "dns":
            case "get":
            case "post":
            case "ntp":
                probe(sender, sub, args);
                break;
            case "whois":
                whois(sender, args);
                break;
            case "name":
                name(sender, args);
                break;
            case "ca":
                ca(sender, args);
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
        RouterNetwork r = net.routers();
        if (r == null) {
            tell(sender, "§c路由器还没建好");
            return;
        }
        tell(sender, "§e游戏内网络统计");
        tell(sender, "路由器 " + r.routers().size() + " 台：收 " + r.sum(x -> x.received) + "，转发 " + r.sum(x -> x.forwarded)
                + "，回目标不可达 " + r.sum(x -> x.unreachableSent) + "，回超时 " + r.sum(x -> x.timeExceededSent) + "，坏包 " + r.sum(x -> x.malformed));
        tell(sender, "插着的网卡 " + net.nicTable().all().size() + " 张，发出收下 " + net.nicTable().accepted + "、交给网卡 " + net.nicTable().delivered
                + "；中转到客户端 " + net.relays().toClient + "、客户端发来 " + net.relays().fromClient + "，丢掉 " + net.rejects().total()
                + "；测试开关：丢包 " + Math.round(r.simulation.loss() * 100) + "%（已丢 " + r.simulation.drops + "），乱序 "
                + Math.round(r.simulation.reorder() * 100) + "%（已推迟 " + r.simulation.delays + "）");
        for (RawHostPort h : net.rawHosts().values()) {
            tell(sender, "原始主机 " + h.key() + "：" + (h.isOpen() ? "" : "已关，") + "收下 " + h.received + "、没人收（回不可达）" + h.refused
                    + "；发出 " + h.sent + "、不合格或排不进 " + h.rejected);
        }
        RecursiveResolverStats.tell(this, sender, net);
    }

    /** 路由器：地址、路由表、各网口的邻居（网络线程上读） */
    private void routers(ICommandSender sender) {
        RouterNetwork rn = net.routers();
        if (rn == null) {
            tell(sender, "§c路由器还没建好");
            return;
        }
        onLoop(() -> {
            for (Router r : rn.routers()) {
                later(sender, "§e" + r.name + " §f" + r.address + "：收 " + r.received + "，转发 " + r.forwarded + "，回不可达 " + r.unreachableSent
                        + "，回超时 " + r.timeExceededSent);
                for (Router.Route route : r.routes()) {
                    later(sender, "  §7路由 " + route);
                }
                for (Router.Port port : r.ports) {
                    if (port instanceof Router.LinkPort && !(port instanceof PlayerNics.PlayerLink)) {
                        Router.LinkPort lp = (Router.LinkPort) port;
                        later(sender, "  §7网口 " + lp.name + " " + lp.linkLocal + "，邻居 " + lp.nd.describeNeighbors());
                    }
                }
            }
        });
    }

    private void players(ICommandSender sender) {
        PlayerNics p = net.players();
        if (p == null) {
            tell(sender, "§c玩家地址没开");
            return;
        }
        tell(sender, "§e玩家地址 §f" + net.book().layout.playersPrefix() + "/96（DHCPv6），在线 " + p.online()
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

    // ---- ping、trace、DNS、HTTP、NTP：从执行者的玩家地址发出 ----

    /**
     * 交给执行者的客户端跑（从他的玩家网口发出），或者在运维主机上跑（控制台、RCON）。目标是坐标、主机登记名的先在这里换成地址
     * （服务端知道分配表；客户端只拿到地址或域名）
     */
    private void probe(ICommandSender sender, String op, String[] args) throws CommandException {
        String[] rest = Arrays.copyOfRange(args, 1, args.length);
        if ((op.equals("ping") || op.equals("trace")) && rest.length >= 1) {
            Ipv6Address a = staticTarget(sender, rest[0]);
            if (a == null && rest[0].matches("-?\\d+,-?\\d+,-?\\d+(,-?\\d+)?")) {
                return;
            }
            if (a != null) {
                rest[0] = a.toString();
            }
        }
        if ((op.equals("dns") && rest.length > 1 && NetProbe.typeOf(rest[1]) < 0) || (rest.length == 0 && !op.equals("ntp"))) {
            throw new WrongUsageException("/ynet " + op + " ……（见 /ynet）");
        }
        Entity e = sender.getCommandSenderEntity();
        if (e instanceof EntityPlayerMP) {
            EntityPlayerMP player = (EntityPlayerMP) e;
            PlayerNics p = net.players();
            if (p == null || !p.isOnline(player.getUniqueID())) {
                tell(sender, "§c" + player.getName() + " 没有玩家地址（玩家地址没开，或还没接上）");
                return;
            }
            long id = net.newProbe(player.getUniqueID(), sender);
            YouranNetwork.CHANNEL.sendTo(new ProbeMessages.Run(id, op, rest), player);
            return;
        }
        NetStack ops = net.hostStack(NetworkServer.HOST_OPS);
        if (ops == null) {
            tell(sender, "§c运维主机（" + NetworkServer.HOST_OPS + "）没起来");
            return;
        }
        tell(sender, "§7从运维主机 " + net.hostAddress(NetworkServer.HOST_OPS) + " 发出");
        String[] fixed = rest;
        onLoop(() -> new NetProbe(ops, null, new DnsResolver(ops, net.resolverAddress()), net.ntpAddress()).run(op, fixed, new NetProbe.Out() {
            @Override
            public void line(String text) {
                later(sender, text);
            }

            @Override
            public void done() {
            }
        }));
    }

    /** 坐标 x,y,z[,维度]、主机登记名换成地址；都不是为 null（地址、域名照原样交下去） */
    private Ipv6Address staticTarget(ICommandSender sender, String target) {
        if (Ipv6Address.tryParse(target) != null) {
            return null;
        }
        if (target.matches("-?\\d+,-?\\d+,-?\\d+(,-?\\d+)?")) {
            String[] parts = target.split(",");
            Entity e = sender.getCommandSenderEntity();
            int dim = e != null ? e.dimension : sender.getEntityWorld().provider.getDimension();
            int d = parts.length > 3 ? Integer.parseInt(parts[3]) : dim;
            Ipv6Address a = net.addressAt(d, Integer.parseInt(parts[0]), Integer.parseInt(parts[1]), Integer.parseInt(parts[2]));
            if (a == null) {
                tell(sender, "§c坐标 " + target + "（维度 " + d + "）没有地址：维度没有全局 Wi-Fi，或坐标超出范围（x、z 在 −524288 到 524287，y 在 0 到 255）");
            }
            return a;
        }
        return net.hostAddress(target);
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

    // ---- 测试开关与压包 ----

    private void sim(ICommandSender sender, String[] args) throws CommandException {
        RouterNetwork rn = net.routers();
        if (rn == null) {
            tell(sender, "§c路由器还没建好");
            return;
        }
        if (args.length >= 2 && args[1].equals("off")) {
            rn.simulation.set(0, 0);
            tell(sender, "§a测试开关已关：不丢包、不乱序");
            return;
        }
        if (args.length < 3) {
            throw new WrongUsageException("/ynet sim <丢包%> <乱序%> | off");
        }
        double loss = parseDouble(args[1], 0, 100) / 100;
        double reorder = parseDouble(args[2], 0, 100) / 100;
        rn.simulation.set(loss, reorder);
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
        if (from == null) {
            tell(sender, "§c机房路由器还没起来");
            return;
        }
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
            return getListOfStringsMatchingLastWord(args, "table", "routers", "stats", "players", "device", "confirm", "location", "operator", "ping", "trace",
                    "dns", "whois", "name", "get", "post", "ca", "ntp", "sim", "stress", "flush");
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
