package moe.qingu.youran.network;

import moe.qingu.youran.api.network.DomainNames;
import moe.qingu.youran.api.network.Ipv6Address;
import moe.qingu.youran.api.network.packet.DnsMessage;
import moe.qingu.youran.api.network.stack.DnsResolver;
import moe.qingu.youran.api.network.stack.HttpClient;
import moe.qingu.youran.api.network.stack.NetStack;
import moe.qingu.youran.api.network.stack.NetworkException;
import moe.qingu.youran.api.network.stack.NtpClient;
import moe.qingu.youran.api.network.stack.PingResult;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.Arrays;
import java.util.Collections;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.CompletionException;
import java.util.function.Consumer;

/**
 * {@code /ynet} 的 ping、dns、get、post、ntp、trace 在一个协议栈上怎么跑（服主 11-27：从执行者自己的玩家地址发出）。两端共用：
 * 玩家执行时在他的客户端上、从他的玩家网口跑（服务器把命令交给客户端，结果一行一行回给服务器，显示在他的聊天栏）；
 * 控制台、RCON 执行时在服务端机房里的运维主机上跑。结果一行一行交给 {@link Out}，全部完了调 {@link Out#done}。
 * <p>
 * 都在协议栈的事件循环线程上回调。
 */
public final class NetProbe {
    public static final long PING_TIMEOUT_MS = 2000;
    public static final long HTTP_TIMEOUT_MS = 15_000;
    public static final int TRACE_MAX_HOPS = 16;

    /** 结果去哪 */
    public interface Out {
        void line(String text);

        void done();
    }

    private final NetStack stack;
    /** 从哪个网口发；null 为按源地址选择规则 */
    private final NetStack.Interface via;
    private final DnsResolver dns;
    private final Ipv6Address ntp;

    public NetProbe(NetStack stack, NetStack.Interface via, DnsResolver dns, Ipv6Address ntp) {
        this.stack = stack;
        this.via = via;
        this.dns = dns;
        this.ntp = ntp;
    }

    /** 这几个子命令在这里跑 */
    public static boolean handles(String op) {
        return Arrays.asList("ping", "dns", "get", "post", "ntp", "trace").contains(op);
    }

    /** 跑一个子命令（{@code args} 不含子命令名；坐标、主机登记名已经由服务端换成了地址） */
    public void run(String op, String[] args, Out out) {
        Ipv6Address src = via != null ? via.address() : stack.primaryAddress();
        if (src == null) {
            out.line("§c没有可用的源地址（玩家网口还没用 DHCPv6 租到地址？）");
            out.done();
            return;
        }
        try {
            switch (op) {
                case "ping":
                    ping(args, src, out);
                    return;
                case "trace":
                    trace(args, src, out);
                    return;
                case "dns":
                    dns(args, out);
                    return;
                case "get":
                    get(args, out);
                    return;
                case "post":
                    post(args, out);
                    return;
                case "ntp":
                    ntp(out);
                    return;
                default:
                    out.line("§c不认识的子命令：" + op);
                    out.done();
            }
        } catch (RuntimeException e) {
            out.line("§c" + e.getMessage());
            out.done();
        }
    }

    private void resolve(String target, Out out, Consumer<Ipv6Address> then) {
        Ipv6Address literal = Ipv6Address.tryParse(target);
        if (literal != null) {
            then.accept(literal);
            return;
        }
        dns.resolveAaaa(target).whenComplete((r, err) -> {
            if (err != null) {
                out.line("§c查 " + target + " 失败：" + message(err));
                out.done();
            } else if (r.addresses.isEmpty()) {
                out.line("§c" + target + " 没有地址（DNS 响应码 " + rcodeText(r.rcode) + "）");
                out.done();
            } else {
                then.accept(r.addresses.get(0));
            }
        });
    }

    private static int intArg(String[] args, int i, int def, int min, int max) {
        if (args.length <= i) {
            return def;
        }
        try {
            return Math.max(min, Math.min(max, Integer.parseInt(args[i])));
        } catch (NumberFormatException e) {
            throw new IllegalArgumentException("不是数字：" + args[i]);
        }
    }

    // ---- ping ----

    private void ping(String[] args, Ipv6Address src, Out out) {
        if (args.length < 1) {
            out.line("§c/ynet ping <目标> [次数] [字节数]");
            out.done();
            return;
        }
        String target = args[0];
        int count = intArg(args, 1, 4, 1, 100);
        int size = intArg(args, 2, 56, 0, 1232);
        resolve(target, out, addr -> {
            out.line("§ePING " + target + (target.equals(addr.toString()) ? "" : "（" + addr + "）") + "，从 " + src + "，" + size + " 字节数据");
            pingLoop(addr, size, count, 0, new int[1], out);
        });
    }

    private void pingLoop(Ipv6Address addr, int size, int count, int i, int[] ok, Out out) {
        stack.ping(via, addr, size, PING_TIMEOUT_MS, 64).whenComplete((r, err) -> {
            if (err != null) {
                out.line("§c" + message(err));
                out.done();
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
            out.line(line);
            if (i + 1 < count) {
                stack.loop().schedule(() -> pingLoop(addr, size, count, i + 1, ok, out), 1000);
            } else {
                out.line("§e发了 " + count + " 个，收到 " + ok[0] + " 个回应");
                out.done();
            }
        });
    }

    // ---- trace ----

    private void trace(String[] args, Ipv6Address src, Out out) {
        if (args.length < 1) {
            out.line("§c/ynet trace <目标>");
            out.done();
            return;
        }
        String target = args[0];
        resolve(target, out, addr -> {
            out.line("§eTRACE " + target + (target.equals(addr.toString()) ? "" : "（" + addr + "）") + "，从 " + src + "，最多 " + TRACE_MAX_HOPS + " 跳");
            traceHop(addr, 1, out);
        });
    }

    private void traceHop(Ipv6Address addr, int hop, Out out) {
        stack.ping(via, addr, 8, PING_TIMEOUT_MS, hop).whenComplete((r, err) -> {
            if (err != null) {
                out.line("§c" + message(err));
                out.done();
                return;
            }
            switch (r.status) {
                case TIME_EXCEEDED:
                    out.line("  " + hop + "  " + r.from + "  " + r.rttMillis + " 毫秒");
                    break;
                case REPLY:
                    out.line("§a  " + hop + "  " + r.from + "  " + r.rttMillis + " 毫秒（到了）");
                    out.done();
                    return;
                case UNREACHABLE:
                    out.line("§c  " + hop + "  " + r.from + "  目标不可达（" + unreachText(r.code) + "）");
                    out.done();
                    return;
                default:
                    out.line("  " + hop + "  *");
                    break;
            }
            if (hop >= TRACE_MAX_HOPS) {
                out.line("§c" + TRACE_MAX_HOPS + " 跳内没到");
                out.done();
                return;
            }
            traceHop(addr, hop + 1, out);
        });
    }

    // ---- DNS ----

    public static int typeOf(String s) {
        switch (s.toUpperCase(Locale.ROOT)) {
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

    private void dns(String[] args, Out out) {
        int type = args.length > 1 ? typeOf(args[1]) : DnsMessage.TYPE_AAAA;
        if (args.length < 1 || type < 0) {
            out.line("§c/ynet dns <域名> [AAAA|TXT|NS|SOA|ANY]");
            out.done();
            return;
        }
        String name = args[0];
        if (!DomainNames.isAscii(name)) {
            out.line("§7" + name + " 的 A-label 是 " + DomainNames.toAscii(name));
        }
        long start = stack.loop().nowMillis();
        dns.query(name, type).whenComplete((m, err) -> {
            if (err != null) {
                out.line("§c查 " + name + " 失败：" + message(err));
                out.done();
                return;
            }
            long ms = stack.loop().nowMillis() - start;
            out.line("§e" + name + " " + typeText(type) + "：响应码 " + rcodeText(m.rcode) + "（问的是 " + dns.server() + "，" + ms + " 毫秒"
                    + (m.edns != null ? "，EDNS 载荷 " + m.edns.payloadSize : "") + "，" + (dns.lastOverTcp() ? "截断后改用 TCP" : "UDP") + "）");
            for (DnsMessage.Record r : m.answers) {
                out.line("  " + r.name + " " + r.ttl + " " + typeText(r.type) + " " + (r.address != null ? r.address
                        : r.target != null ? r.target : r.type == DnsMessage.TYPE_TXT ? "\"" + shorten(r.text()) + "\""
                        : r.soa != null ? r.soa.mname + " " + r.soa.serial : ""));
            }
            out.done();
        });
    }

    private static String shorten(String s) {
        return s == null || s.length() <= 120 ? s : s.substring(0, 120) + "…（共 " + s.length() + " 个字）";
    }

    // ---- HTTP ----

    private void get(String[] args, Out out) {
        if (args.length < 1) {
            out.line("§c/ynet get <网址>");
            out.done();
            return;
        }
        String url = args[0];
        long start = stack.loop().nowMillis();
        long before = stack.tcpRetransmissions();
        HttpClient.fetch(stack, via, dns, url, HTTP_TIMEOUT_MS).whenComplete((r, err) -> {
            long ms = stack.loop().nowMillis() - start;
            long re = stack.tcpRetransmissions() - before;
            if (err != null) {
                out.line("§c取 " + url + " 失败：" + message(err) + "（" + ms + " 毫秒，TCP 重传 " + re + " 次）");
                out.done();
                return;
            }
            out.line("§eHTTP " + r.status + " " + r.reason + "，" + r.body.length + " 字节，" + r.header("Content-Type")
                    + "（" + ms + " 毫秒，TCP 重传 " + re + " 次）");
            body(r, out);
        });
    }

    private void post(String[] args, Out out) {
        if (args.length < 2) {
            out.line("§c/ynet post <网址> <JSON>");
            out.done();
            return;
        }
        String url = args[0];
        HttpClient.Url u = HttpClient.Url.parse(url);
        if (u == null) {
            out.line("§c不认识的网址：" + url);
            out.done();
            return;
        }
        String body = String.join(" ", Arrays.asList(args).subList(1, args.length));
        Map<String, String> headers = Collections.singletonMap("Content-Type", "application/json");
        HttpClient.send(stack, via, dns, u, "POST", headers, body.getBytes(StandardCharsets.UTF_8), HTTP_TIMEOUT_MS, null).whenComplete((r, err) -> {
            if (err != null) {
                out.line("§cPOST " + url + " 失败：" + message(err));
                out.done();
                return;
            }
            out.line("§eHTTP " + r.status + " " + r.reason + "，" + r.body.length + " 字节，" + r.header("Content-Type"));
            body(r, out);
        });
    }

    private static void body(HttpClient.Response r, Out out) {
        int n = 0;
        for (String line : r.bodyText().split("\n")) {
            if (!line.trim().isEmpty()) {
                if (++n > 40) {
                    out.line("§7……（后面的不显示）");
                    break;
                }
                out.line("§7" + line);
            }
        }
        out.done();
    }

    // ---- NTP ----

    private void ntp(Out out) {
        if (ntp == null) {
            out.line("§c这个网口没有 NTP 服务器（DHCPv6 没给）");
            out.done();
            return;
        }
        NtpClient.query(stack, via, ntp).whenComplete((r, err) -> {
            if (err != null) {
                out.line("§c对时失败：" + message(err));
            } else {
                out.line("§eNTP " + ntp + "：UTC " + Instant.ofEpochMilli(r.serverTimeMillis) + "，层级 " + r.stratum
                        + "，参考标识 " + String.format("%08x", r.referenceId) + "，偏差 " + r.offsetMillis + " 毫秒，往返 " + r.roundTripMillis + " 毫秒");
            }
            out.done();
        });
    }

    // ---- 文字 ----

    public static String unreachText(int code) {
        switch (code) {
            case 0:
                return "没有路由";
            case 1:
                return "被禁止";
            case 2:
                return "超出源地址的范围";
            case 3:
                return "地址不可达";
            case 4:
                return "端口不可达";
            default:
                return "代码 " + code;
        }
    }

    public static String rcodeText(int rcode) {
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

    public static String typeText(int type) {
        return type == DnsMessage.TYPE_AAAA ? "AAAA" : type == DnsMessage.TYPE_CNAME ? "CNAME" : type == DnsMessage.TYPE_NS ? "NS"
                : type == DnsMessage.TYPE_TXT ? "TXT" : type == DnsMessage.TYPE_SOA ? "SOA" : type == DnsMessage.TYPE_ANY ? "ANY" : String.valueOf(type);
    }

    public static String message(Throwable t) {
        Throwable c = t instanceof CompletionException && t.getCause() != null ? t.getCause() : t;
        if (c instanceof NetworkException) {
            return c.getMessage() + "（" + ((NetworkException) c).reason + "）";
        }
        return String.valueOf(c);
    }
}
