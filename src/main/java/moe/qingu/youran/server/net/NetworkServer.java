package moe.qingu.youran.server.net;

import moe.qingu.youran.api.network.ConfirmRequest;
import moe.qingu.youran.api.network.ConfirmResult;
import moe.qingu.youran.api.network.DefaultDimensionNetwork;
import moe.qingu.youran.api.network.DomainNames;
import com.mojang.authlib.GameProfile;
import moe.qingu.youran.api.network.ClientRelayNic;
import moe.qingu.youran.api.network.NetworkInterface;
import moe.qingu.youran.api.network.identity.OperatorResult;
import moe.qingu.youran.api.network.location.LocationResult;
import moe.qingu.youran.network.ClientRelay;
import moe.qingu.youran.network.NicPort;
import moe.qingu.youran.server.net.identity.OperatorService;
import moe.qingu.youran.server.net.identity.OperatorSigner;
import moe.qingu.youran.net.ConfirmMessages;
import moe.qingu.youran.net.YouranNetwork;
import moe.qingu.youran.server.net.location.LocationService;
import moe.qingu.youran.server.net.location.LocationSigner;
import moe.qingu.youran.api.network.DimensionNetwork;
import moe.qingu.youran.api.network.HostContext;
import moe.qingu.youran.api.network.Ipv6Address;
import moe.qingu.youran.api.network.NetworkLayout;
import moe.qingu.youran.api.network.YouranNetworkApi;
import moe.qingu.youran.api.network.packet.Ipv6Packet;
import moe.qingu.youran.api.network.stack.ExecutorEventLoop;
import moe.qingu.youran.api.network.stack.NetStack;
import moe.qingu.youran.network.NetworkBackend;
import moe.qingu.youran.network.NetworkHooks;
import moe.qingu.youran.network.NetworkRegistry;
import moe.qingu.youran.network.ServerNetworkModule;
import moe.qingu.youran.api.network.dns.DefaultRegistrar;
import moe.qingu.youran.api.network.dns.DnsRegistry;
import moe.qingu.youran.api.network.dns.DnsServer;
import moe.qingu.youran.api.network.dns.Registration;
import moe.qingu.youran.network.DnsState;
import moe.qingu.youran.server.net.service.NtpServer;
import moe.qingu.youran.server.net.service.RecursiveResolver;
import moe.qingu.youran.server.net.service.TestWebsite;
import moe.qingu.youran.server.net.service.captcha.CaptchaLocalServer;
import moe.qingu.youran.server.net.service.captcha.CaptchaService;
import moe.qingu.youran.server.net.service.captcha.CaptchaStore;
import moe.qingu.youran.server.net.tls.IntermediateCa;
import moe.qingu.youran.server.net.ca.CaHttp;
import moe.qingu.youran.server.net.ca.CaService;
import moe.qingu.youran.server.net.ca.NetworkValidator;
import moe.qingu.youran.api.network.ca.CertificateAuthority;
import moe.qingu.youran.api.network.ca.CertificateClient;
import moe.qingu.youran.api.network.tls.CertificateTlsContext;
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
    /** YouranMod 自己网站（test.yr、pay.yr、180.yr……）的 DNS：这些二级域名以模组身份注册在 .yr 下，委派给它 */
    static final String HOST_SITES_DNS = "youranmod:dns-sites";
    /** YouranMod 注册域名用的模组身份 */
    static final String MOD_ID = "youranmod";
    static final String HOST_TEST = "youranmod:test";
    /** 人机验证（captcha.yr，服主 11-15） */
    static final String HOST_CAPTCHA = "youranmod:captcha";
    static final String CAPTCHA_NAME = "captcha.yr";
    /** 定位模块的公钥与核对（location.yr，服主 11-15） */
    static final String HOST_LOCATION = "youranmod:location";
    static final String LOCATION_NAME = moe.qingu.youran.api.network.location.LocationProofs.DOMAIN;
    /** 定位模块的签名密钥文件（相对服务器根目录） */
    static final String LOCATION_KEY_FILE = "config/youranmod-location-key.pem";
    /** 身份模块（操作者证明）的公钥与核对（operator.yr，服主 11-27） */
    static final String HOST_OPERATOR = "youranmod:operator";
    static final String OPERATOR_NAME = moe.qingu.youran.api.network.identity.OperatorProofs.DOMAIN;
    /** 身份模块的签名密钥文件（相对服务器根目录；与定位模块的分开） */
    static final String OPERATOR_KEY_FILE = "config/youranmod-operator-key.pem";
    /** 聚合付款服务（pay.yr，服主 11-15） */
    static final String HOST_PAY = "youranmod:pay";
    static final String PAY_NAME = moe.qingu.youran.api.pay.PayApi.DOMAIN;
    /** 证书签发服务（ca.yr，服主 11-17） */
    static final String HOST_CA = "youranmod:ca";
    static final String CA_NAME = CertificateAuthority.DOMAIN;
    /** 测试网站的国际化域名别名 */
    static final String TEST_IDN_NAME = "测试.yr";
    static final long PART_ROUTER = 0x1;
    static final long PART_RESOLVER = 0x53;
    static final long PART_NTP = 0x123;
    static final long PART_ROOT_DNS = 0x1_0053L;
    static final long PART_YR_DNS = 0x2_0053L;
    static final long PART_SITES_DNS = 0x3_0053L;
    /** 一个玩家在 Netty 线程上排进网络线程、还没处理的包最多这么多；再多的直接丢掉（防止一个客户端塞满队列） */
    static final int MAX_QUEUED_PER_PLAYER = 256;

    private MinecraftServer server;
    private ExecutorEventLoop loop;
    private AllocationTable table;
    private AddressBook book;
    /** 插着的网卡 */
    private NicTable nics;
    private Router router;
    /** 网卡与玩家客户端之间的中转（ClientRelayNic） */
    private ClientRelays relays;
    private TestNics testNics;
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
    /** 定位模块、身份模块的签名（主线程） */
    private ProofDesk proofs;
    /** pay.yr 的账本与给模组的接口；没开（配置关掉、读不了账本）为 null */
    private moe.qingu.youran.server.net.pay.PayServer pay;
    /** 浏览器主页 180.yr、广告平台、帮助（服主 11-15）；没开为 null */
    private moe.qingu.youran.server.net.nav.NavServer nav;
    /** .yr、.com 的注册局，YouranMod 自己网站的 DNS（网络线程上建好；没建起来为 null） */
    private volatile DefaultRegistrar yrRegistrar;
    private volatile DefaultRegistrar comRegistrar;
    private volatile DnsServer sitesDns;
    private File worldDirectory;
    private final Map<String, NetStack> hostStacks = new LinkedHashMap<>();
    /** 原始主机（登记名 → 端口）；开服时在网络线程上填，任意线程读 */
    private final Map<String, RawHostPort> rawHosts = new ConcurrentHashMap<>();
    private final Set<CompletableFuture<?>> mainThreadTasks = ConcurrentHashMap.newKeySet();
    private final Map<UUID, AtomicInteger> queued = new ConcurrentHashMap<>();
    private final AtomicLong overflowDrops = new AtomicLong();
    /** 证书签发服务；没配中间 CA 时也有（申请都失败，说明原因） */
    private volatile CaService ca;
    /** YouranMod 自己网站的证书申请（关服时停） */
    private final List<CertificateClient> certClients = new java.util.concurrent.CopyOnWriteArrayList<>();
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
        this.worldDirectory = server.getWorld(0).getSaveHandler().getWorldDirectory();
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
        hostRequests.add(new AllocationTable.Request(HOST_SITES_DNS, PART_SITES_DNS, dc));
        hostRequests.add(new AllocationTable.Request(HOST_TEST, -1, dc));
        hostRequests.add(new AllocationTable.Request(HOST_CAPTCHA, -1, dc));
        hostRequests.add(new AllocationTable.Request(HOST_LOCATION, -1, dc));
        hostRequests.add(new AllocationTable.Request(HOST_OPERATOR, -1, dc));
        hostRequests.add(new AllocationTable.Request(HOST_CA, -1, dc));
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

        String[] caProblem = new String[1];
        IntermediateCa intermediate = loadCa(server, caProblem);

        // 运行时
        loop = new ExecutorEventLoop(THREAD_NAME);
        ca = new CaService(intermediate, caProblem[0], loop, System::currentTimeMillis, new SecureRandom());
        rejects = new RejectLog(NetworkConfig.rejectLogIntervalSeconds * 1000L);
        AddressBook addressBook = book;
        nics = new NicTable(addressBook::coordinateAddress, loop, rejects, p -> router.forward(p),
                NetworkConfig.devicePacketsPerSecond, NetworkConfig.deviceBytesPerSecond, this::unplugged);
        router = new Router(loop, book, nics, routerAddress);
        relays = new ClientRelays(loop, rejects, () -> resolverAddress, () -> ntpAddress);
        testNics = new TestNics(loop, resolverAddress);
        if (!NetworkConfig.playerAddresses) {
            LOG.info("游戏内网络：玩家临时地址在配置里关掉了（network.playerAddresses=false）");
        } else if (!Long.valueOf(NetworkLayout.PLAYERS_DATACENTER).equals(activeDc.get(YouranNetworkApi.PLAYERS_DATACENTER))) {
            LOG.warn("游戏内网络：玩家临时地址的 /96 没分到（序号 {} 被占了），玩家临时地址不开", Long.toHexString(NetworkLayout.PLAYERS_DATACENTER));
        } else {
            players = new PlayerNics(loop, layout, router, rejects, resolverAddress, new SecureRandom(),
                    NetworkConfig.playerPacketsPerSecond, NetworkConfig.playerBytesPerSecond,
                    NetworkConfig.playerInboundPacketsPerSecond, NetworkConfig.playerInboundBytesPerSecond);
            router.setPlayers(players);
        }
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
        mainRejects = new RejectLog(NetworkConfig.rejectLogIntervalSeconds * 1000L);
        prompts = new ConfirmPrompts(new PromptEnv(), new SecureRandom());
        proofs = new ProofDesk(new ProofEnv());
        proofs.locationSigner = loadLocationKey(server);
        proofs.operatorSigner = loadOperatorKey(server);
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
        // 先挂上：主机的启动回调里就能用 YouranNetworkApi.hostAddress 等查别的主机
        NetworkHooks.setBackend(this);
        loop.execute(() -> startHosts(hostAddresses, declByKey, activeDcCopy));
        loop.execute(this::scheduleOverflowLog);

        events = new NetworkEvents(this::playerLoggedIn, this::playerLoggedOut, this::serverTick);
        MinecraftForge.EVENT_BUS.register(events);
        event.registerServerCommand(new NetCommand(this));
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
                moe.qingu.youran.net.NicChannel.sendToClient(conn, moe.qingu.youran.net.NicMessages.packet(moe.qingu.youran.net.NicMessages.PLAYER_PORT, packet));
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

    /** 读身份模块的签名密钥；第一次开服时生成。读不了时返回 null（不签名，一键登录不可用） */
    private static OperatorSigner loadOperatorKey(MinecraftServer server) {
        File f = server.getFile(OPERATOR_KEY_FILE);
        try {
            OperatorSigner.Loaded l = OperatorSigner.loadOrCreate(f.toPath());
            if (l.created) {
                LOG.info("游戏内网络：第一次开服，生成了身份模块的签名密钥 {}（公钥编号 {}；私钥不要外传，不要放进仓库）", f, l.signer.keyId());
            } else {
                LOG.info("游戏内网络：身份模块的签名密钥公钥编号 {}", l.signer.keyId());
            }
            return l.signer;
        } catch (Exception e) {
            LOG.error("游戏内网络：读不了身份模块的签名密钥 {}，操作者证明不可用（文件没有动；坏了的话移走它，下次开服会生成新的）：{}", f, e.toString());
            return null;
        }
    }

    private void playerLoggedOut(UUID player) {
        ConfirmPrompts cp = prompts;
        if (cp != null) {
            cp.playerLeft(player);
        }
        TestNics tn = testNics;
        if (tn != null) {
            tn.onPlayerLeft(player);
        }
        ClientRelays r = relays;
        if (r != null) {
            r.playerLeft(player);
        }
        PlayerNics p = players;
        if (p != null) {
            p.detach(player);
        }
    }

    /** 读配置里的中间 CA；没配、读不了或已过期时返回 null，原因写进 {@code problem[0]}（证书服务不开，网站只有 HTTP） */
    private static IntermediateCa loadCa(MinecraftServer server, String[] problem) {
        String cert = NetworkConfig.tlsCaCertificate == null ? "" : NetworkConfig.tlsCaCertificate.trim();
        String key = NetworkConfig.tlsCaKey == null ? "" : NetworkConfig.tlsCaKey.trim();
        if (cert.isEmpty() || key.isEmpty()) {
            problem[0] = "服务器没配中间 CA（network.tlsCaCertificate / tlsCaKey）";
            LOG.info("游戏内网络：没有配中间 CA（network.tlsCaCertificate / tlsCaKey），证书签发服务不开，网站只有 HTTP");
            return null;
        }
        File certFile = server.getFile(cert);
        File keyFile = server.getFile(key);
        try {
            IntermediateCa ca = IntermediateCa.load(certFile.toPath(), keyFile.toPath());
            long left = ca.certificate().getNotAfter().getTime() - System.currentTimeMillis();
            if (left <= 0) {
                problem[0] = "中间 CA 已经过期";
                LOG.error("游戏内网络：中间 CA {} 已经过期，证书签发服务不开，网站只有 HTTP", ca.certificate().getSubjectX500Principal());
                return null;
            }
            if (left < 30L * 24 * 3600 * 1000) {
                LOG.warn("游戏内网络：中间 CA 不到 30 天就过期了（{}），请换新的", ca.certificate().getNotAfter());
            }
            LOG.info("游戏内网络：证书签发服务（ca.yr）已开，中间 CA {}（有效期到 {}）", ca.certificate().getSubjectX500Principal(), ca.certificate().getNotAfter());
            return ca;
        } catch (Exception e) {
            problem[0] = "服务器的中间 CA 读不了";
            LOG.error("游戏内网络：读不了中间 CA（{}、{}），证书签发服务不开，网站只有 HTTP：{}", certFile, keyFile, e.toString());
            return null;
        }
    }

    /**
     * 网络线程上：YouranMod 自己的网站向证书服务申请证书（HTTP 认证，网站的 80 口自动回），到期前续。
     * 证书与私钥存在世界存档的 {@code youranmod/certs/<网站>/}（见 {@link #siteCertificates}），开服时还能用就直接用。
     * 返回给 443 口用的 TLS；证书服务没开时为 null（只开 HTTP）
     */
    TlsServerContext siteTls(String hostKey, NetStack stack, Collection<String> names) {
        CertificateClient c = CertificateClient.start(stack, names, null, ca, System::currentTimeMillis, siteCertificates(hostKey));
        certClients.add(c);
        return c.tls();
    }

    /**
     * YouranMod 自己的网站（主机 {@code youranmod:<网站>}）的证书存在哪：世界存档的 {@code youranmod/certs/<网站>/}
     * （如 {@code youranmod:pay} → {@code youranmod/certs/pay/}）。网络没在运行时为 null（不存）
     */
    public static moe.qingu.youran.api.network.ca.CertificateStore siteCertificates(String hostKey) {
        java.nio.file.Path world = moe.qingu.youran.api.network.YouranNetworkApi.worldDirectory();
        if (world == null) {
            return null;
        }
        String site = hostKey.startsWith(MOD_ID + ":") ? hostKey.substring(MOD_ID.length() + 1) : hostKey;
        return moe.qingu.youran.api.network.ca.CertificateStore.directory(world.resolve(MOD_ID).resolve("certs").resolve(site));
    }

    /** 网络线程上：证书签发服务 ca.yr（80 与 443 口；自己的证书直接签给自己） */
    private void startCa(Map<String, Ipv6Address> hosts) {
        CaService service = ca;
        NetStack stack = hostStacks.get(HOST_CA);
        if (service == null || stack == null) {
            LOG.error("游戏内网络：证书签发服务的主机 {} 没分到地址，ca.yr 不开", HOST_CA);
            return;
        }
        service.setValidator(new NetworkValidator(stack, resolverAddress));
        CertificateTlsContext tls = null;
        if (service.unavailableReason() == null) {
            tls = new CertificateTlsContext();
            service.selfCertificate(Collections.singletonList(CA_NAME), tls);
        }
        new CaHttp(service, System::currentTimeMillis).listen(stack, tls);
        LOG.info("游戏内网络：证书签发服务 ca.yr 开在 {}（{}）", hosts.get(HOST_CA),
                tls == null ? "HTTP；没开：" + service.unavailableReason() : "HTTP 与 HTTPS；能签的名字：" + service.constraintsText());
    }

    /** 网络线程上：给每台主机建协议栈、挂到路由器上、启动服务 */
    private void startHosts(Map<String, Ipv6Address> hosts, Map<String, NetworkRegistry.HostDecl> decls, Map<String, Long> datacenters) {
        for (long idx : datacenters.values()) {
            router.attachDatacenter(idx);
        }
        for (Map.Entry<String, Ipv6Address> e : hosts.entrySet()) {
            NetworkRegistry.HostDecl decl = decls.get(e.getKey());
            if (decl != null && decl.isRaw()) {
                RawHostPort port = new RawHostPort(e.getKey(), e.getValue(), loop, router, rejects);
                router.attachHost(e.getValue(), port);
                rawHosts.put(e.getKey(), port);
                continue;
            }
            NetStack stack = new NetStack(loop, p -> loop.execute(() -> router.forward(p)), new SecureRandom());
            stack.addAddress(e.getValue());
            stack.setMaxConnections(NetworkConfig.hostMaxTcpConnections);
            router.attachHost(e.getValue(), stack);
            hostStacks.put(e.getKey(), stack);
        }
        startDns(hosts);
        resolver = new RecursiveResolver(hostStacks.get(HOST_RESOLVER), DnsState.rootServers());
        resolver.start();
        new NtpServer(System::currentTimeMillis).start(hostStacks.get(HOST_NTP));
        startCa(hosts);
        NetStack testStack = hostStacks.get(HOST_TEST);
        TestWebsite.start(testStack, siteTls(HOST_TEST, testStack, TEST_NAMES));
        CaptchaService cs = captcha;
        NetStack captchaStack = hostStacks.get(HOST_CAPTCHA);
        if (cs != null && captchaStack != null) {
            cs.start(captchaStack, siteTls(HOST_CAPTCHA, captchaStack, Collections.singletonList(CAPTCHA_NAME)));
        }
        LocationSigner signer = proofs == null ? null : proofs.locationSigner;
        NetStack locationStack = hostStacks.get(HOST_LOCATION);
        if (signer != null && locationStack != null) {
            new LocationService(signer, System::currentTimeMillis).start(locationStack, siteTls(HOST_LOCATION, locationStack, Collections.singletonList(LOCATION_NAME)));
        }
        OperatorSigner opSigner = proofs == null ? null : proofs.operatorSigner;
        NetStack operatorStack = hostStacks.get(HOST_OPERATOR);
        if (opSigner != null && operatorStack != null) {
            new OperatorService(opSigner, System::currentTimeMillis).start(operatorStack, siteTls(HOST_OPERATOR, operatorStack, Collections.singletonList(OPERATOR_NAME)));
        }
        moe.qingu.youran.server.net.pay.PayServer ps = pay;
        NetStack payStack = hostStacks.get(HOST_PAY);
        if (ps != null && payStack != null) {
            moe.qingu.youran.server.net.pay.PayServer.startWeb(ps, new Context(HOST_PAY, hosts.get(HOST_PAY), payStack), resolverAddress);
        }

        moe.qingu.youran.server.net.nav.NavServer ns = nav;
        NetStack navStack = hostStacks.get(moe.qingu.youran.server.net.nav.NavServer.HOST);
        if (ns != null && navStack != null) {
            ns.startWeb(new Context(moe.qingu.youran.server.net.nav.NavServer.HOST, hosts.get(moe.qingu.youran.server.net.nav.NavServer.HOST), navStack),
                    resolverAddress);
        }

        // 其他模组的服务
        for (Map.Entry<String, NetworkRegistry.HostDecl> e : decls.entrySet()) {
            RawHostPort raw = rawHosts.get(e.getKey());
            if (raw != null) {
                try {
                    e.getValue().rawService.start(raw);
                    LOG.info("游戏内网络：原始主机 {} 在 {}", e.getKey(), raw.address());
                } catch (Throwable t) {
                    LOG.error("游戏内网络：原始主机 {} 的回调出错", e.getKey(), t);
                }
                continue;
            }
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

    /** 测试网站的名字（还有国际化域名的别名；DNS 与证书里是 A-label） */
    static final List<String> TEST_NAMES = Collections.unmodifiableList(Arrays.asList("test.yr", DomainNames.toAscii(TEST_IDN_NAME)));

    /**
     * 网络线程上：根、.yr / .com、YouranMod 自己网站的 DNS。根区跟着顶级域登记处；.yr 用默认注册局，.com 只收以模组身份注册的；
     * YouranMod 的网站以模组身份注册成 .yr 下的二级域名，委派给 {@link #HOST_SITES_DNS}。都要在其他模组的主机启动之前做好。
     */
    private void startDns(Map<String, Ipv6Address> hosts) {
        NetStack rootStack = hostStacks.get(HOST_ROOT_DNS);
        NetStack tldStack = hostStacks.get(HOST_YR_DNS);
        NetStack sitesStack = hostStacks.get(HOST_SITES_DNS);
        if (rootStack == null || tldStack == null || sitesStack == null) {
            LOG.error("游戏内网络：DNS 服务器没分到地址（{}、{}、{}），域名都用不了", HOST_ROOT_DNS, HOST_YR_DNS, HOST_SITES_DNS);
            return;
        }
        DnsState.bindRoot(DnsServer.start(rootStack));
        DnsServer tld = DnsServer.start(tldStack);
        File dir = new File(new File(worldDirectory, "youranmod"), "dns");
        List<Ipv6Address> tldServers = Collections.singletonList(tld.address());
        DefaultRegistrar yr = new DefaultRegistrar(MOD_ID, "yr", tld, new File(dir, "yr.json"));
        // .com：现实里存在的域名，只收以模组身份注册的（服主 11-17）
        DefaultRegistrar com = new ComRegistrar(MOD_ID, tld, new File(dir, "com.json"));
        logRegistration(".yr", DnsRegistry.register("yr", yr, tldServers));
        logRegistration(".com", DnsRegistry.register("com", com, tldServers));
        yrRegistrar = yr;
        comRegistrar = com;

        DnsServer sites = DnsServer.start(sitesStack);
        sitesDns = sites;
        Map<String, Ipv6Address> records = new LinkedHashMap<>();
        for (String n : TEST_NAMES) {
            records.put(n, hosts.get(HOST_TEST));
        }
        records.put(CAPTCHA_NAME, hosts.get(HOST_CAPTCHA));
        records.put(LOCATION_NAME, hosts.get(HOST_LOCATION));
        records.put(OPERATOR_NAME, hosts.get(HOST_OPERATOR));
        records.put(CA_NAME, hosts.get(HOST_CA));
        if (pay != null) {
            records.put(PAY_NAME, hosts.get(HOST_PAY));
            // pay.yr 的开发者文档站（服主 11-15：长说明挪到单独的站），同一台主机，按 Host 分
            records.put(moe.qingu.youran.api.pay.PayApi.DOCS_DOMAIN, hosts.get(HOST_PAY));
        }
        if (nav != null) {
            for (String n : moe.qingu.youran.server.net.nav.NavServer.NAMES) {
                records.put(n, hosts.get(moe.qingu.youran.server.net.nav.NavServer.HOST));
            }
        }
        for (Map.Entry<String, Ipv6Address> e : records.entrySet()) {
            if (e.getValue() == null) {
                continue;
            }
            String zone = secondLevel(e.getKey());
            if (!sites.zones().contains(zone)) {
                sites.addZone(zone);
                logRegistration(zone, yr.register(zone, MOD_ID, Collections.singletonList(sites.address())));
            }
            sites.setAddresses(e.getKey(), Collections.singletonList(e.getValue()));
        }
    }

    /** {@code a.b.yr} 所在的二级域名 {@code b.yr} */
    static String secondLevel(String name) {
        int last = name.lastIndexOf('.');
        int prev = last <= 0 ? -1 : name.lastIndexOf('.', last - 1);
        return name.substring(prev + 1);
    }

    static void logRegistration(String what, Registration r) {
        switch (r.status()) {
            case SUCCESS:
                LOG.debug("游戏内网络：{} 注册好了：{}", what, r);
                break;
            case FAILURE:
                LOG.warn("游戏内网络：{} 注册失败：{}", what, r.reason());
                break;
            default:
                LOG.info("游戏内网络：{} 注册在等注册局处理", what);
                r.whenDone(x -> LOG.info("游戏内网络：{} 注册有结果了：{}", what, x));
        }
    }

    DefaultRegistrar yrRegistrar() {
        return yrRegistrar;
    }

    DefaultRegistrar comRegistrar() {
        return comRegistrar;
    }

    DnsServer sitesDns() {
        return sitesDns;
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
        if (testNics != null) {
            testNics.clear();
        }
        if (relays != null) {
            relays.clear();
        }
        if (nics != null) {
            nics.clear();
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
        proofs = null;
        for (RawHostPort r : rawHosts.values()) {
            r.close();
        }
        rawHosts.clear();
        for (CompletableFuture<?> f : mainThreadTasks) {
            f.completeExceptionally(new IllegalStateException("服务器正在关"));
        }
        mainThreadTasks.clear();
        for (CertificateClient c : certClients) {
            c.stop();
        }
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
        certClients.clear();
        ca = null;
        DnsState.reset();
        yrRegistrar = null;
        comRegistrar = null;
        sitesDns = null;
        hostStacks.clear();
        router = null;
        relays = null;
        players = null;
        nics = null;
        testNics = null;
        resolver = null;
        book = null;
        server = null;
        worldDirectory = null;
        queued.clear();
    }

    // ---- NetworkBackend ----

    @Override
    public NicPort plug(NetworkInterface nic, int dimension, int x, int y, int z) {
        NicTable t = nics;
        return t == null ? null : t.plug(nic, dimension, x, y, z);
    }

    @Override
    public void unplug(NicPort port) {
        NicTable t = nics;
        if (t != null) {
            t.unplug(port);
        }
    }

    /** 一张网卡拔掉了（任意线程）：接着玩家客户端的断开 */
    private void unplugged(NicTable.Port port) {
        if (port.nic instanceof ClientRelayNic) {
            ((ClientRelayNic) port.nic).disconnect();
        }
    }

    @Override
    public Ipv6Address addressAt(int dimension, int x, int y, int z) {
        AddressBook b = book;
        return b == null ? null : b.coordinateAddress(dimension, x, y, z);
    }

    @Override
    public ClientRelay relay() {
        return relays;
    }

    @Override
    public Ipv6Address hostAddress(String key) {
        AddressBook b = book;
        return b == null ? null : b.hosts.get(key);
    }

    @Override
    public moe.qingu.youran.api.network.RawHost rawHost(String key) {
        return key == null ? null : rawHosts.get(key);
    }

    @Override
    public Ipv6Address networkPrefix() {
        AddressBook b = book;
        return b == null ? null : b.layout.prefix();
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
    public CertificateAuthority certificateAuthority() {
        return ca;
    }

    /** 证书签发服务（{@code /ynet ca} 用）；网络没在运行时为 null */
    CaService caService() {
        return ca;
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

    // ---- 系统确认框 ----

    @Override
    public CompletableFuture<ConfirmResult> confirm(UUID player, ConfirmRequest request) {
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
            if (out.isCancelled()) {
                return;
            }
            CompletableFuture<ConfirmResult> f = confirmPlayer(p, player, request);
            f.whenComplete((r, e) -> out.complete(r != null ? r : new ConfirmResult(ConfirmResult.Outcome.UNAVAILABLE, null, null)));
            // 调用方撤回：框还开着就关掉（下一 tick，或同一个人再弹框时）
            out.whenComplete((r, e) -> {
                if (out.isCancelled()) {
                    f.cancel(false);
                }
            });
        });
        return out;
    }

    /** 主线程：问一个在线玩家；他下线时作废 */
    private CompletableFuture<ConfirmResult> confirmPlayer(ConfirmPrompts p, UUID player, ConfirmRequest request) {
        MinecraftServer s = server;
        EntityPlayerMP e = s == null || player == null ? null : s.getPlayerList().getPlayerByUUID(player);
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

    // ---- 定位模块、身份模块 ----

    /** 签名台要的东西（主线程） */
    private final class ProofEnv implements ProofDesk.Env {
        @Override
        public long nowMillis() {
            return System.currentTimeMillis();
        }

        @Override
        public CompletableFuture<ConfirmResult> confirm(UUID player, ConfirmRequest request) {
            ConfirmPrompts p = prompts;
            if (p == null) {
                return CompletableFuture.completedFuture(new ConfirmResult(ConfirmResult.Outcome.UNAVAILABLE, null, null));
            }
            return confirmPlayer(p, player, request);
        }
    }

    @Override
    public java.security.PublicKey locationKey() {
        ProofDesk d = proofs;
        LocationSigner s = d == null ? null : d.locationSigner;
        return s == null ? null : s.publicKey();
    }

    @Override
    public java.security.PublicKey operatorKey() {
        ProofDesk d = proofs;
        OperatorSigner s = d == null ? null : d.operatorSigner;
        return s == null ? null : s.publicKey();
    }

    LocationSigner locationSigner() {
        ProofDesk d = proofs;
        return d == null ? null : d.locationSigner;
    }

    @Override
    public CompletableFuture<LocationResult> signLocation(NetworkInterface nic, GameProfile player, int dimension, int x, int y, int z, String site, String nonce) {
        return onMain(d -> d.location(nic, player, dimension, x, y, z, site, nonce), LocationResult.of(LocationResult.Status.UNAVAILABLE));
    }

    @Override
    public CompletableFuture<OperatorResult> signOperator(NetworkInterface nic, GameProfile player, String site, String nonce) {
        return onMain(d -> d.operator(nic, player, site, nonce), OperatorResult.of(OperatorResult.Status.UNAVAILABLE));
    }

    /** 转到主线程上交给签名台；网络没在运行、服务器正在关时以 {@code unavailable} 完成 */
    private <T> CompletableFuture<T> onMain(java.util.function.Function<ProofDesk, CompletableFuture<T>> task, T unavailable) {
        MinecraftServer s = server;
        ProofDesk d = proofs;
        if (s == null || d == null) {
            return CompletableFuture.completedFuture(unavailable);
        }
        CompletableFuture<T> out = new CompletableFuture<>();
        mainThreadTasks.add(out);
        out.whenComplete((v, e) -> mainThreadTasks.remove(out));
        s.addScheduledTask(() -> {
            if (proofs != d) {
                out.complete(unavailable);
                return;
            }
            task.apply(d).whenComplete((r, e) -> out.complete(r != null ? r : unavailable));
        });
        return out;
    }

    @Override
    public File worldDirectory() {
        return worldDirectory;
    }

    @Override
    public void onClientPacket(EntityPlayerMP player, int port, byte[] packet, int messageLength) {
        ExecutorEventLoop l = loop;
        if (l == null) {
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
            clientPacket(id, name, port, packet, messageLength);
        });
    }

    /** 网络线程：玩家客户端从网口 {@code port} 发来的包（0 号是他自己的网口，别的是接着的网卡） */
    void clientPacket(UUID id, String name, int port, byte[] packet, int messageLength) {
        RejectLog r = rejects;
        if (packet == null) {
            r.reject("玩家 " + name, messageLength > moe.qingu.youran.net.NicMessages.MAX_MESSAGE
                    ? "消息超过 " + moe.qingu.youran.net.NicMessages.MAX_MESSAGE + " 字节（" + messageLength + " 字节），包超过 1280 字节"
                    : "不是原始包消息或长度不对（" + messageLength + " 字节）", loop.nowMillis());
            return;
        }
        if (port == moe.qingu.youran.net.NicMessages.PLAYER_PORT) {
            PlayerNics p = players;
            if (p == null) {
                r.reject("玩家 " + name, "玩家临时地址没开（network.playerAddresses=false）", loop.nowMillis());
                return;
            }
            p.fromPlayer(id, name, packet);
            return;
        }
        ClientRelays c = relays;
        if (c != null) {
            c.fromClient(id, name, port, packet);
        }
    }

    // ---- 给命令用 ----

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

    TestNics testNics() {
        return testNics;
    }

    NicTable nicTable() {
        return nics;
    }

    ClientRelays relays() {
        return relays;
    }

    ProofDesk proofs() {
        return proofs;
    }

    RecursiveResolver resolver() {
        return resolver;
    }

    RejectLog rejects() {
        return rejects;
    }

    PlayerNics players() {
        return players;
    }

    NetStack hostStack(String key) {
        return hostStacks.get(key);
    }

    Map<String, RawHostPort> rawHosts() {
        return rawHosts;
    }

    boolean running() {
        return loop != null;
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
