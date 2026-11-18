package moe.qingu.youran.server.net.pay;

import moe.qingu.youran.api.pay.PayApi;

import static moe.qingu.youran.server.net.pay.PayPages.esc;

/**
 * pay.yr 的开发者文档站 {@code docs.pay.yr}（服主 11-15：主站只放必要的，长说明挪到单独的站，照真的商业网站来）。
 * 和 pay.yr 同一台主机，按 Host 分。只有静态页：
 * <pre>
 *   /           接入流程（概览）
 *   /api        收款单接口：创建、查询、取消、余额、付款
 *   /errors     错误码
 *   /examples   示例（HTTP、纯文本、模组的 Java 接口）
 *   /help       给付款的玩家：怎么付、余额、提现、退款
 * </pre>
 */
final class PayDocs {
    private PayDocs() {
    }

    /** 没有这一页返回 null */
    static String page(String path, boolean https) {
        String base = (https ? "https" : "http") + "://" + PayApi.DOMAIN;
        switch (path) {
            case "/":
            case "/index.html":
                return wrap("接入 pay.yr", overview(base));
            case "/api":
                return wrap("收款单接口", api(base));
            case "/errors":
                return wrap("错误码", errors());
            case "/examples":
                return wrap("示例", examples(base));
            case "/help":
                return wrap("付款帮助", help());
            default:
                return null;
        }
    }

    private static String wrap(String title, String body) {
        StringBuilder b = new StringBuilder();
        b.append("<p class=\"hint\">")
                .append(nav("/", "接入流程")).append(" · ").append(nav("/api", "接口")).append(" · ").append(nav("/errors", "错误码"))
                .append(" · ").append(nav("/examples", "示例")).append(" · ").append(nav("/help", "付款帮助"))
                .append("</p>\n<h1>").append(esc(title)).append("</h1>\n").append(body);
        return PayPages.page(title + "（开发者文档）", null, null, b.toString(), 0, null);
    }

    private static String nav(String href, String text) {
        return PayPages.link(href, text);
    }

    private static String overview(String base) {
        return "<p>网站在后台开一张收款单，把玩家带到 pay.yr 付款，付完带回网站；网站后台核对后发货。不收手续费。</p>\n"
                + "<h2>四步</h2>\n<table class=\"list\">\n"
                + "<tr><td>1</td><td>收款的角色登录 " + PayPages.link(base + "/account", "pay.yr 账户") + "，生成密钥（只显示一次）。</td></tr>\n"
                + "<tr><td>2</td><td>后台 <code>POST /api/v1/orders</code> 开单，拿到付款页网址 <code>url</code>。</td></tr>\n"
                + "<tr><td>3</td><td>把玩家的浏览器跳到 <code>url</code>。玩家用游戏角色登录、在 id.yr 弹出的系统确认框里确认。</td></tr>\n"
                + "<tr><td>4</td><td>玩家回到 <code>return_url</code> 后，后台 <code>GET /api/v1/orders/单号</code>，<code>status=paid</code> 才发货。</td></tr>\n"
                + "</table>\n"
                + "<h2>要点</h2>\n<ul>\n"
                + "<li>价格标绿宝石或元（二选一）；绿宝石与悠然元按 1 : 2 互换，玩家用哪种、混着付都行。</li>\n"
                + "<li>也可以要别的物品（物品、数据值、NBT 完全一致）。装着东西的容器、服务器黑名单里的物品不收。</li>\n"
                + "<li>跳回网址里的 <code>status</code> 可以伪造，<b>发货前一定要后台再查</b>。</li>\n"
                + "<li>每张单只能付一次，有有效期；没有 webhook，跳回来时或轮询时查。</li>\n"
                + "<li>收到的钱记进你的 pay.yr 账户，在账户页提现、退款。</li>\n"
                + "</ul>\n<p>" + PayPages.link("/api", "接口详情 »") + "</p>\n";
    }

    private static String api(String base) {
        StringBuilder b = new StringBuilder();
        b.append("<p>地址 <code>").append(esc(base)).append("</code>（游戏内网络）。认证：<code>Authorization: Bearer 密钥</code>，或表单字段 <code>key</code>。"
                + "回 JSON；加 <code>format=text</code> 回一行一项的纯文本。每把密钥每分钟 ").append(PayConfig.apiPerMinute).append(" 次。</p>\n");
        b.append("<h2>创建收款单</h2>\n<pre>POST /api/v1/orders\nContent-Type: application/x-www-form-urlencoded</pre>\n")
                .append("<table class=\"list\"><tr><th>字段</th><th>说明</th></tr>\n")
                .append("<tr><td>emerald / yuan</td><td>价格，正整数，二选一</td></tr>\n")
                .append("<tr><td>item1 … item16</td><td>别的物品，如 <code>minecraft:wool@14</code>；<code>itemN_count</code> 个数（默认 1），<code>itemN_nbt</code> SNBT</td></tr>\n")
                .append("<tr><td>return_url</td><td>必填。付完回哪里（http/https，主机就是付款页上写的网站），带 <code>?order=单号&amp;status=paid</code></td></tr>\n")
                .append("<tr><td>cancel_url</td><td>可选，同主机，玩家点“返回网站”时去，带 <code>status=canceled</code></td></tr>\n")
                .append("<tr><td>description</td><td>可选，最多 60 字</td></tr>\n")
                .append("<tr><td>reference</td><td>可选，你的单号，最多 64 个可见 ASCII 字符</td></tr>\n")
                .append("<tr><td>expires_in</td><td>可选，秒，60–").append(PayConfig.orderMaxTtlSeconds).append("，默认 ").append(PayConfig.orderTtlSeconds).append("</td></tr>\n")
                .append("<tr><td>payer_uuid</td><td>可选，只许这个玩家付（别的角色登录时付款页不给付款按钮）</td></tr>\n")
                .append("</table>\n<p>回 <code>201</code>：<code>id</code>、<code>url</code>、<code>status</code>、<code>site</code>、<code>currency</code>、<code>amount</code>、"
                        + "<code>amount_yuan</code>、<code>items</code>、<code>created</code>、<code>expires_at</code>（Unix 秒）。</p>\n");
        b.append("<h2>查询收款单</h2>\n<pre>GET /api/v1/orders/单号</pre>\n")
                .append("<p><code>status</code>：<code>open</code>、<code>paying</code>、<code>paid</code>、<code>canceled</code>、<code>expired</code>。"
                        + "付了以后多 <code>paid_at</code>、<code>payment</code>、<code>payer_uuid</code>、<code>payer_account</code>、<code>payer_name</code>、<code>refunded_yuan</code>、<code>refunded_items</code>。"
                        + "模组用虚拟账户的余额付的（如清替玩家下单）没有 <code>payer_uuid</code>，<code>payer_account</code> 是 <code>virtual:模组 ID:名字</code>，"
                        + "<code>payer_name</code> 是它的显示名。要送货给谁按你自己的单子定，不要按付款人。</p>\n");
        b.append("<h2>其他</h2>\n<table class=\"list\">\n")
                .append("<tr><td><code>POST /api/v1/orders/单号/cancel</code></td><td>取消还没付的单（付过的回 409）</td></tr>\n")
                .append("<tr><td><code>GET /api/v1/payments/付款号</code></td><td>查一笔付款（金额、已退）</td></tr>\n")
                .append("<tr><td><code>GET /api/v1/balance</code></td><td>你账户的余额（<code>yuan</code>、<code>items</code>）</td></tr>\n")
                .append("</table>\n<p>退款在账户页做（全额或部分，按价值），退进付款人的 pay.yr 账户。</p>\n");
        return b.toString();
    }

    private static String errors() {
        String[][] rows = {
                {"401", "invalid_key", "密钥不对"},
                {"429", "too_many_requests", "太频繁"},
                {"400", "invalid_return_url", "没给 return_url，或写法不对"},
                {"400", "invalid_amount", "价格、数量不对（零、太大、emerald 与 yuan 都给了……）"},
                {"400", "invalid_itemN / invalid_itemN_nbt", "物品写法、SNBT 不对"},
                {"400", "invalid_item", "没有这种物品，或 NBT 记不了账"},
                {"400", "item_not_allowed", "黑名单里的物品、装着东西的容器"},
                {"400", "invalid_site / invalid_cancel_url", "网站域名、取消网址不对"},
                {"400", "invalid_description / invalid_reference / invalid_expires", "说明、单号、有效期不对"},
                {"400", "invalid_payer_uuid / self_payment", "指定的付款人不对 / 就是收款方"},
                {"400", "too_many_orders", "能付的单太多（每个收款方最多 " + PayConfig.maxOpenOrders + " 张）"},
                {"404", "no_such_order / no_such_payment", "没有，或不是你的"},
                {"409", "order_not_open", "单子不能取消（付过、过期、已取消）"},
                {"405", "method_not_allowed", "方法不对"},
                {"503", "unavailable", "pay.yr 暂时不可用"},
        };
        StringBuilder b = new StringBuilder("<p>出错时回 4xx / 5xx，正文 <code>{\"error\":代号,\"message\":中文说明}</code>。</p>\n"
                + "<table class=\"list\"><tr><th>HTTP</th><th>error</th><th>意思</th></tr>\n");
        for (String[] r : rows) {
            b.append("<tr><td>").append(r[0]).append("</td><td><code>").append(esc(r[1])).append("</code></td><td>").append(esc(r[2])).append("</td></tr>\n");
        }
        return b.append("</table>\n").toString();
    }

    private static String examples(String base) {
        return "<h2>开单</h2>\n<pre>POST " + esc(base) + "/api/v1/orders\nAuthorization: Bearer yrsk_…\nContent-Type: application/x-www-form-urlencoded\n\n"
                + "emerald=20&amp;description=钻石剑&amp;reference=A-123&amp;return_url=https://shop.yr/done</pre>\n"
                + "<pre>HTTP/1.1 201 Created\n{\"id\":\"ord_…\",\"status\":\"open\",\"url\":\"" + esc(base) + "/c/ord_…\",\"amount\":20,\"amount_yuan\":40,…}</pre>\n"
                + "<h2>核对（纯文本，给 Z80 程序）</h2>\n<pre>GET " + esc(base) + "/api/v1/orders/ord_…?format=text\nAuthorization: Bearer yrsk_…\n\n"
                + "id=ord_…\nstatus=paid\npayer_name=Alice\n…</pre>\n"
                + "<h2>以物易物</h2>\n<pre>item1=minecraft:diamond&amp;item1_count=2&amp;emerald=1&amp;return_url=https://shop.yr/done</pre>\n"
                + "<h2>模组（服务端 Java）</h2>\n<pre>OrderInfo o = PayApi.createOrder(new OrderSpec(AccountRef.virtual(\"mymod:shop\"), \"shop.yr\")\n"
                + "        .emeralds(20).description(\"钻石剑\").reference(\"A-123\")\n"
                + "        .payer(buyerUuid).returnUrl(\"https://shop.yr/done\"));\n"
                + "PayApi.addListener(AccountRef.virtual(\"mymod:shop\"), (order, payment) -&gt; { /* 发货 */ });\n"
                + "PayApi.setDisplayName(\"mymod:shop\", \"我的小店\");</pre>\n"
                + "<p class=\"hint\">模组收款进虚拟账户，不用密钥；回调在服务端主线程上。显示名是付款页、确认框、收支记录里给人看的名字（不起时显示登记名）。"
                + "<code>reference</code> 不显示在付款页上。</p>\n"
                + "<h2>模组付款：付款入口与付款请求</h2>\n<pre>PaymentInfo p = PayApi.pay(new PaySpec(\"ord_…\", AccountRef.virtual(\"mymod:shop\"))\n"
                + "        .expect(expectedPrice).requestId(\"req-123\"));\n"
                + "// 同 PayApi.virtualAccount(\"mymod:shop\").pay(\"ord_…\", expectedPrice, \"req-123\")</pre>\n"
                + "<p class=\"hint\"><code>PayApi.pay</code> 是唯一的付款入口（玩家在付款页确认以后也是它付的），在 Java 里调用就是授权、不弹确认框。"
                + "规则同玩家付款（单子要开着、没过期、不能付给自己），虚拟账户从余额里出，不够时抛 <code>insufficient_balance</code>；"
                + "<code>expect</code> 不为 null 时要和价格一样。同一个请求编号再付只付一次，返回第一次的付款。收款方照常收到回调，退款退回付款的账户。</p>\n"
                + "<p class=\"hint\">付款页上有人要用某个账户付时，pay.yr 发付款请求：玩家账户由 YouranMod 弹确认框；虚拟账户交给它的模组用 "
                + "<code>PayApi.setRequestHandler(\"mymod\", handler)</code> 登记的处理者（可选）。没有处理者时什么都不发生，单子照旧开着，以后调 "
                + "<code>PayApi.pay</code> 照样能付。</p>\n";
    }

    private static String help() {
        return "<ul>\n"
                + "<li>付款前用游戏角色登录（会弹系统确认框）。登录跟着电脑走：同一台电脑上的下一个人看得到，用完请退出。</li>\n"
                + "<li>点“付款”后，页面跳到 id.yr，游戏弹出系统确认框，写着付给谁、拿出什么、用多少余额、是谁的账户；确认了才扣。确认框弹给正在操作电脑的人，扣的是登录的角色的。"
                + "登录时同意的“允许这个网站弹出确认框”就是这一项。</li>\n"
                + "<li>付款从背包（主背包、快捷栏、副手）拿，也可以用 pay.yr 余额付一部分或全部。绿宝石与悠然元按 1 : 2 互换，找零给悠然元。</li>\n"
                + "<li>余额：收款、退款、没送到的找零记在账户里。账户页可以提现成绿宝石或悠然元，放进账户主人的背包（要在线），放不下的掉在脚边。</li>\n"
                + "<li>退款只由收款方发起，退进付款人的 pay.yr 账户。pay.yr 不裁决纠纷。</li>\n"
                + "</ul>\n"
                + "<h2 id=\"verify\">操作前必须验证身份</h2>\n"
                + "<p>默认关闭：坐在已登录的电脑前的人确认就能付款、提现（和真电脑一样，用完请退出）。在“我的账户 → 安全”里打开以后，付款、提现、退款、重置密钥前，"
                + "id.yr 的确认框会问“确认你是 X”，只有你本人同意才执行；别人坐在前面同意了也不算。</p>\n"
                + "<p>打开时 id.yr 弹框问是否允许 pay.yr 验证身份，同意就打开；关闭要你本人验证身份（别人关不掉）。验证身份由 id.yr 做，pay.yr 只知道通过了没有。"
                + "重新登录以后第一次要验证时，会先问一次是否允许验证身份。</p>\n"
                + "<p>没通过时页面上点“重新验证”再弹一次框，填的内容不变。框没弹出来时，点 id.yr 页面上的“点这里重新弹出”。</p>\n";
    }
}
