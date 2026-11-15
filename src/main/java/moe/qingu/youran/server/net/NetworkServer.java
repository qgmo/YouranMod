package moe.qingu.youran.server.net;

import moe.qingu.youran.api.network.ConfirmRequest;
import moe.qingu.youran.api.network.ConfirmResult;
import moe.qingu.youran.api.network.DefaultDimensionNetwork;
import moe.qingu.youran.api.network.DeviceCapability;
import moe.qingu.youran.api.network.DomainNames;
import moe.qingu.youran.api.network.location.LocationProof;
import moe.qingu.youran.api.network.location.LocationResult;
import moe.qingu.youran.net.ConfirmMessages;
import moe.qingu.youran.net.LocationMessages;
import moe.qingu.youran.net.YouranNetwork;
import moe.qingu.youran.server.net.location.LocationService;
import moe.qingu.youran.server.net.location.LocationSigner;
import moe.qingu.youran.server.net.service.captcha.SourceLimiter;
import moe.qingu.youran.api.network.DeviceLocation;
import moe.qingu.youran.api.network.DimensionNetwork;
import moe.qingu.youran.api.network.HostContext;
import moe.qingu.youran.api.network.Ipv6Address;
import moe.qingu.youran.api.network.NetworkDevices;
import moe.qingu.youran.api.network.NetworkLayout;
import moe.qingu.youran.api.network.SourceIdentity;
import moe.qingu.youran.api.network.YouranNetworkApi;
import moe.qingu.youran.api.network.packet.Ipv6Packet;
import moe.qingu.youran.api.network.stack.ExecutorEventLoop;
import moe.qingu.youran.api.network.stack.NetStack;
import moe.qingu.youran.network.NetworkBackend;
import moe.qingu.youran.network.NetworkHooks;
import moe.qingu.youran.network.NetworkRegistry;
import moe.qingu.youran.network.ServerNetworkModule;
import moe.qingu.youran.server.net.service.AuthoritativeDnsServer;
import moe.qingu.youran.server.net.service.DnsZoneData;
import moe.qingu.youran.server.net.service.NtpServer;
import moe.qingu.youran.server.net.service.RecursiveResolver;
import moe.qingu.youran.server.net.service.TestWebsite;
import moe.qingu.youran.server.net.service.captcha.CaptchaLocalServer;
import moe.qingu.youran.server.net.service.captcha.CaptchaService;
import moe.qingu.youran.server.net.service.captcha.CaptchaStore;
import moe.qingu.youran.server.net.tls.IntermediateCa;
import moe.qingu.youran.server.net.tls.IssuingTlsContext;
import moe.qingu.youran.server.net.tls.LeafCache;
import moe.qingu.youran.api.network.tls.TlsServerContext;
import net.minecraft.entity.player.EntityPlayerMP;
import net.minecraft.server.MinecraftServer;
import net.minecraftforge.common.MinecraftForge;
import net.minecraftforge.fml.common.event.FMLServerStartingEvent;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import java.io.File;
import java.security.SecureRandom;
import java.util.Collection;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Supplier;

/**
 * 服务端网络的入口（只在完整包里）：开服时读存档里的分配表、分地址、启动网络线程（路由器、协议栈、机房服务都在上面）、
 * 登记测试命令；关服时停干净。由 {@code network.NetworkHooks} 按类名加载。
 */
public final class NetworkServer implements ServerNetworkModule, NetworkBackend {
    static final Logger LOG = LogManager.getLogger("YouranMod");
    static final String THREAD_NAME = "YouranMod 网络";

    // YouranMod 机房里自己的主机（主机号固定，见网络系统实现文档）
    static final String HOST_ROUTER = "youranmod:router";
    static final String HOST_RESOLVER = "youranmod:resolver";
    static final String HOST_NTP = "youranmod:ntp";
    static final String HOST_ROOT_DNS = "youranmod:dns-root";
    static final String HOST_YR_DNS = "youranmod:dns-yr";
    static final String HOST_TEST = "youranmod:test";
    /** 人机验证（captcha.yr，服主 11-15） */
    static final String HOST_CAPTCHA = "youranmod:captcha";
    static final String CAPTCHA_NAME = "captcha.yr";
    /** 定位模块的公钥与核对（location.yr，服主 11-15） */
    static final String HOST_LOCATION = "youranmod:location";
    static final String LOCATION_NAME = moe.qingu.youran.api.network.location.LocationProofs.DOMAIN;
    /** 定位模块的签名密钥文件（相对服务器根目录） */
    static final String LOCATION_KEY_FILE = "config/youranmod-location-key.pem";
    /** 聚合付款服务（pay.yr，服主 11-15） */
    static final String HOST_PAY = "youranmod:pay";
    static final String PAY_NAME = moe.qingu.youran.api.pay.PayApi.DOMAIN;
    /** 测试网站的国际化域名别名 */
    static final String TEST_IDN_NAME = "测试.yr";
    static final long PART_ROUTER = 0x1;
    static final long PART_RESOLVER = 0x53;
    static final long PART_NTP = 0x123;
    static final long PART_ROOT_DNS = 0x1_0053L;
    static final long PART_YR_DNS = 0x2_0053L;
    /** 一个玩家在 Netty 线程上排进网络线程、还没处理的包最多这么多；再多的直接丢掉（防止一个客户端塞满队列） */
    static final int MAX_QUEUED_PER_PLAYER = 256;

    private MinecraftServer server;
    private ExecutorEventLoop loop;
    private AllocationTable table;
    private AddressBook book;
    private DeviceTable devices;
    private Router router;
    private NicGateway nic;
    private TestDevices testDevices;
    private NetworkEvents events;
    private RejectLog rejects;
    private RecursiveResolver resolver;
    /** 玩家临时地址；没开（配置关掉、序号冲突）时为 null */
    private PlayerNics players;
    /** captcha.yr 与它的本机端口（没开为 null） */
    private CaptchaService captcha;
    private CaptchaLocalServer captchaLocal;
    /** 系统确认框（主线程） */
    private ConfirmPrompts prompts;
    /** 主线程上的拒绝日志（{@link #rejects} 只给网络线程用） */
    private RejectLog mainRejects;
    /** 定位模块的签名密钥；没有（读不了）为 null */
    private volatile LocationSigner locationSigner;
    /** pay.yr 的账本与给模组的接口；没开（配置关掉、读不了账本）为 null */
    private moe.qingu.youran.server.net.pay.PayServer pay;
    /** 浏览器主页 180.yr、广告平台、帮助（服主 11-15）；没开为 null */
    private moe.qingu.youran.server.net.nav.NavServer nav;
    /** 每个玩家请位置的频率（主线程） */
    private final SourceLimiter locationLimit = new SourceLimiter(0.5, 3, 4096);
    /** YouranMod 自己的 .yr / .com 名字（A-label）→ 地址（开服时算好，任意线程读） */
    private volatile Map<String, Ipv6Address> nameAddresses = Collections.emptyMap();
    private final Map<String, NetStack> hostStacks = new LinkedHashMap<>();
    private final Set<CompletableFuture<?>> mainThreadTasks = ConcurrentHashMap.newKeySet();
    private final Map<UUID, AtomicInteger> queued = new ConcurrentHashMap<>();
    private final AtomicLong overflowDrops = new AtomicLong();
    private final List<String> tlds = new ArrayList<>();
    private final Map<String, String> names = new LinkedHashMap<>();
    /** 每台主机的域名（HTTPS 证书用） */
    private final Map<String, List<String>> hostNames = new LinkedHashMap<>();
    /** 中间 CA 签好的叶子证书；没配中间 CA 时为 null（只有 HTTP） */
    private LeafCache leafCache;
    private Ipv6Address routerAddress;
    private Ipv6Address resolverAddress;
    private Ipv6Address ntpAddress;

    // ---- 启停 ----

    @Override
    public void start(MinecraftServer server, FMLServerStartingEvent event) {
        stop();
        if (!NetworkConfig.enabled) {
            LOG.info("游戏内网络在配置里关掉了（network.enabled=false）");
            return;
        }
        this.server = server;
        NetworkSaveData data = NetworkSaveData.get(server.getWorld(0));
        table = data.table;
        List<String> problems = new ArrayList<>();
        if (table.globalId < 0) {
            table.globalId = UlaPrefix.generateGlobalId();
            table.dirty = true;
            LOG.info("第一次启动游戏内网络，按 RFC 4193 生成了私有前缀 {}/48", NetworkLayout.fromGlobalId(table.globalId).prefix());
        }
        NetworkLayout layout = NetworkLayout.fromGlobalId(table.globalId);

        // 维度
        Map<Integer, Integer> dims = table.assignDimensions(NetworkConfig.wifiDimensions, problems);
        Map<Integer, DimensionNetwork> custom = NetworkRegistry.dimensionNetworks();
        Map<Integer, AddressBook.Dim> dimInfo = new LinkedHashMap<>();
        for (Map.Entry<Integer, Integer> e : dims.entrySet()) {
            DimensionNetwork n = custom.getOrDefault(e.getKey(), DefaultDimensionNetwork.INSTANCE);
            dimInfo.put(e.getKey(), new AddressBook.Dim(e.getKey(), e.getValue(), n));
        }

        // 机房：YouranMod 的固定为 0 号
        List<AllocationTable.Request> dcRequests = new ArrayList<>();
        dcRequests.add(new AllocationTable.Request(YouranNetworkApi.YOURANMOD_DATACENTER, 0, null));
        // 玩家临时地址：最后一个 /96，固定序号（和别的机房冲突时由分配表报出来，玩家临时地址就不开）
        dcRequests.add(new AllocationTable.Request(YouranNetworkApi.PLAYERS_DATACENTER, NetworkLayout.PLAYERS_DATACENTER, null));
        for (NetworkRegistry.DatacenterDecl d : NetworkRegistry.datacenters()) {
            dcRequests.add(new AllocationTable.Request(d.key, d.fixedIndex, null));
        }
        Map<String, Long> activeDc = table.assignDatacenters(dcRequests, problems);

        // 主机：YouranMod 自己的先登记
        List<NetworkRegistry.HostDecl> hostDecls = NetworkRegistry.hosts();
        List<AllocationTable.Request> hostRequests = new ArrayList<>();
        String dc = YouranNetworkApi.YOURANMOD_DATACENTER;
        hostRequests.add(new AllocationTable.Request(HOST_ROUTER, PART_ROUTER, dc));
        hostRequests.add(new AllocationTable.Request(HOST_RESOLVER, PART_RESOLVER, dc));
        hostRequests.add(new AllocationTable.Request(HOST_NTP, PART_NTP, dc));
        hostRequests.add(new AllocationTable.Request(HOST_ROOT_DNS, PART_ROOT_DNS, dc));
        hostRequests.add(new AllocationTable.Request(HOST_YR_DNS, PART_YR_DNS, dc));
        hostRequests.add(new AllocationTable.Request(HOST_TEST, -1, dc));
        hostRequests.add(new AllocationTable.Request(HOST_CAPTCHA, -1, dc));
        hostRequests.add(new AllocationTable.Request(HOST_LOCATION, -1, dc));
        if (moe.qingu.youran.server.net.pay.PayConfig.enabled) {
            hostRequests.add(new AllocationTable.Request(HOST_PAY, -1, dc));
        }
        if (moe.qingu.youran.server.net.nav.NavConfig.enabled) {
            hostRequests.add(new AllocationTable.Request(moe.qingu.youran.server.net.nav.NavServer.HOST, -1, dc));
        }
        for (NetworkRegistry.HostDecl h : hostDecls) {
            if (h.datacenter.equals(YouranNetworkApi.PLAYERS_DATACENTER)) {
                problems.add("主机 " + h.key + " 不能登记在玩家临时地址的 /96（" + h.datacenter + "）里，拒绝");
                continue;
            }
            hostRequests.add(new AllocationTable.Request(h.key, h.fixedHost, h.datacenter));
        }
        Map<String, AllocationTable.Host> activeHosts = table.assignHosts(hostRequests, activeDc.keySet(), problems);
        Map<String, Ipv6Address> hostAddresses = new LinkedHashMap<>();
        for (Map.Entry<String, AllocationTable.Host> e : activeHosts.entrySet()) {
            hostAddresses.put(e.getKey(), layout.hostAddress(activeDc.get(e.getValue().datacenter), e.getValue().hostPart));
        }
        if (table.dirty) {
            data.markDirty();
            table.dirty = false;
        }
        for (String p : problems) {
            LOG.warn("游戏内网络的地址分配：{}", p);
        }
        book = new AddressBook(layout, dimInfo, activeDc, hostAddresses);
        routerAddress = hostAddresses.get(HOST_ROUTER);
        resolverAddress = hostAddresses.get(HOST_RESOLVER);
        ntpAddress = hostAddresses.get(HOST_NTP);

        // DNS：顶级域与名字
        Map<String, NetworkRegistry.TldDecl> tldDecls = new LinkedHashMap<>();
        for (NetworkRegistry.TldDecl t : NetworkRegistry.tlds()) {
            if (t.tld.equals("yr") || t.tld.equals("com")) {
                LOG.warn("游戏内网络：顶级域 .{} 是 YouranMod 的，{} 不能登记", t.tld, t.hostKey);
            } else if (tldDecls.containsKey(t.tld)) {
                LOG.warn("游戏内网络：顶级域 .{} 登记了两次，后一次（{}）不算", t.tld, t.hostKey);
            } else if (!hostAddresses.containsKey(t.hostKey)) {
                LOG.warn("游戏内网络：顶级域 .{} 的权威服务器 {} 没有分到地址，不登记", t.tld, t.hostKey);
            } else {
                tldDecls.put(t.tld, t);
            }
        }
        Map<String, Ipv6Address> yrRecords = new LinkedHashMap<>();
        Map<String, Ipv6Address> comRecords = new LinkedHashMap<>();
        yrRecords.put("test.yr", hostAddresses.get(HOST_TEST));
        // 国际化域名的测试别名（DNS 与证书里是 A-label）
        yrRecords.put(moe.qingu.youran.api.network.DomainNames.toAscii(TEST_IDN_NAME), hostAddresses.get(HOST_TEST));
        if (hostAddresses.containsKey(HOST_CAPTCHA)) {
            yrRecords.put(CAPTCHA_NAME, hostAddresses.get(HOST_CAPTCHA));
        }
        if (hostAddresses.containsKey(HOST_LOCATION)) {
            yrRecords.put(LOCATION_NAME, hostAddresses.get(HOST_LOCATION));
        }
        if (hostAddresses.containsKey(HOST_PAY)) {
            yrRecords.put(PAY_NAME, hostAddresses.get(HOST_PAY));
        }
        if (hostAddresses.containsKey(moe.qingu.youran.server.net.nav.NavServer.HOST)) {
            for (String n : moe.qingu.youran.server.net.nav.NavServer.NAMES) {
                yrRecords.put(n, hostAddresses.get(moe.qingu.youran.server.net.nav.NavServer.HOST));
            }
        }
        for (NetworkRegistry.NameDecl n : NetworkRegistry.names()) {
            Ipv6Address a = hostAddresses.get(n.hostKey);
            Map<String, Ipv6Address> target = n.name.endsWith(".yr") ? yrRecords : comRecords;
            if (a == null) {
                LOG.warn("游戏内网络：名字 {} 指向的主机 {} 没有分到地址，不登记", n.name, n.hostKey);
            } else if (target.containsKey(n.name) || n.name.equals("ns.yr") || n.name.equals("root.yr")) {
                LOG.warn("游戏内网络：名字 {} 已经有了，{} 不能再登记", n.name, n.hostKey);
            } else {
                target.put(n.name, a);
            }
        }
        tlds.clear();
        tlds.addAll(tldDecls.keySet());
        names.clear();
        for (String n : yrRecords.keySet()) {
            names.put(n, keyOf(hostAddresses, yrRecords.get(n)));
        }
        for (String n : comRecords.keySet()) {
            names.put(n, keyOf(hostAddresses, comRecords.get(n)));
        }
        Map<String, Ipv6Address> addrs = new LinkedHashMap<>(yrRecords);
        addrs.putAll(comRecords);
        addrs.put("ns.yr", hostAddresses.get(HOST_YR_DNS));
        addrs.put("root.yr", hostAddresses.get(HOST_ROOT_DNS));
        nameAddresses = Collections.unmodifiableMap(addrs);

        hostNames.clear();
        for (Map.Entry<String, String> e : names.entrySet()) {
            hostNames.computeIfAbsent(e.getValue(), k -> new ArrayList<>()).add(e.getKey());
        }
        leafCache = loadCa(server);

        // 运行时
        loop = new ExecutorEventLoop(THREAD_NAME);
        rejects = new RejectLog(NetworkConfig.rejectLogIntervalSeconds * 1000L);
        devices = new DeviceTable(book, new Bridge());
        router = new Router(loop, book, devices, routerAddress);
        nic = new NicGateway(loop, devices, router, rejects, NetworkConfig.devicePacketsPerSecond, NetworkConfig.deviceBytesPerSecond);
        testDevices = new TestDevices(devices, loop, resolverAddress);
        if (!NetworkConfig.playerAddresses) {
            LOG.info("游戏内网络：玩家临时地址在配置里关掉了（network.playerAddresses=false）");
        } else if (!Long.valueOf(NetworkLayout.PLAYERS_DATACENTER).equals(activeDc.get(YouranNetworkApi.PLAYERS_DATACENTER))) {
            LOG.warn("游戏内网络：玩家临时地址的 /96 没分到（序号 {} 被占了），玩家临时地址不开", Long.toHexString(NetworkLayout.PLAYERS_DATACENTER));
        } else {
            players = new PlayerNics(loop, layout, router, rejects, resolverAddress, new SecureRandom(),
                    NetworkConfig.playerPacketsPerSecond, NetworkConfig.playerBytesPerSecond,
                    NetworkConfig.playerInboundPacketsPerSecond, NetworkConfig.playerInboundBytesPerSecond);
            router.setPlayers(players);
            nic.setPlayers(players);
        }
        long serial = System.currentTimeMillis() / 1000;
        Map<String, NetworkRegistry.HostDecl> declByKey = new LinkedHashMap<>();
        for (NetworkRegistry.HostDecl h : hostDecls) {
            declByKey.putIfAbsent(h.key, h);
        }
        Map<String, Long> activeDcCopy = new LinkedHashMap<>(activeDc);
        // 验证码图片在服务端用 Java2D 画：专用服务器上没设时按无头模式（不连显示器；要在 AWT 初始化之前设）
        if (server.isDedicatedServer() && System.getProperty("java.awt.headless") == null) {
            System.setProperty("java.awt.headless", "true");
        }
        captcha = new CaptchaService(CaptchaStore.createDefault(), System::currentTimeMillis, CaptchaService.newDrawer());
        locationSigner = loadLocationKey(server);
        mainRejects = new RejectLog(NetworkConfig.rejectLogIntervalSeconds * 1000L);
        prompts = new ConfirmPrompts(new PromptEnv(), new SecureRandom());
        startCaptchaLocal(captcha);
        if (moe.qingu.youran.server.net.pay.PayConfig.enabled) {
            pay = moe.qingu.youran.server.net.pay.PayServer.start(server);
        } else {
            LOG.info("游戏内网络：pay.yr 在配置里关掉了（pay.enabled=false）");
        }
        if (moe.qingu.youran.server.net.nav.NavConfig.enabled) {
            nav = moe.qingu.youran.server.net.nav.NavServer.start(server);
        } else {
            LOG.info("游戏内网络：180.yr 在配置里关掉了（nav.enabled=false）");
        }
        loop.execute(() -> startHosts(hostAddresses, declByKey, tldDecls, yrRecords, comRecords, activeDcCopy, serial));
        loop.execute(this::scheduleOverflowLog);

        events = new NetworkEvents(devices, testDevices, this::playerLoggedIn, this::playerLoggedOut, this::serverTick);
        MinecraftForge.EVENT_BUS.register(events);
        event.registerServerCommand(new NetCommand(this));
        NetworkHooks.setBackend(this);
        LOG.info("游戏内网络已启动：前缀 {}/48，联网维度 {}，机房 {} 个，主机 {} 个；递归解析器 {}，NTP {}{}",
                layout.prefix(), dims, activeDc.size(), hostAddresses.size(), resolverAddress, ntpAddress,
                players == null ? "" : "；玩家临时地址 " + layout.playersPrefix() + "/96");
    }

    /** 验证码的本机端口（配置 {@code network.captchaLocal} 开关、{@code network.captchaLocalPort} 端口，默认 8094） */
    private void startCaptchaLocal(CaptchaService service) {
        int port = NetworkConfig.captchaLocalPort;
        if (!NetworkConfig.captchaLocal) {
            LOG.info("游戏内网络：验证码的本机端口没开（network.captchaLocal=false）");
            return;
        }
        CaptchaLocalServer s = new CaptchaLocalServer(service, System::currentTimeMillis);
        try {
            s.start(port);
            captchaLocal = s;
            LOG.info("游戏内网络：验证码的本机端口开在 127.0.0.1:{}（给游戏外的网页面板后台用）", port);
        } catch (java.io.IOException | RuntimeException e) {
            LOG.error("游戏内网络：验证码的本机端口 127.0.0.1:{} 开不了：{}", port, e.toString());
        }
    }

    /** 主线程：玩家上线，分临时地址 */
    private void playerLoggedIn(EntityPlayerMP player) {
        PlayerNics p = players;
        if (p == null || player.connection == null) {
            return;
        }
        net.minecraft.network.NetworkManager conn = player.connection.getNetworkManager();
        p.attach(player.getUniqueID(), player.getName(), new PlayerNics.Link() {
            @Override
            public void deliver(byte[] packet) {
                moe.qingu.youran.net.NicChannel.sendToClient(conn, moe.qingu.youran.net.NicMessages.playerPacket(packet));
            }

            @Override
            public void control(byte[] message) {
                moe.qingu.youran.net.NicChannel.sendToClient(conn, message);
            }
        });
    }

    /** 主线程，每 tick：系统确认框的超时、操作者变了 */
    private void serverTick() {
        ConfirmPrompts p = prompts;
        if (p != null) {
            p.tick();
        }
    }

    /** 读定位模块的签名密钥；第一次开服时生成。读不了时返回 null（不签名，位置证明不可用） */
    private static LocationSigner loadLocationKey(MinecraftServer server) {
        File f = server.getFile(LOCATION_KEY_FILE);
        try {
            LocationSigner.Loaded l = LocationSigner.loadOrCreate(f.toPath());
            if (l.created) {
                LOG.info("游戏内网络：第一次开服，生成了定位模块的签名密钥 {}（公钥编号 {}；私钥不要外传，不要放进仓库）", f, l.signer.keyId());
            } else {
                LOG.info("游戏内网络：定位模块的签名密钥公钥编号 {}", l.signer.keyId());
            }
            return l.signer;
        } catch (Exception e) {
            LOG.error("游戏内网络：读不了定位模块的签名密钥 {}，位置证明不可用（文件没有动；坏了的话移走它，下次开服会生成新的）：{}", f, e.toString());
            return null;
        }
    }

    private void playerLoggedOut(UUID player) {
        ConfirmPrompts cp = prompts;
        if (cp != null) {
            cp.playerLeft(player);
        }
        PlayerNics p = players;
        if (p != null) {
            p.detach(player);
        }
    }

    /** 读配置里的中间 CA；没配或读不了时返回 null（只有 HTTP） */
    private static LeafCache loadCa(MinecraftServer server) {
        String cert = NetworkConfig.tlsCaCertificate == null ? "" : NetworkConfig.tlsCaCertificate.trim();
        String key = NetworkConfig.tlsCaKey == null ? "" : NetworkConfig.tlsCaKey.trim();
        if (cert.isEmpty() || key.isEmpty()) {
            LOG.info("游戏内网络：没有配中间 CA（network.tlsCaCertificate / tlsCaKey），网站只开 HTTP，不开 HTTPS");
            return null;
        }
        File certFile = server.getFile(cert);
        File keyFile = server.getFile(key);
        try {
            IntermediateCa ca = IntermediateCa.load(certFile.toPath(), keyFile.toPath());
            long left = ca.certificate().getNotAfter().getTime() - System.currentTimeMillis();
            if (left <= 0) {
                LOG.error("游戏内网络：中间 CA {} 已经过期，网站只开 HTTP", ca.certificate().getSubjectX500Principal());
                return null;
            }
            if (left < 30L * 24 * 3600 * 1000) {
                LOG.warn("游戏内网络：中间 CA 不到 30 天就过期了（{}），请换新的", ca.certificate().getNotAfter());
            }
            LOG.info("游戏内网络：HTTPS 已开，中间 CA {}（有效期到 {}）", ca.certificate().getSubjectX500Principal(), ca.certificate().getNotAfter());
            return new LeafCache(ca, System::currentTimeMillis);
        } catch (Exception e) {
            LOG.error("游戏内网络：读不了中间 CA（{}、{}），网站只开 HTTP：{}", certFile, keyFile, e.toString());
            return null;
        }
    }

    /** 机房主机的 TLS；没配中间 CA、或主机没有域名时为 null */
    private TlsServerContext hostTls(String hostKey) {
        List<String> n = hostNames.get(hostKey);
        if (n == null || n.isEmpty()) {
            return null;
        }
        List<String> fixed = Collections.unmodifiableList(new ArrayList<>(n));
        return serverTls(() -> fixed);
    }

    @Override
    public TlsServerContext serverTls(java.util.function.Supplier<? extends Collection<String>> names) {
        LeafCache c = leafCache;
        if (c == null) {
            return null;
        }
        try {
            return new IssuingTlsContext(c, names);
        } catch (Exception e) {
            LOG.error("游戏内网络：TLS 初始化失败", e);
            return null;
        }
    }

    private static String keyOf(Map<String, Ipv6Address> hosts, Ipv6Address a) {
        for (Map.Entry<String, Ipv6Address> e : hosts.entrySet()) {
            if (e.getValue().equals(a)) {
                return e.getKey();
            }
        }
        return "?";
    }

    /** 网络线程上：给每台主机建协议栈、挂到路由器上、启动服务 */
    private void startHosts(Map<String, Ipv6Address> hosts, Map<String, NetworkRegistry.HostDecl> decls,
                            Map<String, NetworkRegistry.TldDecl> tldDecls, Map<String, Ipv6Address> yrRecords,
                            Map<String, Ipv6Address> comRecords, Map<String, Long> datacenters, long serial) {
        for (long idx : datacenters.values()) {
            router.attachDatacenter(idx);
        }
        for (Map.Entry<String, Ipv6Address> e : hosts.entrySet()) {
            NetStack stack = new NetStack(loop, p -> loop.execute(() -> router.forward(p)), new SecureRandom());
            stack.addAddress(e.getValue());
            stack.setMaxConnections(NetworkConfig.hostMaxTcpConnections);
            router.attachHost(e.getValue(), stack);
            hostStacks.put(e.getKey(), stack);
        }
        Ipv6Address root = hosts.get(HOST_ROOT_DNS);
        Ipv6Address yr = hosts.get(HOST_YR_DNS);
        // 根区：.yr、.com 委派给 YouranMod 的顶级域服务器，其他模组的顶级域委派给它们自己的主机
        Map<String, List<DnsZoneData.NameServer>> delegations = new LinkedHashMap<>();
        List<DnsZoneData.NameServer> yrNs = Collections.singletonList(new DnsZoneData.NameServer("ns.yr", yr));
        delegations.put("yr", yrNs);
        delegations.put("com", yrNs);
        Map<String, DnsZoneData> modZones = new LinkedHashMap<>();
        for (NetworkRegistry.TldDecl t : tldDecls.values()) {
            Ipv6Address a = hosts.get(t.hostKey);
            String nsName = "ns." + t.tld;
            delegations.put(t.tld, Collections.singletonList(new DnsZoneData.NameServer(nsName, a)));
            if (t.zone != null) {
                Map<String, List<Ipv6Address>> rec = new LinkedHashMap<>();
                rec.put(nsName, Collections.singletonList(a));
                modZones.put(t.hostKey + " " + t.tld, new DnsZoneData(t.tld,
                        Collections.singletonList(new DnsZoneData.NameServer(nsName, a)),
                        Collections.<String, List<DnsZoneData.NameServer>>emptyMap(), rec, t.zone, serial));
            }
        }
        DnsZoneData rootZone = new DnsZoneData("", Collections.singletonList(new DnsZoneData.NameServer("root.yr", root)),
                delegations, Collections.<String, List<Ipv6Address>>emptyMap(), null, serial);
        Map<String, List<Ipv6Address>> yrRec = new LinkedHashMap<>();
        yrRec.put("ns.yr", Collections.singletonList(yr));
        yrRec.put("root.yr", Collections.singletonList(root));
        for (Map.Entry<String, Ipv6Address> e : yrRecords.entrySet()) {
            yrRec.put(e.getKey(), Collections.singletonList(e.getValue()));
        }
        Map<String, List<Ipv6Address>> comRec = new LinkedHashMap<>();
        for (Map.Entry<String, Ipv6Address> e : comRecords.entrySet()) {
            comRec.put(e.getKey(), Collections.singletonList(e.getValue()));
        }
        DnsZoneData yrZone = new DnsZoneData("yr", yrNs, Collections.<String, List<DnsZoneData.NameServer>>emptyMap(), yrRec, null, serial);
        DnsZoneData comZone = new DnsZoneData("com", yrNs, Collections.<String, List<DnsZoneData.NameServer>>emptyMap(), comRec, null, serial);

        new AuthoritativeDnsServer(Collections.singletonList(rootZone)).start(hostStacks.get(HOST_ROOT_DNS));
        new AuthoritativeDnsServer(Arrays.asList(yrZone, comZone)).start(hostStacks.get(HOST_YR_DNS));
        resolver = new RecursiveResolver(hostStacks.get(HOST_RESOLVER), Collections.singletonList(root));
        resolver.start();
        new NtpServer(System::currentTimeMillis).start(hostStacks.get(HOST_NTP));
        TestWebsite.start(hostStacks.get(HOST_TEST), hostTls(HOST_TEST));
        CaptchaService cs = captcha;
        NetStack captchaStack = hostStacks.get(HOST_CAPTCHA);
        if (cs != null && captchaStack != null) {
            cs.start(captchaStack, hostTls(HOST_CAPTCHA));
        }
        LocationSigner signer = locationSigner;
        NetStack locationStack = hostStacks.get(HOST_LOCATION);
        if (signer != null && locationStack != null) {
            new LocationService(signer, System::currentTimeMillis).start(locationStack, hostTls(HOST_LOCATION));
        }
        moe.qingu.youran.server.net.pay.PayServer ps = pay;
        NetStack payStack = hostStacks.get(HOST_PAY);
        if (ps != null && payStack != null) {
            String idName = DomainNames.tryToAscii(moe.qingu.youran.server.net.pay.PayConfig.idDomain.trim().toLowerCase(java.util.Locale.ROOT));
            moe.qingu.youran.server.net.pay.PayServer.startWeb(ps, new Context(HOST_PAY, hosts.get(HOST_PAY), payStack), resolverAddress,
                    () -> idName != null && nameAddresses.containsKey(idName));
        }

        moe.qingu.youran.server.net.nav.NavServer ns = nav;
        NetStack navStack = hostStacks.get(moe.qingu.youran.server.net.nav.NavServer.HOST);
        if (ns != null && navStack != null) {
            String idName = DomainNames.tryToAscii(moe.qingu.youran.server.net.nav.NavConfig.idDomain.trim().toLowerCase(java.util.Locale.ROOT));
            ns.startWeb(new Context(moe.qingu.youran.server.net.nav.NavServer.HOST, hosts.get(moe.qingu.youran.server.net.nav.NavServer.HOST), navStack),
                    resolverAddress, () -> idName != null && nameAddresses.containsKey(idName), n -> nameAddresses.containsKey(n));
        }

        // 其他模组的顶级域：替它们跑权威 DNS
        Map<String, List<DnsZoneData>> zonesByHost = new LinkedHashMap<>();
        for (Map.Entry<String, DnsZoneData> e : modZones.entrySet()) {
            zonesByHost.computeIfAbsent(e.getKey().substring(0, e.getKey().indexOf(' ')), k -> new ArrayList<>()).add(e.getValue());
        }
        for (Map.Entry<String, List<DnsZoneData>> e : zonesByHost.entrySet()) {
            new AuthoritativeDnsServer(e.getValue()).start(hostStacks.get(e.getKey()));
        }
        // 其他模组的服务
        for (Map.Entry<String, NetworkRegistry.HostDecl> e : decls.entrySet()) {
            NetStack stack = hostStacks.get(e.getKey());
            if (stack == null || e.getValue().service == null) {
                continue;
            }
            try {
                e.getValue().service.start(new Context(e.getKey(), hosts.get(e.getKey()), stack));
            } catch (Throwable t) {
                LOG.error("游戏内网络：主机 {} 的服务启动失败", e.getKey(), t);
            }
        }
    }

    private void scheduleOverflowLog() {
        ExecutorEventLoop l = loop;
        if (l == null || l.isShutdown()) {
            return;
        }
        l.schedule(() -> {
            long n = overflowDrops.getAndSet(0);
            if (n > 0) {
                LOG.warn("游戏内网络：客户端发包太快、排队的包超过 {} 个，这段时间里在收包线程上直接丢掉了 {} 个", MAX_QUEUED_PER_PLAYER, n);
            }
            scheduleOverflowLog();
        }, 10_000);
    }

    @Override
    public void stop() {
        if (NetworkHooks.backend() == this) {
            NetworkHooks.setBackend(null);
        }
        if (pay != null) {
            pay.stop();
            pay = null;
        }
        if (nav != null) {
            nav.stop();
            nav = null;
        }
        if (events != null) {
            MinecraftForge.EVENT_BUS.unregister(events);
            events = null;
        }
        if (testDevices != null) {
            testDevices.clear();
        }
        if (devices != null) {
            devices.clear();
        }
        if (players != null) {
            players.clear();
        }
        if (captchaLocal != null) {
            captchaLocal.stop();
            captchaLocal = null;
        }
        if (captcha != null) {
            captcha.stop();
            captcha = null;
        }
        if (prompts != null) {
            prompts.clear();
            prompts = null;
        }
        locationSigner = null;
        for (CompletableFuture<?> f : mainThreadTasks) {
            f.completeExceptionally(new IllegalStateException("服务器正在关"));
        }
        mainThreadTasks.clear();
        ExecutorEventLoop l = loop;
        if (l != null) {
            l.execute(() -> {
                for (NetStack s : hostStacks.values()) {
                    s.close();
                }
            });
            boolean clean = l.shutdown(5000);
            if (clean) {
                LOG.info("游戏内网络已停，网络线程已结束");
            } else {
                LOG.warn("游戏内网络的网络线程没能在时限内停下");
            }
        }
        loop = null;
        hostStacks.clear();
        router = null;
        nic = null;
        players = null;
        nameAddresses = Collections.emptyMap();
        devices = null;
        testDevices = null;
        resolver = null;
        book = null;
        server = null;
        leafCache = null;
        hostNames.clear();
        queued.clear();
    }

    // ---- NetworkBackend ----

    @Override
    public NetworkDevices devices() {
        return devices;
    }

    @Override
    public Ipv6Address hostAddress(String key) {
        AddressBook b = book;
        return b == null ? null : b.hosts.get(key);
    }

    @Override
    public Ipv6Address resolverAddress() {
        return resolverAddress;
    }

    @Override
    public Ipv6Address ntpAddress() {
        return ntpAddress;
    }

    @Override
    public Ipv6Address playerAddress(UUID player) {
        PlayerNics p = players;
        return p == null ? null : p.address(player);
    }

    @Override
    public boolean allowPlayer(UUID player, String purpose, Collection<Ipv6Address> hosts, long ttlMillis) {
        PlayerNics p = players;
        return p != null && p.allow(player, purpose, hosts, ttlMillis);
    }

    @Override
    public void revokePlayer(UUID player, String purpose) {
        PlayerNics p = players;
        if (p != null) {
            p.revoke(player, purpose);
        }
    }

    @Override
    public DeviceLocation deviceLocation(Ipv6Address address) {
        AddressBook b = book;
        DeviceTable d = devices;
        return b == null || d == null ? null : d.locate(b, address);
    }

    // ---- 系统确认框 ----

    @Override
    public CompletableFuture<ConfirmResult> confirm(Ipv6Address source, UUID player, ConfirmRequest request) {
        MinecraftServer s = server;
        ConfirmPrompts p = prompts;
        if (s == null || p == null) {
            return CompletableFuture.completedFuture(new ConfirmResult(ConfirmResult.Outcome.UNAVAILABLE, null, null));
        }
        CompletableFuture<ConfirmResult> out = new CompletableFuture<>();
        s.addScheduledTask(() -> {
            if (prompts != p) {
                out.complete(new ConfirmResult(ConfirmResult.Outcome.UNAVAILABLE, null, null));
                return;
            }
            CompletableFuture<ConfirmResult> f = source != null ? confirmSource(p, source, request) : confirmPlayer(p, player, request);
            f.whenComplete((r, e) -> out.complete(r != null ? r : new ConfirmResult(ConfirmResult.Outcome.UNAVAILABLE, null, null)));
        });
        return out;
    }

    /** 主线程：问这个来源的当前操作者 */
    private CompletableFuture<ConfirmResult> confirmSource(ConfirmPrompts p, Ipv6Address source, ConfirmRequest request) {
        AddressBook b = book;
        DeviceTable dt = devices;
        DeviceTable.Device d = b == null || dt == null ? null : dt.deviceAt(b, source);
        if (d != null) {
            return confirmDevice(p, d, request);
        }
        PlayerNics pn = players;
        Map.Entry<UUID, String> owner = pn == null ? null : pn.owner(source);
        if (owner == null) {
            return CompletableFuture.completedFuture(new ConfirmResult(ConfirmResult.Outcome.NO_OPERATOR, null, null));
        }
        UUID id = owner.getKey();
        return p.open(id, owner.getValue(), () -> source.equals(pn.address(id)) ? id : null, request);
    }

    /** 主线程：问设备现在的操作者；设备拿掉、断电、换人时作废 */
    private CompletableFuture<ConfirmResult> confirmDevice(ConfirmPrompts p, DeviceTable.Device d, ConfirmRequest request) {
        DeviceTable.Operator op = d.operator();
        if (op == null || !d.powered) {
            return CompletableFuture.completedFuture(new ConfirmResult(ConfirmResult.Outcome.NO_OPERATOR, null, null));
        }
        return p.open(op.id, op.name, () -> d.removed || !d.powered ? null : d.operatorId(), request);
    }

    private CompletableFuture<ConfirmResult> confirmPlayer(ConfirmPrompts p, UUID player, ConfirmRequest request) {
        MinecraftServer s = server;
        EntityPlayerMP e = s == null ? null : s.getPlayerList().getPlayerByUUID(player);
        if (e == null) {
            return CompletableFuture.completedFuture(new ConfirmResult(ConfirmResult.Outcome.NO_OPERATOR, null, null));
        }
        return p.open(player, e.getName(), () -> player, request);
    }

    @Override
    public void onConfirmAnswer(EntityPlayerMP player, long id, boolean approve) {
        ConfirmPrompts p = prompts;
        if (p != null && !p.answer(player.getUniqueID(), id, approve)) {
            mainRejects.reject("玩家 " + player.getName(), "系统确认框的回应对不上（不是问他的、过时了或伪造的）", System.currentTimeMillis());
        }
    }

    /** 确认框要的东西（主线程） */
    private final class PromptEnv implements ConfirmPrompts.Env {
        @Override
        public long nowMillis() {
            return System.currentTimeMillis();
        }

        @Override
        public boolean isOnline(UUID player) {
            MinecraftServer s = server;
            return s != null && s.getPlayerList().getPlayerByUUID(player) != null;
        }

        @Override
        public void show(UUID player, long id, ConfirmRequest request, int secondsLeft) {
            EntityPlayerMP e = server == null ? null : server.getPlayerList().getPlayerByUUID(player);
            if (e != null) {
                YouranNetwork.CHANNEL.sendTo(new ConfirmMessages.Show(id, request, secondsLeft), e);
            }
        }

        @Override
        public void close(UUID player, long id) {
            EntityPlayerMP e = server == null ? null : server.getPlayerList().getPlayerByUUID(player);
            if (e != null) {
                YouranNetwork.CHANNEL.sendTo(new ConfirmMessages.Close(id), e);
            }
        }
    }

    ConfirmPrompts prompts() {
        return prompts;
    }

    // ---- 定位模块 ----

    @Override
    public java.security.PublicKey locationKey() {
        LocationSigner s = locationSigner;
        return s == null ? null : s.publicKey();
    }

    LocationSigner locationSigner() {
        return locationSigner;
    }

    @Override
    public CompletableFuture<LocationResult> signLocation(Ipv6Address device, String site, String nonce) {
        MinecraftServer s = server;
        if (s == null || prompts == null) {
            return CompletableFuture.completedFuture(LocationResult.of(LocationResult.Status.UNAVAILABLE));
        }
        CompletableFuture<LocationResult> out = new CompletableFuture<>();
        s.addScheduledTask(() -> {
            AddressBook b = book;
            DeviceTable dt = devices;
            DeviceTable.Device d = b == null || dt == null ? null : dt.deviceAt(b, device);
            if (d == null) {
                out.complete(LocationResult.of(LocationResult.Status.NO_OPERATOR));
                return;
            }
            signOnMain(d, site, nonce).whenComplete((r, e) -> out.complete(r != null ? r : LocationResult.of(LocationResult.Status.UNAVAILABLE)));
        });
        return out;
    }

    @Override
    public void onLocationRequest(EntityPlayerMP player, int requestId, String site, String nonce) {
        DeviceTable dt = devices;
        if (!locationLimit.tryAcquire(player.getUniqueID().toString(), System.currentTimeMillis())) {
            YouranNetwork.CHANNEL.sendTo(new LocationMessages.Reply(requestId, LocationResult.Status.BUSY.code, null), player);
            return;
        }
        // 他现在接着的那台设备（网卡接在他客户端上的）
        DeviceTable.Device d = dt == null ? null : dt.byPlayer(player.getUniqueID());
        CompletableFuture<LocationResult> f = d == null || d.removed
                ? CompletableFuture.completedFuture(LocationResult.of(LocationResult.Status.NO_OPERATOR))
                : signOnMain(d, site, nonce);
        f.whenComplete((r, e) -> {
            LocationResult res = r != null ? r : LocationResult.of(LocationResult.Status.UNAVAILABLE);
            if (player.connection != null) {
                YouranNetwork.CHANNEL.sendTo(new LocationMessages.Reply(requestId, res.status.code, res.proof), player);
            }
        });
    }

    /** 主线程：核对、问操作者、签名 */
    CompletableFuture<LocationResult> signOnMain(DeviceTable.Device d, String site, String nonce) {
        String s = site == null ? null : DomainNames.tryToAscii(site.trim().toLowerCase(java.util.Locale.ROOT));
        if (s == null || !LocationProof.SITE.matcher(s).matches() || nonce == null || !LocationProof.NONCE.matcher(nonce).matches()) {
            return CompletableFuture.completedFuture(LocationResult.of(LocationResult.Status.BAD_REQUEST));
        }
        LocationSigner signer = locationSigner;
        ConfirmPrompts p = prompts;
        if (signer == null || p == null) {
            return CompletableFuture.completedFuture(LocationResult.of(LocationResult.Status.UNAVAILABLE));
        }
        if (!d.hasCapability(DeviceCapability.LOCATION)) {
            return CompletableFuture.completedFuture(LocationResult.of(LocationResult.Status.NO_LOCATOR));
        }
        // 固定文字用翻译键，按操作者客户端的语言显示（服务端写好的中文是客户端没有这个键时的退路）
        ConfirmRequest req = new ConfirmRequest(
                moe.qingu.youran.api.network.ConfirmText.translate("youranmod.confirm.location.title", "获取这台电脑的位置"), s, Arrays.asList(
                moe.qingu.youran.api.network.ConfirmText.translate("youranmod.confirm.location.position",
                        "这台电脑的位置：维度 " + d.key.dimension + "，坐标 " + d.key.x + ", " + d.key.y + ", " + d.key.z,
                        d.key.dimension, d.key.x, d.key.y, d.key.z),
                moe.qingu.youran.api.network.ConfirmText.translate("youranmod.confirm.location.address", "这台电脑的游戏内地址")),
                ConfirmRequest.DEFAULT_TIMEOUT_SECONDS, ConfirmRequest.Purpose.SHARE);
        return confirmDevice(p, d, req).thenApply(r -> {
            switch (r.outcome) {
                case APPROVED:
                    if (d.removed || !d.hasCapability(DeviceCapability.LOCATION)) {
                        return LocationResult.of(d.removed ? LocationResult.Status.OPERATOR_CHANGED : LocationResult.Status.NO_LOCATOR);
                    }
                    try {
                        LocationProof proof = signer.sign(d.key.dimension, d.key.x, d.key.y, d.key.z, d.address,
                                System.currentTimeMillis() / 1000, s, nonce);
                        LOG.info("游戏内网络：{} 同意把 {} 的位置给 {}", r.playerName, d.label(), s);
                        return new LocationResult(LocationResult.Status.OK, proof.text());
                    } catch (Exception e) {
                        LOG.error("游戏内网络：位置签名失败", e);
                        return LocationResult.of(LocationResult.Status.UNAVAILABLE);
                    }
                case DENIED:
                    return LocationResult.of(LocationResult.Status.DENIED);
                case TIMEOUT:
                    return LocationResult.of(LocationResult.Status.TIMEOUT);
                case OPERATOR_CHANGED:
                    return LocationResult.of(LocationResult.Status.OPERATOR_CHANGED);
                case BUSY:
                    return LocationResult.of(LocationResult.Status.BUSY);
                case NO_OPERATOR:
                    return LocationResult.of(LocationResult.Status.NO_OPERATOR);
                default:
                    return LocationResult.of(LocationResult.Status.UNAVAILABLE);
            }
        });
    }

    @Override
    public SourceIdentity identify(Ipv6Address address) {
        AddressBook b = book;
        DeviceTable d = devices;
        if (b == null || d == null || address == null) {
            return null;
        }
        SourceIdentity id = d.identify(b, address);
        if (id != null) {
            return id;
        }
        PlayerNics p = players;
        Map.Entry<UUID, String> owner = p == null ? null : p.owner(address);
        return owner == null ? null : new SourceIdentity(SourceIdentity.Kind.PLAYER, address, null, null, owner.getKey(), owner.getValue());
    }

    @Override
    public Ipv6Address localNameAddress(String asciiName) {
        return asciiName == null ? null : nameAddresses.get(asciiName);
    }

    @Override
    public void onClientPacket(EntityPlayerMP player, byte[] packet, int messageLength, boolean playerNic) {
        ExecutorEventLoop l = loop;
        NicGateway g = nic;
        if (l == null || g == null) {
            return;
        }
        UUID id = player.getUniqueID();
        String name = player.getName();
        AtomicInteger q = queued.computeIfAbsent(id, k -> new AtomicInteger());
        if (q.incrementAndGet() > MAX_QUEUED_PER_PLAYER) {
            q.decrementAndGet();
            overflowDrops.incrementAndGet();
            return;
        }
        l.execute(() -> {
            q.decrementAndGet();
            if (playerNic) {
                g.fromPlayerNic(id, name, packet);
            } else {
                g.fromPlayer(id, name, packet, messageLength);
            }
        });
    }

    // ---- 给命令与设备表用 ----

    ExecutorEventLoop loop() {
        return loop;
    }

    MinecraftServer server() {
        return server;
    }

    AllocationTable table() {
        return table;
    }

    AddressBook book() {
        return book;
    }

    Router router() {
        return router;
    }

    TestDevices testDevices() {
        return testDevices;
    }

    DeviceTable deviceTable() {
        return devices;
    }

    RecursiveResolver resolver() {
        return resolver;
    }

    RejectLog rejects() {
        return rejects;
    }

    NicGateway nic() {
        return nic;
    }

    PlayerNics players() {
        return players;
    }

    NetStack hostStack(String key) {
        return hostStacks.get(key);
    }

    List<String> tlds() {
        return tlds;
    }

    Map<String, String> names() {
        return names;
    }

    boolean running() {
        return loop != null;
    }

    /** 设备表要的东西 */
    final class Bridge implements DeviceTable.Hooks {
        @Override
        public boolean isLoaded(int dimension, int x, int y, int z) {
            return DeviceTable.isLoadedInWorld(dimension, x, y, z);
        }

        @Override
        public Ipv6Address resolverAddress() {
            return resolverAddress;
        }

        @Override
        public Ipv6Address ntpAddress() {
            return ntpAddress;
        }

        @Override
        public EntityPlayerMP player(UUID id) {
            MinecraftServer s = server;
            return s == null ? null : s.getPlayerList().getPlayerByUUID(id);
        }

        @Override
        public void fromDevice(DeviceTable.Device d, byte[] packet) {
            ExecutorEventLoop l = loop;
            NicGateway g = nic;
            if (l == null || g == null) {
                return;
            }
            // 排进队列，不在调用方（可能就是网络线程上的端点）里递归；超长的包不复制，反正会被拒绝
            byte[] copy = packet.length <= Ipv6Packet.MAX_PACKET ? packet.clone() : packet;
            l.execute(() -> g.fromDevice(d, copy));
        }
    }

    /** 机房主机的运行环境 */
    private final class Context implements HostContext {
        private final String key;
        private final Ipv6Address address;
        private final NetStack stack;

        Context(String key, Ipv6Address address, NetStack stack) {
            this.key = key;
            this.address = address;
            this.stack = stack;
        }

        @Override
        public String key() {
            return key;
        }

        @Override
        public Ipv6Address address() {
            return address;
        }

        @Override
        public NetStack stack() {
            return stack;
        }

        private TlsServerContext tls;
        private boolean tlsChecked;

        @Override
        public synchronized TlsServerContext tls() {
            if (!tlsChecked) {
                tlsChecked = true;
                tls = hostTls(key);
            }
            return tls;
        }

        @Override
        public <T> CompletableFuture<T> onMainThread(Supplier<T> task) {
            CompletableFuture<T> f = new CompletableFuture<>();
            MinecraftServer s = server;
            ExecutorEventLoop l = loop;
            if (s == null || l == null) {
                f.completeExceptionally(new IllegalStateException("服务器正在关"));
                return f;
            }
            mainThreadTasks.add(f);
            f.whenComplete((v, e) -> mainThreadTasks.remove(f));
            s.addScheduledTask(() -> {
                if (f.isDone()) {
                    return;
                }
                try {
                    T v = task.get();
                    l.execute(() -> f.complete(v));
                } catch (Throwable t) {
                    l.execute(() -> f.completeExceptionally(t));
                }
            });
            return f;
        }
    }
}
