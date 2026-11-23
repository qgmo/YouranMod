package moe.qingu.youran.server.net.pay;

import com.mojang.authlib.GameProfile;
import moe.qingu.youran.api.network.ConfirmRequest;
import moe.qingu.youran.api.network.DomainNames;
import moe.qingu.youran.api.network.stack.HttpClient;
import moe.qingu.youran.api.pay.AccountRef;
import moe.qingu.youran.api.pay.Amount;
import moe.qingu.youran.api.pay.Currency;
import moe.qingu.youran.api.pay.DepositInfo;
import moe.qingu.youran.api.pay.OrderInfo;
import moe.qingu.youran.api.pay.OrderSpec;
import moe.qingu.youran.api.pay.PayApi;
import moe.qingu.youran.api.pay.PayException;
import moe.qingu.youran.api.pay.PayItem;
import moe.qingu.youran.api.pay.PayRecord;
import moe.qingu.youran.api.pay.PaymentInfo;
import moe.qingu.youran.api.pay.VirtualAccount;
import moe.qingu.youran.network.PayBackend;
import moe.qingu.youran.network.PayHooks;
import net.minecraft.entity.player.EntityPlayerMP;
import net.minecraft.item.ItemStack;
import net.minecraft.server.MinecraftServer;
import net.minecraftforge.common.MinecraftForge;
import net.minecraftforge.fml.common.eventhandler.SubscribeEvent;
import net.minecraftforge.fml.common.gameevent.PlayerEvent;
import net.minecraftforge.fml.common.gameevent.TickEvent;

import java.io.File;
import java.io.IOException;
import java.security.SecureRandom;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.regex.Pattern;

/**
 * 聚合付款服务 pay.yr 的服务端本体（不含网页）：账本、动背包、给模组的接口（{@link PayBackend}）、准备确认框与确认后执行。
 * 账本在世界存档目录的 {@code youranmod/pay/}。开服时读账本、照存档补上崩溃时没结束的那几笔；关服时写快照。
 * <p>
 * 网页与 HTTP 接口在 {@link PayService}（网络线程），要动游戏的都交到这里的主线程方法。
 */
public final class PayServer implements PayBackend {
    static final int MAX_DESCRIPTION = 60;
    static final int MAX_REFERENCE = 64;
    static final int MAX_ITEM_LINES = 16;
    static final long MAX_YUAN = 1_000_000;
    static final long MAX_ITEM_COUNT = 100_000;
    static final Pattern REFERENCE = Pattern.compile("[\\x20-\\x7e]{1," + MAX_REFERENCE + "}");
    static final Pattern DOMAIN = Pattern.compile("[a-z0-9]([a-z0-9.-]*[a-z0-9])?");

    private final MinecraftServer server;
    final Ledger ledger;
    private final McWorld world;
    private final TxRunner runner;
    /** 唯一的付款入口（{@code PayApi.pay}）：付款页确认以后、模组直接付，都经它 */
    final PayEntry entry;
    /** 通知收款方（至少一次）：付款、退款以后回调，没确认的开服后、监听器登记时补发 */
    final PayNotifier notifier;
    /** 付款页的网址用 https（服务器配了中间 CA）还是 http */
    private volatile boolean https;
    private final Events events = new Events();
    private int ticks;

    private PayServer(MinecraftServer server, Ledger ledger) {
        this.server = server;
        this.ledger = ledger;
        this.world = new McWorld(server);
        this.runner = new TxRunner(ledger, world, System::currentTimeMillis, this::firePaid);
        this.entry = new PayEntry(ledger, world, runner, System::currentTimeMillis, this::firePaid, ItemText::cacheAll);
        this.notifier = new PayNotifier(ledger, PayHooks::listeners, id -> ledger.orderInfo(id, System.currentTimeMillis(), this::checkoutUrl),
                System::currentTimeMillis);
    }

    /** 开服（主线程，世界已加载、还没有玩家）：读账本、恢复、登记接口；读不了账本时返回 null（pay.yr 不开） */
    public static PayServer start(MinecraftServer server) {
        File dir = new File(new File(server.getWorld(0).getSaveHandler().getWorldDirectory(), "youranmod"), "pay");
        Ledger ledger = new Ledger(new PayStore(dir.toPath()), new SecureRandom());
        try {
            ledger.load();
        } catch (IOException | RuntimeException e) {
            Ledger.LOG.error("pay.yr：读不了账本 {}，pay.yr 不开（文件没有动；修好后重启）", dir, e);
            ledger.close();
            return null;
        }
        PayServer s = new PayServer(server, ledger);
        // 上次没确认通知收款方的（含下面崩溃恢复补上的付款）：开服后第一次 tick 补发，那时各模组都已开服、读好了自己的存档
        s.notifier.kickAll();
        int pending = ledger.pending().size();
        if (pending > 0) {
            int n = s.runner.recover();
            Ledger.LOG.warn("pay.yr：上次关服时有 {} 笔扣物品 / 发物品没结束，照存档补上了 {} 笔", pending, n);
        }
        MinecraftForge.EVENT_BUS.register(s.events);
        PayHooks.setBackend(s);
        // 加载阶段登记的显示名：账户已经有的改名（新建的账户在第一次用到时带上）
        for (java.util.Map.Entry<String, String> e : PayHooks.displayNames().entrySet()) {
            if (ledger.account(AccountRef.virtual(e.getKey())) != null) {
                s.applyDisplayName(e.getKey(), e.getValue());
            }
        }
        int unnotified = ledger.notices().size();
        Ledger.LOG.info("pay.yr：账本 {} 已读{}", dir, unnotified == 0 ? "" : "；" + unnotified + " 笔付款 / 退款还没确认通知过收款方，开服后补发（收款方没登记监听器的等登记了再发）");
        return s;
    }

    /** 网络线程：在 pay.yr 这台主机上开网页与接口 */
    public static void startWeb(PayServer s, moe.qingu.youran.api.network.HostContext ctx, moe.qingu.youran.api.network.Ipv6Address resolver) {
        PayService service = new PayService(s, ctx, resolver);
        service.start();
        Ledger.LOG.info("pay.yr：网页与接口开在 {}（{}）", ctx.address(), service.tls == null ? "HTTP" : "HTTP 与 HTTPS");
    }

    public void stop() {
        if (PayHooks.backend() == this) {
            PayHooks.setBackend(null);
        }
        MinecraftForge.EVENT_BUS.unregister(events);
        runner.clear();
        ledger.snapshotNow(System.currentTimeMillis(), keepMillis());
        ledger.close();
    }

    void setHttps(boolean https) {
        this.https = https;
    }

    boolean https() {
        return https;
    }

    static long keepMillis() {
        return PayConfig.keepDays * 24L * 3600 * 1000;
    }

    MinecraftServer server() {
        return server;
    }

    /** 在主线程上做（已经在主线程就直接做） */
    void onMain(Runnable r) {
        if (server.isCallingFromMinecraftThread()) {
            r.run();
        } else {
            server.addScheduledTask(r);
        }
    }

    // ---------------------------------------------------------------- 收款单

    @Override
    public String checkoutUrl(String orderId) {
        return (https ? "https://" : "http://") + PayApi.DOMAIN + "/c/" + orderId;
    }

    @Override
    public OrderInfo createOrder(OrderSpec spec) throws PayException {
        mainThread();
        long now = System.currentTimeMillis();
        Ledger.Order o = create(draft(spec, "mod", now), now);
        return ledger.info(o, now, this::checkoutUrl);
    }

    Ledger.Order create(Ledger.Draft d, long now) throws PayException {
        if (ledger.openOrders(d.receiver, now) >= PayConfig.maxOpenOrders) {
            throw new PayException("too_many_orders", "还能付的账单太多了（最多 " + PayConfig.maxOpenOrders + " 张），等一些过期或取消后再建");
        }
        try {
            if (d.receiver.isPlayer()) {
                if (ledger.account(d.receiver) == null) {
                    GameProfile gp = server.getPlayerProfileCache().getProfileByUUID(d.receiver.player);
                    if (gp == null) {
                        throw new PayException("no_such_account", "收款人不是这个服务器的玩家");
                    }
                    ledger.touchPlayer(d.receiver.player, gp.getName());
                }
            } else {
                ledger.ensureVirtual(d.receiver.virtual, PayHooks.displayName(d.receiver.virtual));
            }
            return ledger.createOrder(d, now);
        } catch (IOException e) {
            Ledger.LOG.error("pay.yr：写账本失败，收款单没建", e);
            throw new PayException("storage", "pay.yr 记账失败，请稍后再试");
        }
    }

    /** 检查收款单的各项（主线程：要做物品） */
    Ledger.Draft draft(OrderSpec spec, String origin, long now) throws PayException {
        Ledger.Draft d = new Ledger.Draft();
        d.receiver = spec.receiver;
        String site = spec.site == null ? null : DomainNames.tryToAscii(spec.site.trim().toLowerCase(java.util.Locale.ROOT));
        if (site == null || site.isEmpty() || site.length() > DomainNames.MAX_NAME || !DOMAIN.matcher(site).matches() || !site.contains(".")) {
            throw new PayException("invalid_site", "网站域名不对：" + spec.site);
        }
        d.site = site;
        Amount price = spec.price();
        if (price.isZero()) {
            throw new PayException("invalid_amount", "价格不能是零");
        }
        if (price.yuan > MAX_YUAN) {
            throw new PayException("invalid_amount", "价格太大（最多 " + MAX_YUAN + " 元）");
        }
        if (price.items.size() > MAX_ITEM_LINES) {
            throw new PayException("invalid_amount", "物品种类太多（最多 " + MAX_ITEM_LINES + " 种）");
        }
        Map<PayItem, Long> items = new LinkedHashMap<>();
        for (Map.Entry<PayItem, Long> e : price.items.entrySet()) {
            PayItem i = e.getKey();
            if (e.getValue() > MAX_ITEM_COUNT) {
                throw new PayException("invalid_amount", "物品数量太大（每种最多 " + MAX_ITEM_COUNT + " 个）");
            }
            ItemStack s = i.toStack(1);
            if (s.isEmpty()) {
                throw new PayException("invalid_item", "没有这种物品：" + i);
            }
            PayItem norm = McWorld.itemOf(s);
            if (norm == null) {
                throw new PayException("invalid_item", "这种物品的 NBT 记不了账：" + i);
            }
            checkUsable(norm, s);
            items.merge(norm, e.getValue(), Math::addExact);
            ItemText.cache(norm);
        }
        d.price = Amount.of(price.yuan, items);
        d.unit = spec.unit();
        d.description = text(spec.description(), MAX_DESCRIPTION, "description", "说明");
        String ref = spec.reference();
        if (ref != null && !ref.isEmpty() && !REFERENCE.matcher(ref).matches()) {
            throw new PayException("invalid_reference", "reference 最多 " + MAX_REFERENCE + " 个可见 ASCII 字符");
        }
        d.reference = ref == null || ref.isEmpty() ? null : ref;
        d.returnUrl = url(spec.returnUrl(), site, "return_url");
        d.cancelUrl = url(spec.cancelUrl(), site, "cancel_url");
        int ttl = spec.ttlSeconds() <= 0 ? PayConfig.orderTtlSeconds : spec.ttlSeconds();
        if (ttl < 60 || ttl > PayConfig.orderMaxTtlSeconds) {
            throw new PayException("invalid_expires", "有效期要在 60–" + PayConfig.orderMaxTtlSeconds + " 秒之间");
        }
        d.expiresAt = now + ttl * 1000L;
        d.origin = origin;
        if (spec.payer() != null) {
            if (d.receiver.isPlayer() && d.receiver.player.equals(spec.payer())) {
                throw new PayException("self_payment", "指定的付款人就是收款人");
            }
            d.onlyPayer = spec.payer();
        }
        return d;
    }

    /** 这种物品能不能用来付：黑名单、装着东西的容器 */
    static void checkUsable(PayItem item, ItemStack stack) throws PayException {
        checkUsable(item, stack, "用来付款");
    }

    /** 同上；{@code what} 写进原因里（“用来付款”“充值”） */
    static void checkUsable(PayItem item, ItemStack stack, String what) throws PayException {
        if (item.isCurrency()) {
            return;
        }
        if (ItemRules.blacklisted(item, PayConfig.blacklist)) {
            throw new PayException("item_not_allowed", "这种物品不能" + what + "（服务器设置）：" + ItemText.cache(item));
        }
        if (ItemRules.filledContainer(stack)) {
            throw new PayException("item_not_allowed", "装着东西的容器不能" + what + "：" + ItemText.cache(item));
        }
    }

    /**
     * 充值收的东西（服主 11-18，规则同付款）：钱已经在 {@link Amount} 里按元折好；其他物品要现在有、NBT 记得了账、不在黑名单、不是装着东西的容器，
     * 按 pay.yr 的写法重新认一遍（同 {@link #draft}）。不行时抛异常。主线程（要做物品）
     */
    static Amount depositable(Amount amount) throws PayException {
        if (amount == null || amount.isZero()) {
            throw new PayException("invalid_amount", "要充多少？");
        }
        Map<PayItem, Long> items = new LinkedHashMap<>();
        for (Map.Entry<PayItem, Long> e : amount.items.entrySet()) {
            PayItem i = e.getKey();
            ItemStack s = i.toStack(1);
            if (s.isEmpty()) {
                throw new PayException("invalid_item", "没有这种物品：" + i);
            }
            PayItem norm = McWorld.itemOf(s);
            if (norm == null) {
                throw new PayException("invalid_item", "这种物品的 NBT 记不了账：" + i);
            }
            checkUsable(norm, s, "充值");
            items.merge(norm, e.getValue(), Math::addExact);
        }
        return Amount.of(amount.yuan, items);
    }

    private static String text(String s, int max, String field, String what) throws PayException {
        if (s == null || s.trim().isEmpty()) {
            return null;
        }
        String t = s.trim();
        if (t.codePointCount(0, t.length()) > max) {
            throw new PayException("invalid_" + field, what + "最多 " + max + " 个字");
        }
        for (int i = 0; i < t.length(); i++) {
            if (!ConfirmRequest.isAllowedChar(t.charAt(i))) {
                throw new PayException("invalid_" + field, what + "里有不允许的字符（控制字符、颜色代码、文字方向控制）");
            }
        }
        return t;
    }

    /** 跳回的网址：http(s)，主机必须是这个网站 */
    static String url(String u, String site, String field) throws PayException {
        if (u == null || u.trim().isEmpty()) {
            return null;
        }
        String t = u.trim();
        HttpClient.Url p = t.length() > 512 ? null : HttpClient.Url.parse(t);
        if (p == null || !(t.regionMatches(true, 0, "http://", 0, 7) || t.regionMatches(true, 0, "https://", 0, 8)) || t.contains("#")
                || !t.matches("[\\x21-\\x7e]+")) {
            throw new PayException("invalid_" + field, field + " 要是 http:// 或 https:// 开头的网址（最长 512，不能有 #）");
        }
        if (!p.host.equalsIgnoreCase(site)) {
            throw new PayException("invalid_" + field, field + " 的主机要是这个网站（" + site + "）");
        }
        return t;
    }

    @Override
    public OrderInfo order(String id) {
        return ledger.orderInfo(id, System.currentTimeMillis(), this::checkoutUrl);
    }

    /** 付款入口（{@code PayApi.pay}，主线程）：做成了返回付款；没做成抛原因；玩家付款存盘还没核对上抛 {@code processing} */
    @Override
    public PaymentInfo pay(moe.qingu.youran.api.pay.PaySpec spec) throws PayException {
        mainThread();
        if (spec == null) {
            throw new PayException("invalid_order", "要写付哪张账单");
        }
        TxRunner.Result r = entry.pay(spec, null);
        switch (r.status) {
            case DONE:
                return r.paid.payment.info();
            case PROCESSING:
                throw new PayException("processing", "东西已经从背包扣了，正在确认存盘（每秒重试，关服后下次开服照存档补上或作废）；稍后用 PayApi.order 查结果");
            default:
                throw r.error;
        }
    }

    @Override
    public boolean cancelOrder(String id, AccountRef receiver) {
        try {
            return ledger.cancel(id, receiver, System.currentTimeMillis());
        } catch (IOException e) {
            Ledger.LOG.error("pay.yr：写账本失败，收款单没取消", e);
            return false;
        }
    }

    @Override
    public PaymentInfo payment(String id) {
        Ledger.Payment p = ledger.payment(id);
        return p == null ? null : p.info();
    }

    @Override
    public Amount balance(AccountRef account) {
        return ledger.balance(account);
    }

    @Override
    public List<PayRecord> records(AccountRef account, int max) {
        return ledger.records(account, max);
    }

    /** 模组给虚拟账户收到的付款退款 */
    @Override
    public PaymentInfo refund(String paymentId, Amount amount, String note) throws PayException {
        mainThread();
        Ledger.Payment p = ledger.payment(paymentId);
        if (p == null) {
            throw new PayException("no_such_payment", "没有这笔付款");
        }
        if (p.receiver.isPlayer()) {
            throw new PayException("not_allowed", "收款人是玩家的付款只能由他自己在 pay.yr 上退");
        }
        return doRefund(paymentId, amount, note);
    }

    PaymentInfo doRefund(String paymentId, Amount amount, String note) throws PayException {
        try {
            Ledger.Payment p = ledger.refund(paymentId, amount, note, System.currentTimeMillis());
            notifier.notify(p.id);
            return p.info();
        } catch (IOException e) {
            Ledger.LOG.error("pay.yr：写账本失败，没退款", e);
            throw new PayException("storage", "pay.yr 记账失败，请稍后再试");
        }
    }

    @Override
    public VirtualAccount virtual(String key) throws PayException {
        AccountRef ref;
        try {
            ref = AccountRef.virtual(key);
        } catch (IllegalArgumentException e) {
            throw new PayException("invalid_account", e.getMessage());
        }
        try {
            ledger.ensureVirtual(key, PayHooks.displayName(key));
        } catch (IOException e) {
            throw new PayException("storage", "pay.yr 记账失败，请稍后再试");
        }
        return new Virtual(ref);
    }

    @Override
    public void applyDisplayName(String key, String name) {
        try {
            ledger.ensureVirtual(key, name);
        } catch (IOException | RuntimeException e) {
            Ledger.LOG.error("pay.yr：写账本失败，虚拟账户 {} 的显示名没记下", key, e);
        }
    }

    private void firePaid(Ledger.Paid paid) {
        OrderInfo o = ledger.info(paid.order, System.currentTimeMillis(), this::checkoutUrl);
        PaymentInfo p = paid.payment.info();
        Ledger.LOG.info("pay.yr：{} 付了 {}（{}，{} → {}）", p.payerName, o.id, p.amount, o.site, o.receiverName);
        notifier.notify(p.id);
    }

    @Override
    public void listenerAdded(AccountRef account) {
        notifier.kick(account);
    }

    private void mainThread() throws PayException {
        if (!server.isCallingFromMinecraftThread()) {
            throw new PayException("wrong_thread", "要在服务端主线程上调用");
        }
    }

    // ---------------------------------------------------------------- 确认框的内容

    /**
     * 要请 id.yr 弹的确认框（pay.yr 是 id.yr 的普通网站，用它的弹窗确认 / 验证身份能力，不直接调系统确认框）：标题、条目（都是写好的中文）、
     * 确认的是哪一类事。条目最多 {@value #MAX_ASK_ITEMS} 条（id.yr 的上限），每条最多 80 个字。
     */
    static final class Ask {
        static final int MAX_ASK_ITEMS = 10;
        final String title;
        final List<String> items;
        final ConfirmRequest.Purpose purpose;

        Ask(String title, List<String> items, ConfirmRequest.Purpose purpose) {
            this.title = ItemText.clip(ItemText.clean(title), ConfirmRequest.MAX_TITLE);
            List<String> l = new ArrayList<>();
            for (String i : items) {
                l.add(ItemText.clip(ItemText.clean(i), ConfirmRequest.MAX_ITEM));
            }
            this.items = java.util.Collections.unmodifiableList(l);
            this.purpose = purpose;
        }
    }

    /** 条目太多时只留前面的，最后一条写“还有几行” */
    private static List<String> fit(List<String> head, List<String> lines, List<String> tail) {
        int room = Ask.MAX_ASK_ITEMS - head.size() - tail.size();
        List<String> out = new ArrayList<>(head);
        if (lines.size() > room) {
            int more = lines.size() - (room - 1);
            out.addAll(lines.subList(0, room - 1));
            out.add("……还有 " + more + " 行，见网页");
        } else {
            out.addAll(lines);
        }
        out.addAll(tail);
        return out;
    }

    /** 一行“前缀 物品名 ×个数”（物品名在主线程上算好） */
    private static String line(String prefix, PayItem item, long n) {
        return prefix + ItemText.clip(ItemText.cache(item), 40) + " ×" + n;
    }

    /** 一行钱：“前缀 20 绿宝石（40 元）” */
    private static String money(String prefix, long yuan, Currency.Unit unit) {
        Currency.Unit other = unit == Currency.Unit.EMERALD ? Currency.Unit.YUAN : Currency.Unit.EMERALD;
        return prefix + Currency.format(yuan, unit) + "（" + Currency.format(yuan, other) + "）";
    }

    // ---------------------------------------------------------------- 付款（主线程）

    /** 准备好的一次付款：算好的拿法（背包、余额）与确认框的内容 */
    static final class PreparedPay {
        final String orderId;
        final UUID payer;
        /** 确认框里写的拿法：确认以后付款入口照它扣 */
        final PayEntry.Plan confirmed;
        final Ask ask;

        PreparedPay(String orderId, UUID payer, PayEntry.Plan confirmed, Ask ask) {
            this.orderId = orderId;
            this.payer = payer;
            this.confirmed = confirmed;
            this.ask = ask;
        }
    }
    /** 付款页上的预览：账户余额、用余额付多少、剩下的按现在的背包怎么拿 */
    static final class Preview {
        /** 登录的角色不在线时为 null */
        final CurrencyPlan.PayPlan plan;
        final Amount balance;
        final Amount fromBalance;
        /** 选的余额用法不行（比余额多……）时的说明 */
        final String error;

        Preview(CurrencyPlan.PayPlan plan, Amount balance, Amount fromBalance, String error) {
            this.plan = plan;
            this.balance = balance;
            this.fromBalance = fromBalance;
            this.error = error;
        }
    }

    /** 付款页上预览：按现在的余额与背包怎么付（主线程） */
    Preview preview(String orderId, UUID payer, CurrencyPlan.Prefer prefer, CurrencyPlan.BalanceUse use) {
        Ledger.Order o = ledger.order(orderId);
        Amount bal = ledger.balance(AccountRef.player(payer));
        ItemText.cacheAll(bal);
        WorldPort.PlayerPort p = world.player(payer);
        if (o == null) {
            return new Preview(null, bal, Amount.ZERO, null);
        }
        Amount fromBal;
        String error = null;
        try {
            fromBal = CurrencyPlan.fromBalance(o.price, bal, use);
        } catch (PayException e) {
            fromBal = Amount.ZERO;
            error = e.getMessage();
        }
        if (p == null) {
            return new Preview(null, bal, fromBal, error);
        }
        CurrencyPlan.PayPlan plan = CurrencyPlan.planPayment(o.price.minus(fromBal), p.counts(), prefer);
        ItemText.cacheAll(plan.take);
        ItemText.cacheAll(plan.change);
        ItemText.cacheAll(fromBal);
        if (plan.lack != null) {
            ItemText.cacheAll(plan.lack);
        }
        return new Preview(plan, bal, fromBal, error);
    }

    PreparedPay preparePay(String orderId, UUID payer, CurrencyPlan.Prefer prefer) throws PayException {
        return preparePay(orderId, payer, prefer, CurrencyPlan.BalanceUse.NONE);
    }

    /**
     * 准备付款：登录的角色 {@code payer} 付（要在线：背包、余额都是他的）；确认框由 id.yr 弹给这台电脑现在的操作者，谁同意都照 {@code payer} 的账扣
     * （服主 11-15：另一个玩家坐到已登录的电脑前动钱是允许的，同 MineBay“相当于老婆帮你买东西”）。
     */
    PreparedPay preparePay(String orderId, UUID payer, CurrencyPlan.Prefer prefer, CurrencyPlan.BalanceUse use) throws PayException {
        long now = System.currentTimeMillis();
        Ledger.Order o = ledger.order(orderId);
        if (o == null) {
            throw new PayException("no_such_order", "没有这张账单");
        }
        OrderInfo.Status st = o.status(now);
        if (st != OrderInfo.Status.OPEN) {
            throw new PayException("order_not_open", statusText(st));
        }
        WorldPort.PlayerPort p = world.player(payer);
        if (p == null) {
            throw new PayException("offline", "登录的角色不在线");
        }
        if (o.receiver.isPlayer() && o.receiver.player.equals(payer)) {
            throw new PayException("self_payment", "不能付给自己");
        }
        if (o.onlyPayer != null && !o.onlyPayer.equals(payer)) {
            throw new PayException("wrong_payer", "这张账单要由 " + ledger.name(AccountRef.player(o.onlyPayer)) + " 付（网站指定的），现在登录的是 " + p.name());
        }
        if (ledger.hasPending(payer)) {
            throw new PayException("busy", "上一笔还没处理完，请稍后再试");
        }
        // 拿法和不带确认的玩家付款用同一个算法（付款入口的 plan）；确认以后照这个扣
        PayEntry.Plan confirmed = entry.plan(o, payer, prefer, use);
        CurrencyPlan.PayPlan plan = confirmed.items;
        Amount fromBal = confirmed.fromBalance;
        ItemText.cacheAll(fromBal);
        String recv = ItemText.clip(ledger.name(o.receiver), 24);
        List<String> head = new ArrayList<>();
        head.add("收款人：" + recv);
        if (o.site != null && !o.site.equals(PayApi.DOMAIN)) {
            // 框上的“网站”是 pay.yr（是它请 id.yr 弹的框），收款的网站写在这里
            head.add("收款网站：" + PayPages.site(o.site));
        }
        if (o.description != null) {
            head.add("内容：" + ItemText.clip(o.description, 60));
        }
        if (o.price.yuan > 0) {
            head.add(money("价格：", o.price.yuan, o.unit));
        }
        List<String> lines = new ArrayList<>();
        if (fromBal.yuan > 0) {
            lines.add(money("用 pay.yr 余额：", fromBal.yuan, Currency.Unit.YUAN));
        }
        for (Map.Entry<PayItem, Long> e : fromBal.items.entrySet()) {
            lines.add(line("用 pay.yr 余额：", e.getKey(), e.getValue()));
        }
        for (Map.Entry<PayItem, Long> e : plan.take.entrySet()) {
            lines.add(line("拿出：", e.getKey(), e.getValue()));
        }
        for (Map.Entry<PayItem, Long> e : plan.change.entrySet()) {
            lines.add(line("找零：", e.getKey(), e.getValue()));
        }
        String payerName = p.name();
        String last = plan.take.isEmpty() ? "这是 " + payerName + " 的 pay.yr 账户" : "从 " + payerName + " 的背包里拿";
        Ask ask = new Ask("付款给 " + recv, fit(head, lines, java.util.Collections.singletonList(last)), ConfirmRequest.Purpose.PAY);
        return new PreparedPay(orderId, payer, confirmed, ask);
    }

    /**
     * 确认框同意以后（主线程）：调付款入口，照确认时的拿法扣（余额那部分从登录的角色的账户出）。付款页没有别的付法，
     * 拒绝或超时就不调这里，什么都不动。
     */
    TxRunner.Result executePay(PreparedPay prep) {
        return entry.pay(new moe.qingu.youran.api.pay.PaySpec(prep.orderId, AccountRef.player(prep.payer)), prep.confirmed);
    }

    static String statusText(OrderInfo.Status s) {
        switch (s) {
            case PAID:
                return "这张账单已经付过了";
            case CANCELED:
                return "这张账单已经被网站取消了";
            case EXPIRED:
                return "这张账单已经过期了";
            case PAYING:
                return "这张账单正在付款";
            default:
                return "可以付款";
        }
    }

    /** 这一笔还在“处理中”（存盘没核对上，在重试） */
    boolean processing(String txId) {
        return txId != null && runner.processing(txId);
    }

    // ---------------------------------------------------------------- 提现（主线程）

    static final class PreparedWithdraw {
        final AccountRef account;
        final UUID player;
        final Amount value;
        final Map<PayItem, Long> give;
        final Ask ask;

        PreparedWithdraw(AccountRef account, UUID player, Amount value, Map<PayItem, Long> give, Ask ask) {
            this.account = account;
            this.player = player;
            this.value = value;
            this.give = give;
            this.ask = ask;
        }
    }

    /** 提现：钱 {@code yuan}（按 {@code form} 给）加物品 {@code items}，给账户的主人（要在线） */
    PreparedWithdraw prepareWithdraw(UUID player, long yuan, Currency.Unit form, Map<PayItem, Long> items) throws PayException {
        AccountRef acct = AccountRef.player(player);
        WorldPort.PlayerPort p = world.player(player);
        if (p == null) {
            throw new PayException("offline", "提现要账户的主人在线（东西放进他的背包）");
        }
        Amount value = Amount.of(yuan, items);
        if (value.isZero()) {
            throw new PayException("invalid_amount", "要提多少？");
        }
        Amount bal = ledger.balance(acct);
        if (!bal.covers(value)) {
            throw new PayException("insufficient_balance", "账户余额不够：还差 " + ItemText.describe(bal.shortOf(value), Currency.Unit.YUAN));
        }
        Map<PayItem, Long> give = new LinkedHashMap<>(CurrencyPlan.split(yuan, form));
        for (Map.Entry<PayItem, Long> e : value.items.entrySet()) {
            if (!e.getKey().exists()) {
                throw new PayException("invalid_item", "这种物品现在没有了（模组没装），提不出来：" + e.getKey());
            }
            give.merge(e.getKey(), e.getValue(), Math::addExact);
        }
        String name = p.name();
        List<String> lines = new ArrayList<>();
        for (Map.Entry<PayItem, Long> e : give.entrySet()) {
            lines.add(line("", e.getKey(), e.getValue()));
        }
        Ask ask = new Ask("从 " + name + " 的 pay.yr 账户提现",
                fit(java.util.Collections.singletonList("放进 " + name + " 的背包，放不下的掉在脚边"), lines, java.util.Collections.<String>emptyList()),
                ConfirmRequest.Purpose.RECEIVE);
        return new PreparedWithdraw(acct, player, value, give, ask);
    }

    TxRunner.Result executeWithdraw(PreparedWithdraw prep) {
        WorldPort.PlayerPort p = world.player(prep.player);
        if (p == null) {
            return TxRunner.Result.failed(new PayException("offline", "提现要账户的主人在线（东西放进他的背包）"));
        }
        return runner.withdraw(prep.account, p, prep.value, prep.give, "在 pay.yr 提现");
    }

    // ---------------------------------------------------------------- 退款、密钥、安全设置（主线程）

    static final class PreparedRefund {
        final String paymentId;
        final UUID by;
        final Amount amount;
        final Ask ask;

        PreparedRefund(String paymentId, UUID by, Amount amount, Ask ask) {
            this.paymentId = paymentId;
            this.by = by;
            this.amount = amount;
            this.ask = ask;
        }
    }

    PreparedRefund prepareRefund(UUID by, String paymentId, Amount amount) throws PayException {
        String owner = ItemText.clip(ledger.name(AccountRef.player(by)), 16);
        Ledger.Payment p = ledger.payment(paymentId);
        if (p == null || !p.receiver.equals(AccountRef.player(by))) {
            throw new PayException("no_such_payment", "没有这笔付款（只能退自己收到的）");
        }
        if (amount.isZero()) {
            throw new PayException("invalid_amount", "要退多少？");
        }
        if (!p.amount.minus(p.refunded).covers(amount)) {
            throw new PayException("exceeds_refundable", "超过了这笔还能退的：" + ItemText.describe(p.amount.minus(p.refunded), Currency.Unit.YUAN));
        }
        Amount bal = ledger.balance(p.receiver);
        if (!bal.covers(amount)) {
            throw new PayException("insufficient_balance", "账户余额不够退（已经提现了）：还差 " + ItemText.describe(bal.shortOf(amount), Currency.Unit.YUAN));
        }
        ItemText.cacheAll(amount);
        String payer = p.payerName == null ? "?" : p.payerName;
        List<String> head = new ArrayList<>();
        head.add("这是 " + owner + " 的 pay.yr 账户");
        head.add("退进 " + payer + " 在 pay.yr 的账户");
        List<String> lines = new ArrayList<>();
        if (amount.yuan > 0) {
            lines.add(money("从这个账户扣回：", amount.yuan, Currency.Unit.YUAN));
        }
        for (Map.Entry<PayItem, Long> e : amount.items.entrySet()) {
            lines.add(line("从这个账户扣回：", e.getKey(), e.getValue()));
        }
        Ask ask = new Ask("退款给 " + payer, fit(head, lines, java.util.Collections.<String>emptyList()), ConfirmRequest.Purpose.PAY);
        return new PreparedRefund(paymentId, by, amount, ask);
    }

    void executeRefund(PreparedRefund prep) throws PayException {
        doRefund(prep.paymentId, prep.amount, "收款人在 pay.yr 退款");
    }

    /** 重置密钥的确认框；标题写明是谁的账户（同一台电脑上别人登录着时，坐下的人看得出不是自己的） */
    Ask keyAsk(String accountName) {
        String n = ItemText.clip(accountName, 16);
        return new Ask("重置 " + n + " 的 pay.yr 密钥", java.util.Arrays.asList("生成一把新的密钥，给你的网站创建、查询账单用", "旧的密钥马上作废"),
                ConfirmRequest.Purpose.ACTION);
    }

    String executeKey(UUID player) throws PayException {
        try {
            return ledger.newKey(AccountRef.player(player), System.currentTimeMillis());
        } catch (IOException e) {
            throw new PayException("storage", "pay.yr 记账失败，请稍后再试");
        }
    }

    /** 打开、关闭“操作前必须验证身份”的确认框（总是验证身份：只有帐号本人能改） */
    Ask securityAsk(String accountName, boolean on) {
        String n = ItemText.clip(accountName, 16);
        return new Ask((on ? "打开" : "关闭") + "操作前验证身份", java.util.Arrays.asList("pay.yr 账户：" + n,
                on ? "打开后，付款、提现、退款、重置密钥前都要 " + n + " 本人确认" : "关闭后，坐在这台电脑前的人确认就能操作"), ConfirmRequest.Purpose.ACTION);
    }

    /** 这个账户开了“操作前必须验证身份”没有 */
    boolean verifyRequired(UUID player) {
        Ledger.Account a = ledger.account(AccountRef.player(player));
        return a != null && a.verifyBeforeOps;
    }

    void setVerifyRequired(UUID player, boolean on) throws PayException {
        try {
            ledger.setVerify(AccountRef.player(player), on);
        } catch (IOException e) {
            throw new PayException("storage", "pay.yr 记账失败，请稍后再试");
        } catch (IllegalStateException e) {
            throw new PayException("no_account", "没有这个账户");
        }
    }

    // ---------------------------------------------------------------- 虚拟账户

    private final class Virtual implements VirtualAccount {
        private final AccountRef ref;

        Virtual(AccountRef ref) {
            this.ref = ref;
        }

        @Override
        public AccountRef ref() {
            return ref;
        }

        @Override
        public String displayName() {
            return ledger.name(ref);
        }

        @Override
        public Amount balance() {
            return ledger.balance(ref);
        }

        @Override
        public List<PayRecord> records(int max) {
            return ledger.records(ref, max);
        }

        @Override
        public List<ItemStack> take(Amount amount, Currency.Unit form, String note) throws PayException {
            mainThread();
            for (PayItem i : amount.items.keySet()) {
                if (!i.exists()) {
                    throw new PayException("invalid_item", "这种物品现在没有了，取不出来：" + i);
                }
            }
            remove(amount, moe.qingu.youran.api.pay.PayRecord.Kind.TAKEN, note);
            List<ItemStack> out = new ArrayList<>();
            Map<PayItem, Long> things = new LinkedHashMap<>(CurrencyPlan.split(amount.yuan, form == null ? Currency.Unit.EMERALD : form));
            for (Map.Entry<PayItem, Long> e : amount.items.entrySet()) {
                things.merge(e.getKey(), e.getValue(), Math::addExact);
            }
            for (Map.Entry<PayItem, Long> e : things.entrySet()) {
                long left = e.getValue();
                while (left > 0) {
                    ItemStack s = e.getKey().toStack(1);
                    int n = (int) Math.min(left, s.getMaxStackSize());
                    s.setCount(n);
                    out.add(s);
                    left -= n;
                }
            }
            return out;
        }

        @Override
        public void discard(Amount amount, String note) throws PayException {
            mainThread();
            remove(amount, moe.qingu.youran.api.pay.PayRecord.Kind.DISCARDED, note);
        }

        private void remove(Amount amount, PayRecord.Kind kind, String note) throws PayException {
            try {
                ledger.remove(ref, amount, kind, note, System.currentTimeMillis());
            } catch (IOException e) {
                throw new PayException("storage", "pay.yr 记账失败，请稍后再试");
            }
        }

        @Override
        public DepositInfo deposit(Amount amount, String requestId, String note) throws PayException {
            mainThread();
            if (requestId != null && !REQUEST_ID.matcher(requestId).matches()) {
                throw new PayException("invalid_request_id", "请求编号要 1–100 个字母、数字或 _ . : -");
            }
            // 同一个编号、同样的东西：直接认出是重发（物品规则后来改了也不影响已经入账的那笔）
            DepositInfo prior = requestId == null ? null : ledger.deposited(ref, requestId);
            Amount checked = prior != null && prior.amount.equals(amount) ? amount : depositable(amount);
            try {
                DepositInfo d = ledger.deposit(ref, checked, requestId, note, System.currentTimeMillis());
                if (!d.replayed) {
                    Ledger.LOG.info("pay.yr：{} 充值 {}（{}）", ledger.name(ref), d.amount, d.id);
                }
                return d;
            } catch (IOException e) {
                Ledger.LOG.error("pay.yr：写账本失败，{} 没有充值", ref, e);
                throw new PayException("storage", "pay.yr 记账失败，没有充，请稍后再试");
            }
        }

        @Override
        public DepositInfo deposited(String requestId) {
            return ledger.deposited(ref, requestId);
        }

        @Override
        public void giveTo(EntityPlayerMP player, Amount amount, Currency.Unit form, String note) throws PayException {
            mainThread();
            WorldPort.PlayerPort p = player == null ? null : world.player(player.getUniqueID());
            if (p == null) {
                throw new PayException("offline", "玩家不在线");
            }
            Map<PayItem, Long> give = new LinkedHashMap<>(CurrencyPlan.split(amount.yuan, form == null ? Currency.Unit.EMERALD : form));
            for (Map.Entry<PayItem, Long> e : amount.items.entrySet()) {
                give.merge(e.getKey(), e.getValue(), Math::addExact);
            }
            TxRunner.Result r = runner.withdraw(ref, p, amount, give, note);
            if (r.status == TxRunner.Result.Status.FAILED) {
                throw r.error;
            }
        }
    }

    // ---------------------------------------------------------------- 事件（主线程）

    public final class Events {
        @SubscribeEvent
        public void onLogin(PlayerEvent.PlayerLoggedInEvent e) {
            if (!(e.player instanceof EntityPlayerMP)) {
                return;
            }
            EntityPlayerMP p = (EntityPlayerMP) e.player;
            try {
                ledger.touchPlayer(p.getUniqueID(), p.getName());
            } catch (IOException ex) {
                Ledger.LOG.warn("pay.yr：记玩家名字失败：{}", ex.toString());
            }
            McWorld.cleanMarkers(p, tx -> ledger.pending(tx) != null);
        }

        @SubscribeEvent
        public void onTick(TickEvent.ServerTickEvent e) {
            if (e.phase != TickEvent.Phase.END) {
                return;
            }
            ticks++;
            if (ticks % 20 == 0) {
                runner.tick();
            }
            if (ticks == 1 || ticks % 20 == 0) {
                notifier.tick();
            }
            if (ticks % 1200 == 0) {
                ledger.maybeSnapshot(System.currentTimeMillis(), keepMillis());
            }
        }
    }
}
