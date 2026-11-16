package moe.qingu.youran.server.net.nav;

import moe.qingu.youran.api.image.DyePng;

/** help.180.yr：帮助中心（服主 11-15：说明性的文字都放这里，主站与平台只留短提示） */
final class HelpPages {
    private HelpPages() {
    }

    static final String[][] TOPICS = {
            {"/home", "设为主页"},
            {"/ads", "投放广告"},
            {"/rules", "广告规则与审核"},
            {"/report", "举报广告"},
            {"/verify", "操作前必须验证身份"},
            {"/canvas", "画板控件（给网站作者）"},
    };

    static final String[] DYE_NAMES = {"白", "橙", "品红", "淡蓝", "黄", "黄绿", "粉红", "灰", "淡灰", "青", "紫", "蓝", "棕", "绿", "红", "黑"};

    /** 路径对应的页面；没有为 null */
    static String page(String path, String scheme, AdRules rules) {
        switch (path) {
            case "/":
            case "/index.html":
                return index(scheme);
            case "/home":
                return wrap(scheme, "设为主页", home());
            case "/ads":
                return wrap(scheme, "投放广告", ads(scheme, rules));
            case "/rules":
                return wrap(scheme, "广告规则与审核", rules(rules));
            case "/report":
                return wrap(scheme, "举报广告", report());
            case "/canvas":
                return wrap(scheme, "画板控件", canvas());
            case "/verify":
                return wrap(scheme, "操作前必须验证身份", verify());
            default:
                return null;
        }
    }

    private static String index(String scheme) {
        StringBuilder b = new StringBuilder("<h1>帮助中心</h1>\n<table class=\"list\">");
        for (String[] t : TOPICS) {
            b.append("<tr><td><a href=\"").append(t[0]).append("\">").append(t[1]).append("</a></td></tr>");
        }
        b.append("</table>\n<p class=\"gray small\">广告投放：<a href=\"").append(NavPages.esc(NavPages.url(scheme, NavService.PLATFORM, "/")))
                .append("\">platform.180.yr</a></p>\n");
        return NavPages.frame("帮助中心", "帮助中心", null, null, false, scheme, b.toString(), 0, null);
    }

    private static String wrap(String scheme, String title, String body) {
        return NavPages.frame(title, "帮助中心", null, null, false, scheme,
                "<p class=\"small\"><a href=\"/\">帮助中心</a> &gt; " + NavPages.esc(title) + "</p>\n<h1>" + NavPages.esc(title) + "</h1>\n" + body, 0, null);
    }

    private static String verify() {
        return "<p>登录着的电脑，谁坐在前面谁就能用你的帐号投放广告（和真电脑一样，用完请退出）。不想这样，就在 platform.180.yr 首页的“帐号设置”里"
                + "打开“操作前必须验证身份”。</p>\n"
                + "<h3>打开以后</h3>\n<p>投放广告、重新下单，以及管理员的下架、封禁、解封之前，游戏会弹出 id.yr 的确认框“确认你是 X”。只有你本人同意才继续；"
                + "别人坐在前面同意了也不算，填的内容不会丢。</p>\n"
                + "<h3>打开、关闭</h3>\n<p>打开：id.yr 弹框问是否允许 180 验证身份，同意就打开。关闭：要你本人验证身份，别人关不掉。默认关闭。</p>\n"
                + "<h3>没通过</h3>\n<p>页面上点“重新验证”再弹一次框。框没弹出来时，点 id.yr 页面上的“点这里重新弹出”。</p>\n"
                + "<p class=\"gray small\">验证身份由 id.yr 做，180 只知道通过了没有。重新登录以后第一次要验证时，会先问一次是否允许验证身份。</p>\n";
    }

    private static String home() {
        return "<p>电脑开机、按“主页”按钮时打开主页。默认是服务器设的（一般是 180.yr）。</p>\n"
                + "<h3>把当前页设为主页</h3>\n<p>1. 打开想设的网页。<br />2. 点浏览器右上角的“菜单”。<br />3. 点“设为这台电脑的主页”。</p>\n"
                + "<p>主页跟着电脑走：换个人用这台电脑，主页不变。</p>\n"
                + "<h3>恢复默认</h3>\n<p>菜单里点“恢复默认主页”。</p>\n"
                + "<h3>离线主页</h3>\n<p>主页打不开（没网、网站关了）时显示浏览器自带的 <b>about:home</b>。也可以在地址栏输入它。</p>\n";
    }

    private static String ads(String scheme, AdRules rules) {
        StringBuilder b = new StringBuilder();
        b.append("<p>1. 打开 <a href=\"").append(NavPages.esc(NavPages.url(scheme, NavService.PLATFORM, "/"))).append("\">platform.180.yr</a>，用游戏角色登录。<br />")
                .append("2. 选广告位，点“投放”。<br />3. 填天数和链接，画图或写字。<br />4. 点“去付款”，在 pay.yr 付款。<br />5. 付完马上上线；位置满了就排队，轮到时自动上线。</p>\n");
        b.append("<h3>价格</h3>\n<table class=\"list\"><tr><th>广告位</th><th>尺寸</th><th>位置数</th><th>每天</th></tr>");
        for (AdSlot s : AdSlot.values()) {
            b.append("<tr><td>").append(s.label).append("</td><td>").append(s.isImage() ? s.width + "×" + s.height + " 图片" : "最多 " + AdInput.MAX_TEXT + " 个字")
                    .append("</td><td class=\"num\">").append(rules.positions(s)).append("</td><td class=\"num\">").append(rules.emeraldsPerDay.get(s))
                    .append(" 绿宝石</td></tr>");
        }
        b.append("</table>\n<p>绿宝石、悠然元都能付（1 绿宝石 = 2 元）。一条最多买 ").append(rules.maxDays).append(" 天；每个位置在播的后面最多排 ")
                .append(rules.queue).append(" 条。</p>\n");
        b.append("<h3>计时</h3>\n<p>按现实时间算，一天 24 小时；服务器关着时也照走。</p>\n");
        b.append("<h3>画图</h3>\n<p>图片在浏览器里画：点画板放大编辑，16 种颜色，有铅笔、橡皮、油漆桶、直线、取色。只能用这 16 种颜色，不能全空白。</p>\n");
        b.append("<h3>没付款</h3>\n<p>下单后 15 分钟内没付，订单作废、位置让出。可以在“我的广告”里用同样的内容重新下单。</p>\n");
        return b.toString();
    }

    private static String rules(AdRules rules) {
        return "<h3>先上后审</h3>\n<p>付款后马上上线，不提前审核。管理员随时检查，也看举报。</p>\n"
                + "<h3>不能投放</h3>\n<p>违反服务器规则的内容；色情、血腥、辱骂；冒充别人或服务器；骗人的、链接到恶意网站的。</p>\n"
                + "<h3>下架</h3>\n<p>违规的广告会被下架。管理员按情况决定退不退款：不退、退没播的部分、或全退。退款进你在 pay.yr 的账户，自己去提现。</p>\n"
                + "<h3>封禁</h3>\n<p>严重或多次违规的，禁止再投放；正在播和排队的也可能一起下架。</p>\n";
    }

    private static String report() {
        return "<p>每条广告旁边有“举报”。点开选原因，可以补充一句，提交就行，不用登录。</p>\n"
                + "<p>同一台电脑对同一条广告只算一次。管理员会尽快处理。</p>\n";
    }

    private static String canvas() {
        StringBuilder b = new StringBuilder();
        b.append("<p>悠然服务器的电脑浏览器支持一种画板输入框，任何网站的表单都能用（比如留言板让人画图）。</p>\n")
                .append("<h3>写法</h3>\n<pre style=\"background: #f4f7fb; border: 1px solid #dde5ef; padding: 4px;\">")
                .append(NavPages.esc("<input type=\"x-yr-canvas\" name=\"pic\" width=\"125\" height=\"125\" />")).append("</pre>\n")
                .append("<p><b>width</b>、<b>height</b>：像素，1–").append(DyePng.MAX_SIDE).append("，宽 × 高不超过 ").append(DyePng.MAX_PIXELS)
                .append("（不写时 64×64）。<b>value</b>（可选）：初始图，写法同提交的。</p>\n")
                .append("<h3>提交的内容</h3>\n<p>字段值是 <code>data:image/png;base64,</code> 加 PNG 的 base64，随表单一起提交（普通的 application/x-www-form-urlencoded）。</p>\n")
                .append("<p>PNG 是 8 位调色板：0 号透明，1–16 号是下面 16 种颜色。没画的地方透明。</p>\n")
                .append("<h3>16 种颜色</h3>\n<table class=\"list\"><tr><th>号</th><th>颜色</th><th>RGB</th></tr>");
        for (int i = 0; i < DyePng.DYE_RGB.length; i++) {
            String hex = String.format("#%06X", DyePng.DYE_RGB[i]);
            b.append("<tr><td class=\"num\">").append(i + 1).append("</td><td><span style=\"background: ").append(hex)
                    .append("; border: 1px solid #888888;\">　　</span> ").append(DYE_NAMES[i]).append("</td><td>").append(hex).append("</td></tr>");
        }
        b.append("</table>\n<p>网站收到后应自己检查尺寸与颜色（Java 网站可以用 YouranMod 的 <code>DyePng.decode</code>）。</p>\n");
        return b.toString();
    }
}
