package moe.qingu.youran.api.network.stack;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import moe.qingu.youran.api.network.Ipv6Address;
import moe.qingu.youran.api.network.packet.Checksum;
import moe.qingu.youran.api.network.packet.DnsMessage;
import moe.qingu.youran.api.network.packet.Icmpv6Message;
import moe.qingu.youran.api.network.packet.Ipv6Packet;
import moe.qingu.youran.api.network.packet.MalformedPacketException;
import moe.qingu.youran.api.network.packet.TcpSegment;
import moe.qingu.youran.api.network.packet.UdpDatagram;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Consumer;

/**
 * 与 079 服务的网卡固件（Go 写的 {@code scp079/netcard}）互通的向量（2.3.1.0 第 1 期，计划验收“互通”）。两边各自造一份向量文件，
 * 对方解得开、按同样的顺序回应：
 * <ul>
 *     <li>{@code interop/netcard/java-made.json}：本文件的 {@link Recorder} 用 Java 栈（两个 {@link NetStack}，零窗口那段的接收方用 Java 的
 *     {@link TcpSegment} 编码手工造）在手动事件循环上跑出来的对话；Go 那边用 netcard 扮演 {@code subject}，喂对端的包、比它的回应。</li>
 *     <li>{@code interop/netcard/go-made.json}：Go 那边两块 netcard 跑出来的对话（同一份文件也在 079 服务那边 netcard 的 {@code testdata/interop/}）；
 *     这边用 Java 栈扮演 {@code subject}（{@link Replayer}）。</li>
 * </ul>
 * 文件格式（两边一样）：{@code {"format":1,"madeBy":"…","scenarios":[{"name","a","b","subject","rx","steps":[…]}]}}，
 * 步骤按发生的顺序：包 {@code {"t":毫秒,"from":"a"|"b","hex":"整个 IPv6 包"}}，{@code subject} 那一方的程序动作 {@code {"do":…}}
 * （listen port、connect addr port、send data、read data、close、abort、expect_reset、ping addr seq、expect_ping seq、lookup name、
 * expect_lookup addr）。{@code rx} 是扮演 subject 的网卡的收缓冲（Go 的规格；Java 的接收窗口固定，不管它）。
 * <p>
 * 回放时随机选的东西（初始序号、临时端口、ping 的标识符、DNS 的标识符与源端口）两边不一样：从 subject 实际发出的第一个包学到对应关系，
 * 把之后喂给它的对端包改过来（确认号、目的端口、标识符；重算校验和）。比较前两边都“归一化”（{@link Norm}）：序号写成相对初始序号的偏移，
 * 窗口只看是不是 0，零窗口探测（Java 发 1 字节新数据、Go 发 SND.UNA−1 的空 ACK）都写成 PROBE，被同一段里后面的确认覆盖的纯 ACK 去掉，
 * 紧跟在最后一段数据后面的单独 FIN 并进那一段；不比 PSH。Go 那边的归一化与这里一字一样（{@code scp079/netcard/interop_test.go}）。
 */
final class NetcardInterop {
    static final Gson GSON = new GsonBuilder().setPrettyPrinting().disableHtmlEscaping().create();

    private NetcardInterop() {
    }

    static String hex(byte[] b) {
        StringBuilder sb = new StringBuilder(b.length * 2);
        for (byte x : b) {
            sb.append(Character.forDigit((x >> 4) & 0xF, 16)).append(Character.forDigit(x & 0xF, 16));
        }
        return sb.toString();
    }

    static byte[] unhex(String s) {
        byte[] b = new byte[s.length() / 2];
        for (int i = 0; i < b.length; i++) {
            b[i] = (byte) Integer.parseInt(s.substring(2 * i, 2 * i + 2), 16);
        }
        return b;
    }

    static int u16(byte[] b, int off) {
        return ((b[off] & 0xFF) << 8) | (b[off + 1] & 0xFF);
    }

    static long u32(byte[] b, int off) {
        return ((long) u16(b, off) << 16) | u16(b, off + 2);
    }

    static void put16(byte[] b, int off, int v) {
        b[off] = (byte) (v >> 8);
        b[off + 1] = (byte) v;
    }

    static void put32(byte[] b, int off, long v) {
        put16(b, off, (int) (v >>> 16) & 0xFFFF);
        put16(b, off + 2, (int) v & 0xFFFF);
    }

    /** 重算上层（TCP、UDP、ICMPv6）校验和 */
    static void fixChecksum(byte[] p) {
        int nh = p[6] & 0xFF;
        int off = nh == Ipv6Packet.PROTO_TCP ? 16 : nh == Ipv6Packet.PROTO_UDP ? 6 : 2;
        Ipv6Address src = Ipv6Address.read(p, 8);
        Ipv6Address dst = Ipv6Address.read(p, 24);
        byte[] up = java.util.Arrays.copyOfRange(p, 40, p.length);
        put16(up, off, 0);
        int sum = Checksum.upperLayer(src, dst, nh, up, 0, up.length);
        if (nh == Ipv6Packet.PROTO_UDP && sum == 0) {
            sum = 0xFFFF;
        }
        put16(p, 40 + off, sum);
    }

    // ---------------------------------------------------------------- 文件

    /** 一个场景 */
    static final class Scenario {
        final String name;
        final Ipv6Address a;
        final Ipv6Address b;
        final String subject;
        final int rx;
        final JsonArray steps = new JsonArray();

        Scenario(String name, Ipv6Address a, Ipv6Address b, String subject, int rx) {
            this.name = name;
            this.a = a;
            this.b = b;
            this.subject = subject;
            this.rx = rx;
        }

        JsonObject toJson() {
            JsonObject o = new JsonObject();
            o.addProperty("name", name);
            o.addProperty("a", a.toString());
            o.addProperty("b", b.toString());
            o.addProperty("subject", subject);
            o.addProperty("rx", rx);
            o.add("steps", steps);
            return o;
        }

        static Scenario of(JsonObject o) {
            Scenario s = new Scenario(o.get("name").getAsString(), Ipv6Address.parse(o.get("a").getAsString()), Ipv6Address.parse(o.get("b").getAsString()),
                    o.get("subject").getAsString(), o.has("rx") ? o.get("rx").getAsInt() : 0);
            s.steps.addAll(o.getAsJsonArray("steps"));
            return s;
        }

        Ipv6Address addr(String side) {
            return "a".equals(side) ? a : b;
        }
    }

    static String file(String madeBy, String note, List<Scenario> scenarios) {
        JsonObject o = new JsonObject();
        o.addProperty("format", 1);
        o.addProperty("madeBy", madeBy);
        o.addProperty("note", note);
        JsonArray arr = new JsonArray();
        for (Scenario s : scenarios) {
            arr.add(s.toJson());
        }
        o.add("scenarios", arr);
        return GSON.toJson(o) + "\n";
    }

    static List<Scenario> parse(String text) {
        JsonObject o = GSON.fromJson(text, JsonObject.class);
        if (o.get("format").getAsInt() != 1) {
            throw new IllegalArgumentException("向量文件格式不认得");
        }
        List<Scenario> out = new ArrayList<>();
        for (JsonElement e : o.getAsJsonArray("scenarios")) {
            out.add(Scenario.of(e.getAsJsonObject()));
        }
        return out;
    }

    // ---------------------------------------------------------------- 记录（Java ↔ Java）

    /**
     * 在手动事件循环上记一段对话：包隔 1 毫秒到，每个包与 subject 的程序动作按发生的顺序记进场景。
     */
    static final class Recorder {
        final ManualEventLoop loop = new ManualEventLoop();
        final Scenario sc;
        private final Map<Ipv6Address, Consumer<byte[]>> hosts = new HashMap<>();
        private final long start;

        Recorder(Scenario sc) {
            this.sc = sc;
            this.start = loop.nowMillis();
        }

        NetStack stack(Ipv6Address addr, long seed) {
            NetStack s = new NetStack(loop, p -> send(addr, p), new java.util.Random(seed));
            s.addAddress(addr);
            hosts.put(addr, s::receive);
            return s;
        }

        /** 不是协议栈的一方（手工造包的）：收到发给 addr 的包时交给 h */
        void host(Ipv6Address addr, Consumer<byte[]> h) {
            hosts.put(addr, h);
        }

        /**
         * 记成 subject 看到的顺序：subject 发的包在发出时记，对端发的包在送到 subject 时记（隔 1 毫秒），这样每个对端包后面跟的就是 subject 对它的回应
         */
        void send(Ipv6Address from, byte[] p) {
            String side = from.equals(sc.a) ? "a" : "b";
            byte[] copy = p.clone();
            if (side.equals(sc.subject)) {
                log(side, copy);
            }
            Consumer<byte[]> h = hosts.get(Ipv6Packet.destinationOf(copy));
            loop.schedule(() -> {
                if (!side.equals(sc.subject)) {
                    log(side, copy);
                }
                if (h != null) {
                    h.accept(copy);
                }
            }, 1);
        }

        private void log(String side, byte[] p) {
            JsonObject o = new JsonObject();
            o.addProperty("t", loop.nowMillis() - start);
            o.addProperty("from", side);
            o.addProperty("hex", hex(p));
            sc.steps.add(o);
        }

        /** subject 的一个程序动作 */
        void act(String what, Object... kv) {
            JsonObject o = new JsonObject();
            o.addProperty("do", what);
            for (int i = 0; i + 1 < kv.length; i += 2) {
                Object v = kv[i + 1];
                if (v instanceof Number) {
                    o.addProperty((String) kv[i], (Number) v);
                } else {
                    o.addProperty((String) kv[i], String.valueOf(v));
                }
            }
            sc.steps.add(o);
        }

        void run(long ms) {
            loop.advance(ms);
        }
    }

    // ---------------------------------------------------------------- 归一化

    /** 一方看到的对话状态（初始序号、对端最近的窗口与确认号），用来把包写成可比较的样子 */
    static final class Norm {
        /** subject 的初始序号（学到以前为 null）、对端的初始序号 */
        Long subjectIsn;
        Long peerIsn;
        /** 对端最近一个 TCP 包的窗口、确认号（零窗口探测的判断用） */
        int peerWindow = -1;
        long peerAck;
        /** subject 发过的 1 字节零窗口探测：相对序号 → 那个字节（Java 栈的探测是新数据，见 {@link Replayer} 的已知差异） */
        final Map<Long, Byte> probeBytes = new HashMap<>();

        /** 喂给 subject 的对端包：记下对端的初始序号、窗口、确认号 */
        void peer(byte[] p) {
            if ((p[6] & 0xFF) != Ipv6Packet.PROTO_TCP || p.length < 60) {
                return;
            }
            int flags = p[40 + 13] & 0x3F;
            if ((flags & TcpSegment.SYN) != 0) {
                peerIsn = u32(p, 44);
            }
            peerWindow = u16(p, 54);
            if ((flags & TcpSegment.ACK) != 0) {
                peerAck = u32(p, 48);
            }
        }

        /** subject 发出的一个包写成一行（{@code syn} 是 subject 的 SYN 时顺便记下初始序号） */
        String describe(byte[] p) {
            StringBuilder sb = new StringBuilder("h").append(p[7] & 0xFF).append(' ');
            int nh = p[6] & 0xFF;
            if (nh == Ipv6Packet.PROTO_TCP) {
                int hl = ((p[52] >> 4) & 0xF) * 4;
                long seq = u32(p, 44);
                long ack = u32(p, 48);
                int flags = p[53] & 0x3F;
                int win = u16(p, 54);
                int len = p.length - 40 - hl;
                if ((flags & TcpSegment.SYN) != 0 && subjectIsn == null) {
                    subjectIsn = seq;
                }
                if (peerWindow == 0 && (flags & (TcpSegment.SYN | TcpSegment.FIN | TcpSegment.RST)) == 0
                        && ((len == 1 && seq == peerAck) || (len == 0 && seq == ((peerAck - 1) & 0xFFFFFFFFL)))) {
                    if (len == 1) {
                        probeBytes.put((seq - (subjectIsn == null ? 0 : subjectIsn)) & 0xFFFFFFFFL, p[40 + hl]);
                    }
                    return sb.append("TCP PROBE").toString();
                }
                sb.append("TCP ");
                if ((flags & TcpSegment.SYN) != 0) {
                    sb.append('S');
                }
                if ((flags & TcpSegment.ACK) != 0) {
                    sb.append('A');
                }
                if ((flags & TcpSegment.FIN) != 0) {
                    sb.append('F');
                }
                if ((flags & TcpSegment.RST) != 0) {
                    sb.append('R');
                }
                sb.append(" s=").append((seq - (subjectIsn == null ? 0 : subjectIsn)) & 0xFFFFFFFFL);
                if ((flags & TcpSegment.ACK) != 0) {
                    sb.append(" a=").append((ack - (peerIsn == null ? 0 : peerIsn)) & 0xFFFFFFFFL);
                }
                sb.append(" w=").append(win == 0 ? "0" : "+");
                for (int o = 60; o < 40 + hl; ) {
                    int kind = p[o] & 0xFF;
                    if (kind == 0) {
                        break;
                    }
                    if (kind == 1) {
                        o++;
                        continue;
                    }
                    if (kind == 2 && o + 4 <= 40 + hl) {
                        sb.append(" m=").append(u16(p, o + 2));
                    }
                    o += Math.max(2, p[o + 1] & 0xFF);
                }
                if (len > 0) {
                    sb.append(" d=").append(hex(java.util.Arrays.copyOfRange(p, 40 + hl, p.length)));
                }
                return sb.toString();
            }
            if (nh == Ipv6Packet.PROTO_ICMPV6) {
                int type = p[40] & 0xFF;
                int code = p[41] & 0xFF;
                sb.append("ICMP t=").append(type).append(" c=").append(code);
                if (type == Icmpv6Message.ECHO_REPLY) {
                    sb.append(" id=").append(u16(p, 44)).append(" q=").append(u16(p, 46)).append(" d=").append(hex(java.util.Arrays.copyOfRange(p, 48, p.length)));
                } else if (type == Icmpv6Message.ECHO_REQUEST) {
                    sb.append(" q=").append(u16(p, 46));
                } else if (type < 128) {
                    sb.append(" inv=").append(hex(java.util.Arrays.copyOfRange(p, 48, Math.min(p.length, 96))));
                }
                return sb.toString();
            }
            if (nh == Ipv6Packet.PROTO_UDP) {
                int dport = u16(p, 42);
                byte[] data = java.util.Arrays.copyOfRange(p, 48, p.length);
                if (dport == DnsMessage.PORT) {
                    try {
                        DnsMessage m = DnsMessage.parse(data);
                        DnsMessage.Question q = m.question();
                        return sb.append("DNSQ ").append(q.name).append(' ').append(q.type).append(' ').append(q.qclass)
                                .append(" rd=").append(m.recursionDesired ? 1 : 0).toString();
                    } catch (MalformedPacketException e) {
                        return sb.append("DNSQ 坏的：").append(e.getMessage()).toString();
                    }
                }
                return sb.append("UDP ").append(dport).append(" d=").append(hex(data)).toString();
            }
            return sb.append("NH ").append(nh).toString();
        }

        /**
         * 一段（两个对端包之间）subject 发出的包写成可比较的几行：纯 ACK 被同一段里后面的包的确认覆盖、或被前面带数据 / FIN 的包以同样的窗口（是不是 0）
         * 确认过了的，去掉（先发确认还是先发数据、要不要补一个纯 ACK，两种实现不一样，都对）；紧跟在最后一段数据后面的单独 FIN 并进那一段
         */
        static List<String> burst(List<String> lines) {
            List<String> out = new ArrayList<>();
            for (int i = 0; i < lines.size(); i++) {
                String l = lines.get(i);
                long a = ackOf(l);
                if (isPureAck(l)) {
                    boolean covered = false;
                    for (int j = 0; j < lines.size() && !covered; j++) {
                        String o = lines.get(j);
                        long b = ackOf(o);
                        if (j == i || b < 0 || (int) (b - a) < 0) {
                            continue;
                        }
                        covered = j > i || !isPureAck(o) && windowOf(o).equals(windowOf(l));
                    }
                    if (covered) {
                        continue;
                    }
                }
                if (!out.isEmpty() && isBareFin(l) && finFollows(out.get(out.size() - 1), l)) {
                    String prev = out.remove(out.size() - 1);
                    out.add(prev.replaceFirst("TCP A ", "TCP AF ").replaceFirst("TCP SA ", "TCP SAF "));
                    continue;
                }
                out.add(l);
            }
            return out;
        }

        static boolean isPureAck(String l) {
            return l.contains("TCP A s=") && !l.contains(" d=");
        }

        static boolean isBareFin(String l) {
            return l.contains("TCP AF s=") && !l.contains(" d=");
        }

        /** l 的 FIN 正好接在 prev 的数据后面（prev 是带数据、确认号一样的 ACK 段） */
        static boolean finFollows(String prev, String fin) {
            if (!prev.contains("TCP A s=") || !prev.contains(" d=") || ackOf(prev) != ackOf(fin)) {
                return false;
            }
            long s = field(prev, " s=");
            long n = (prev.length() - prev.indexOf(" d=") - 3) / 2;
            return field(fin, " s=") == ((s + n) & 0xFFFFFFFFL);
        }

        static String windowOf(String l) {
            int i = l.indexOf(" w=");
            return i < 0 ? "" : l.substring(i + 3, i + 4);
        }

        static long ackOf(String l) {
            return l.contains("TCP ") && l.contains(" a=") ? field(l, " a=") : -1;
        }

        static long field(String l, String key) {
            int i = l.indexOf(key) + key.length();
            int j = i;
            while (j < l.length() && Character.isDigit(l.charAt(j))) {
                j++;
            }
            return Long.parseLong(l.substring(i, j));
        }
    }

    // ---------------------------------------------------------------- 回放（Java 当 subject）

    /**
     * 用 Java 栈扮演场景里的 subject：按顺序做程序动作、喂对端的包（改过随机选的东西），每段比较 subject 的回应。
     * 时间：喂一个对端包前把手动时钟拨到它在对话里的时刻（等 subject 的回应时多拨的时间算进去）；subject 该回却还没回时一次拨 10 毫秒，最多 120 秒。
     */
    static final class Replayer {
        final Scenario sc;
        final ManualEventLoop loop = new ManualEventLoop();
        final NetStack stack;
        final Ipv6Address self;
        final Ipv6Address peer;
        final String subject;
        final List<byte[]> out = new ArrayList<>();
        final Norm exp = new Norm();
        final Norm act = new Norm();
        final List<String> problems = new ArrayList<>();
        final List<String> log = new ArrayList<>();
        /** 按“已知差异”放过的地方（见 {@link #knownDifference}） */
        final List<String> known = new ArrayList<>();
        // 学到的对应关系
        Long isnDelta;
        int portFrom = -1;
        int portTo = -1;
        int pingIdFrom = -1;
        int pingIdTo = -1;
        final Map<Integer, byte[]> pingData = new HashMap<>();
        int dnsIdFrom = -1;
        int dnsIdTo = -1;
        int dnsPortFrom = -1;
        int dnsPortTo = -1;
        // 程序
        TcpConnection conn;
        final java.io.ByteArrayOutputStream received = new java.io.ByteArrayOutputStream();
        NetworkException closedWith;
        boolean closedCalled;
        final Map<Integer, PingResult> pings = new HashMap<>();
        DnsResolver.Result lookup;
        int nextPingSeq = 1;

        Replayer(Scenario sc) {
            this.sc = sc;
            this.subject = sc.subject;
            this.self = sc.addr(subject);
            this.peer = sc.addr("a".equals(subject) ? "b" : "a");
            this.stack = new NetStack(loop, p -> out.add(p.clone()), new java.util.Random(99));
            stack.addAddress(self);
        }

        private final TcpConnection.Listener app = new TcpConnection.Listener() {
            @Override
            public void onData(TcpConnection c, byte[] d) {
                received.write(d, 0, d.length);
            }

            @Override
            public void onClosed(TcpConnection c, NetworkException error) {
                closedCalled = true;
                closedWith = error;
            }
        };

        List<String> run() {
            List<byte[]> expected = new ArrayList<>();
            long lastT = 0;
            long since = 0;
            for (JsonElement e : sc.steps) {
                JsonObject st = e.getAsJsonObject();
                if (st.has("do")) {
                    act(st);
                    loop.drain();
                } else if (subject.equals(st.get("from").getAsString())) {
                    expected.add(unhex(st.get("hex").getAsString()));
                } else {
                    since += settle(expected);
                    expected.clear();
                    long t = st.get("t").getAsLong();
                    long adv = Math.max(0, t - lastT - since);
                    if (adv > 0) {
                        loop.advance(adv);
                        if (!out.isEmpty()) {
                            List<String> extra = describeAll(act, out);
                            if (act.peerWindow == 0 && extra.stream().allMatch(l -> l.endsWith("TCP PROBE"))) {
                                known.add("对端窗口为 0 时多发了零窗口探测 " + extra);
                            } else {
                                problems.add("等对端下一个包的时候 subject 多发了：" + extra);
                            }
                            out.clear();
                        }
                    }
                    lastT = t;
                    since = 0;
                    byte[] orig = unhex(st.get("hex").getAsString());
                    byte[] p = rewrite(orig);
                    exp.peer(orig);
                    act.peer(p);
                    stack.receive(p);
                    loop.drain();
                }
            }
            settle(expected);
            return problems;
        }

        private static List<String> describeAll(Norm n, List<byte[]> ps) {
            List<String> l = new ArrayList<>();
            for (byte[] p : ps) {
                l.add(n.describe(p));
            }
            return l;
        }

        /** 比一段：返回为了等 subject 回应多拨的毫秒数 */
        long settle(List<byte[]> expected) {
            List<String> e = Norm.burst(describeAll(exp, expected));
            List<String> a = Norm.burst(describeAll(act, out));
            long waited = 0;
            while (a.size() < e.size() && waited < 120_000) {
                loop.advance(10);
                waited += 10;
                a = Norm.burst(describeAll(act, out));
            }
            learn(expected, out);
            log.add("期望 " + e + "\n实际 " + a);
            if (!e.equals(a)) {
                if (knownDifference(e, a)) {
                    known.add("窗口打开后从探测之后接着发：期望 " + e + "，实际 " + a);
                } else {
                    problems.add("回应不一样：\n  期望（" + subject + "）" + e + "\n  实际（Java）" + a);
                }
            }
            out.clear();
            return waited;
        }

        /**
         * 已知差异（2.3.1.0 第 1 期互通时发现，记在实现记录里，等改 Java 栈时再定）：Java 栈的零窗口探测是 1 字节<b>新数据</b>（{@code TcpConnection#trySend}），
         * 而且在对端通告 0 窗口时马上发、按重传计时器重发；窗口打开后接着探测<b>之后</b>的字节发，探测的那 1 字节等重传补。Go 的 netcard 探测发
         * SND.UNA−1 的空 ACK，窗口打开后从 SND.UNA 发。对端（netcard 当接收方）两种都能处理（窗口为 0 时不收探测、回 ACK；乱序的先存着），
         * 只是 Java 一侧窗口打开后要等一次重传。这里只在“这一段的数据加上之前发过的探测字节，与期望的数据逐字节一样、没有数据的包一样”时放过。
         */
        boolean knownDifference(List<String> e, List<String> a) {
            if (act.probeBytes.isEmpty()) {
                return false;
            }
            Map<Long, Byte> want = new HashMap<>();
            Map<Long, Byte> got = new HashMap<>(act.probeBytes);
            List<String> wantRest = new ArrayList<>();
            List<String> gotRest = new ArrayList<>();
            split(e, want, wantRest);
            split(a, got, gotRest);
            if (!wantRest.equals(gotRest) || want.isEmpty()) {
                return false;
            }
            for (Map.Entry<Long, Byte> w : want.entrySet()) {
                if (!w.getValue().equals(got.get(w.getKey()))) {
                    return false;
                }
            }
            return true;
        }

        /** 带数据的行拆成“相对序号 → 字节”，其余的去掉序号留下 */
        private static void split(List<String> lines, Map<Long, Byte> data, List<String> rest) {
            for (String l : lines) {
                int d = l.indexOf(" d=");
                if (d < 0 || !l.contains("TCP ")) {
                    rest.add(l);
                    continue;
                }
                long seq = Norm.field(l, " s=");
                byte[] b = unhex(l.substring(d + 3));
                for (int i = 0; i < b.length; i++) {
                    data.put((seq + i) & 0xFFFFFFFFL, b[i]);
                }
                rest.add(l.substring(0, l.indexOf(" s=")) + l.substring(l.indexOf(" a="), d));
            }
        }

        /** 从 subject 实际发出的包学对应关系 */
        void learn(List<byte[]> expected, List<byte[]> actual) {
            byte[] es = firstSyn(expected);
            byte[] as = firstSyn(actual);
            if (es != null && as != null && isnDelta == null) {
                isnDelta = (u32(as, 44) - u32(es, 44)) & 0xFFFFFFFFL;
                if (u16(es, 40) != u16(as, 40)) {
                    portFrom = u16(es, 40);
                    portTo = u16(as, 40);
                }
            }
            for (byte[] x : actual) {
                if ((x[6] & 0xFF) == Ipv6Packet.PROTO_ICMPV6 && (x[40] & 0xFF) == Icmpv6Message.ECHO_REQUEST) {
                    pingIdTo = u16(x, 44);
                    pingData.put(u16(x, 46), java.util.Arrays.copyOfRange(x, 48, x.length));
                }
                if ((x[6] & 0xFF) == Ipv6Packet.PROTO_UDP && u16(x, 42) == DnsMessage.PORT) {
                    dnsIdTo = u16(x, 48);
                    dnsPortTo = u16(x, 40);
                }
            }
            for (byte[] x : expected) {
                if ((x[6] & 0xFF) == Ipv6Packet.PROTO_ICMPV6 && (x[40] & 0xFF) == Icmpv6Message.ECHO_REQUEST) {
                    pingIdFrom = u16(x, 44);
                }
                if ((x[6] & 0xFF) == Ipv6Packet.PROTO_UDP && u16(x, 42) == DnsMessage.PORT) {
                    dnsIdFrom = u16(x, 48);
                    dnsPortFrom = u16(x, 40);
                }
            }
        }

        private static byte[] firstSyn(List<byte[]> ps) {
            for (byte[] p : ps) {
                if ((p[6] & 0xFF) == Ipv6Packet.PROTO_TCP && (p[53] & TcpSegment.SYN) != 0) {
                    return p;
                }
            }
            return null;
        }

        /** 把对话里的对端包改成对得上 subject 实际选的东西 */
        byte[] rewrite(byte[] orig) {
            byte[] p = orig.clone();
            int nh = p[6] & 0xFF;
            if (nh == Ipv6Packet.PROTO_TCP) {
                if (u16(p, 42) == portFrom) {
                    put16(p, 42, portTo);
                }
                if ((p[53] & TcpSegment.ACK) != 0 && isnDelta != null) {
                    put32(p, 48, (u32(p, 48) + isnDelta) & 0xFFFFFFFFL);
                }
                fixChecksum(p);
                return p;
            }
            if (nh == Ipv6Packet.PROTO_ICMPV6 && (p[40] & 0xFF) == Icmpv6Message.ECHO_REPLY && u16(p, 44) == pingIdFrom && pingIdTo >= 0) {
                byte[] data = pingData.get(u16(p, 46));
                byte[] q = java.util.Arrays.copyOf(p, 48 + (data == null ? p.length - 48 : data.length));
                if (data != null) {
                    System.arraycopy(data, 0, q, 48, data.length);
                }
                put16(q, 44, pingIdTo);
                put16(q, 4, q.length - 40);
                fixChecksum(q);
                return q;
            }
            if (nh == Ipv6Packet.PROTO_UDP && u16(p, 40) == DnsMessage.PORT && u16(p, 42) == dnsPortFrom && dnsPortTo >= 0) {
                put16(p, 42, dnsPortTo);
                if (u16(p, 48) == dnsIdFrom) {
                    put16(p, 48, dnsIdTo);
                }
                fixChecksum(p);
                return p;
            }
            return p;
        }

        void act(JsonObject st) {
            String what = st.get("do").getAsString();
            switch (what) {
                case "listen":
                    stack.listen(null, st.get("port").getAsInt(), c -> {
                        conn = c;
                        c.setListener(app);
                    });
                    break;
                case "connect":
                    stack.connect(null, Ipv6Address.parse(st.get("addr").getAsString()), st.get("port").getAsInt(), app).whenComplete((c, err) -> {
                        if (c != null) {
                            conn = c;
                        }
                    });
                    break;
                case "send":
                    conn.write(bytes(st.get("data").getAsString()));
                    break;
                case "read": {
                    String got = text(received.toByteArray());
                    received.reset();
                    if (!got.equals(st.get("data").getAsString())) {
                        problems.add("程序收到的数据不一样：期望 " + st.get("data") + "，实际 " + GSON.toJson(got));
                    }
                    break;
                }
                case "close":
                    conn.close();
                    break;
                case "abort":
                    conn.abort();
                    break;
                case "expect_reset":
                    if (!closedCalled || closedWith == null) {
                        problems.add("应当知道连接被对方重置了：" + closedCalled + " " + closedWith);
                    }
                    break;
                case "ping": {
                    int seq = st.get("seq").getAsInt();
                    if (seq != nextPingSeq) {
                        problems.add("Java 栈的 ping 序号从 1 起自己数，向量要的是 " + seq);
                    }
                    nextPingSeq++;
                    stack.ping(null, Ipv6Address.parse(st.get("addr").getAsString()), 8, 60_000).whenComplete((r, err) -> pings.put(seq, r));
                    break;
                }
                case "expect_ping": {
                    PingResult r = pings.get(st.get("seq").getAsInt());
                    if (r == null || r.status != PingResult.Status.REPLY) {
                        problems.add("ping 应当有回音：" + r);
                    }
                    break;
                }
                case "lookup":
                    new DnsResolver(stack, peer).resolveAaaa(st.get("name").getAsString()).whenComplete((r, err) -> lookup = r);
                    break;
                case "expect_lookup":
                    if (lookup == null || !lookup.addresses.contains(Ipv6Address.parse(st.get("addr").getAsString()))) {
                        problems.add("查名字应当得到 " + st.get("addr") + "：" + (lookup == null ? null : lookup.addresses));
                    }
                    break;
                default:
                    problems.add("不认得的动作 " + what);
            }
        }
    }

    /** 文字转字节（向量里的程序数据都是 ASCII） */
    static byte[] bytes(String s) {
        return s.getBytes(StandardCharsets.ISO_8859_1);
    }

    static String text(byte[] b) {
        return new String(b, StandardCharsets.ISO_8859_1);
    }
}
