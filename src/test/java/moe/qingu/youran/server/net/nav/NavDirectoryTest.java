package moe.qingu.youran.server.net.nav;

import moe.qingu.youran.api.network.SiteInfo;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** 导航登记与搜索 */
class NavDirectoryTest {
    static final Set<String> DNS = new HashSet<>(Arrays.asList("minebay.yr", "pay.yr", "portal.yr", "platform.180.yr", "wood.yr", "captcha.yr",
            "xn--0zwm56d.yr", "dado.yr"));

    static NavDirectory sample(List<String> problems) {
        List<NavDirectory.Decl> decls = new ArrayList<>();
        decls.add(new NavDirectory.Decl("portal.yr", SiteInfo.of("悠然面板", SiteInfo.Category.SERVICE, "公告、消息").featured(), true));
        decls.add(new NavDirectory.Decl("minebay.yr", SiteInfo.of("MineBay", SiteInfo.Category.SHOPPING, "网上商城，买家具").featured(), false));
        // 后登记的同名不算
        decls.add(new NavDirectory.Decl("minebay.yr", SiteInfo.of("假 MineBay", SiteInfo.Category.SHOPPING, ""), false));
        // 非兜底的顶替兜底的
        decls.add(new NavDirectory.Decl("portal.yr", SiteInfo.of("悠然", SiteInfo.Category.SERVICE, "网页面板").featured(), false));
        decls.add(new NavDirectory.Decl("pay.yr", SiteInfo.of("pay.yr 支付", SiteInfo.Category.SERVICE, "付款、收款").featured(), false));
        decls.add(new NavDirectory.Decl("captcha.yr", SiteInfo.of("人机验证", SiteInfo.Category.TOOL, "验证码"), false));
        // DNS 里没有：不显示
        decls.add(new NavDirectory.Decl("ghost.yr", SiteInfo.of("幽灵", SiteInfo.Category.TOOL, ""), false));
        String[] extra = {"*wood.yr|玩家网站|老王木材|整组卖橡木", "测试.yr|tool|测试站|", "bad line", "x.yr|不知道|名字|", ""};
        String[] hidden = {"captcha.yr", "nothing.yr"};
        return NavDirectory.build(decls, extra, hidden, DNS::contains, problems);
    }

    @Test
    void build() {
        List<String> problems = new ArrayList<>();
        NavDirectory d = sample(problems);
        List<String> names = d.all().stream().map(s -> s.domain).collect(Collectors.toList());
        assertEquals(Arrays.asList("portal.yr", "minebay.yr", "pay.yr", "wood.yr", "xn--0zwm56d.yr"), names);
        assertEquals("悠然", d.all().get(0).info.title);
        assertEquals("MineBay", d.all().get(1).info.title);
        assertEquals("测试.yr", d.all().get(4).display);
        // 名站：按分栏顺序（购物、服务、工具、玩家网站）
        assertEquals(Arrays.asList("minebay.yr", "portal.yr", "pay.yr", "wood.yr"), d.featured().stream().map(s -> s.domain).collect(Collectors.toList()));
        assertEquals(1, d.byCategory().get(SiteInfo.Category.TOOL).size());
        assertEquals(1, d.byCategory().get(SiteInfo.Category.PLAYER).size());
        assertEquals(3, problems.size(), problems.toString()); // bad line、分类不认识、nothing.yr
    }

    @Test
    void search() {
        NavDirectory d = sample(new ArrayList<>());
        assertEquals("minebay.yr", d.search("minebay", 10).get(0).domain);
        assertEquals("minebay.yr", d.search("  MINEBAY  ", 10).get(0).domain);
        // 一句话里的词
        assertEquals("wood.yr", d.search("橡木", 10).get(0).domain);
        // 每个词都要有
        assertEquals(1, d.search("老王 橡木", 10).size());
        assertEquals(0, d.search("老王 钻石", 10).size());
        // 名字里有的排在只在一句话里有的前面
        List<NavDirectory.Site> r = d.search("pay", 10);
        assertEquals("pay.yr", r.get(0).domain);
        // 域名（含 Unicode 写法）
        assertEquals("xn--0zwm56d.yr", d.search("测试.yr", 10).get(0).domain);
        assertEquals("xn--0zwm56d.yr", d.search("xn--0zwm56d", 10).get(0).domain);
        // 分栏名
        assertEquals(1, d.search("购物", 10).size());
        assertEquals(0, d.search("", 10).size());
        assertEquals(0, d.search(null, 10).size());
        assertEquals(1, d.search("yr", 1).size());
        assertEquals(NavDirectory.MAX_QUERY, NavDirectory.normalizeQuery(new String(new char[200]).replace('\0', 'a')).length());
    }

    @Test
    void extraLines() {
        NavDirectory.Decl d = NavDirectory.parseExtra("*Wood.YR | 购物 | 木材 | 便宜");
        assertEquals("wood.yr", d.domain);
        assertTrue(d.info.featured);
        assertEquals(SiteInfo.Category.SHOPPING, d.info.category);
        assertThrows(IllegalArgumentException.class, () -> NavDirectory.parseExtra("evil.net|购物|x|y"));
        assertThrows(IllegalArgumentException.class, () -> NavDirectory.parseExtra("a.yr|购物|" + new String(new char[40]).replace('\0', '字') + "|y"));
        assertThrows(IllegalArgumentException.class, () -> NavDirectory.parseExtra("a.yr|购物|§c红字|y"));
    }

    @Test
    void siteInfo() {
        assertThrows(IllegalArgumentException.class, () -> SiteInfo.of("", SiteInfo.Category.TOOL, ""));
        assertThrows(IllegalArgumentException.class, () -> SiteInfo.of("a\nb", SiteInfo.Category.TOOL, ""));
        assertThrows(IllegalArgumentException.class, () -> SiteInfo.of("a", null, ""));
        assertEquals("/shop", SiteInfo.of("a", SiteInfo.Category.TOOL, null).path("/shop").path);
        assertThrows(IllegalArgumentException.class, () -> SiteInfo.of("a", SiteInfo.Category.TOOL, "").path("//evil"));
        assertEquals(SiteInfo.Category.PLAYER, SiteInfo.Category.of("玩家网站"));
        assertEquals(SiteInfo.Category.PLAYER, SiteInfo.Category.of("Player"));
    }
}
