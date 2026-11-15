package moe.qingu.youran.server.net.nav;

import moe.qingu.youran.api.network.DomainNames;
import moe.qingu.youran.api.network.SiteInfo;

import java.util.ArrayList;
import java.util.Collections;
import java.util.EnumMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.function.Predicate;

/**
 * 180.yr 上的网站目录（开服时建好，之后不变，任意线程读）：模组登记的导航信息（{@code YouranNetworkApi.registerNavigation}）、
 * YouranMod 替别人写的兜底项、配置里另加的，去掉配置里隐藏的和在 DNS 里查不到的。搜索按名字、域名、一句话。
 */
final class NavDirectory {
    /** 一个网站 */
    static final class Site {
        /** 域名（A-label，DNS 里用的） */
        final String domain;
        /** 给人看的域名（国际化域名显示 Unicode） */
        final String display;
        final SiteInfo info;

        Site(String domain, SiteInfo info) {
            this.domain = domain;
            this.display = DomainNames.toDisplay(domain);
            this.info = info;
        }
    }

    /** 一条导航信息的来源 */
    static final class Decl {
        final String domain;
        final SiteInfo info;
        /** 兜底：同一个域名有别的（非兜底）登记时让给它 */
        final boolean fallback;

        Decl(String domain, SiteInfo info, boolean fallback) {
            this.domain = domain;
            this.info = info;
            this.fallback = fallback;
        }
    }

    private final List<Site> sites;

    private NavDirectory(List<Site> sites) {
        this.sites = Collections.unmodifiableList(sites);
    }

    static NavDirectory of(List<Site> sites) {
        return new NavDirectory(new ArrayList<>(sites));
    }

    /**
     * @param decls  模组登记的（按登记顺序；同一域名先登记的算，兜底的让给非兜底的）
     * @param extra  配置里另加的（{@code [*]域名|分类|名字|一句话}，覆盖模组登记的）
     * @param hidden 配置里隐藏的域名
     * @param exists 域名（A-label）在 DNS 里查不查得到
     */
    static NavDirectory build(List<Decl> decls, String[] extra, String[] hidden, Predicate<String> exists, List<String> problems) {
        Map<String, Decl> byName = new LinkedHashMap<>();
        for (Decl d : decls) {
            Decl old = byName.get(d.domain);
            if (old == null || (old.fallback && !d.fallback)) {
                byName.put(d.domain, d);
            }
        }
        for (String line : extra) {
            if (line == null || line.trim().isEmpty()) {
                continue;
            }
            try {
                Decl d = parseExtra(line);
                byName.put(d.domain, d);
            } catch (IllegalArgumentException e) {
                problems.add("nav.extraSites 里这一行不对（" + e.getMessage() + "）：" + line);
            }
        }
        for (String h : hidden) {
            if (h == null || h.trim().isEmpty()) {
                continue;
            }
            String n = DomainNames.tryToAscii(h.trim().toLowerCase(Locale.ROOT));
            if (n == null || byName.remove(n) == null) {
                problems.add("nav.hiddenSites 里的 " + h + " 本来就不在导航上");
            }
        }
        List<Site> out = new ArrayList<>();
        for (Decl d : byName.values()) {
            if (exists.test(d.domain)) {
                out.add(new Site(d.domain, d.info));
            }
        }
        return new NavDirectory(out);
    }

    /** 配置里的一行：{@code [*]域名|分类|名字|一句话} */
    static Decl parseExtra(String line) {
        String[] p = line.split("\\|", -1);
        if (p.length < 3 || p.length > 4) {
            throw new IllegalArgumentException("要写成 域名|分类|名字|一句话");
        }
        String domain = p[0].trim();
        boolean featured = domain.startsWith("*");
        if (featured) {
            domain = domain.substring(1).trim();
        }
        String n = DomainNames.tryToAscii(domain.toLowerCase(Locale.ROOT));
        if (n == null || !(n.endsWith(".yr") || n.endsWith(".com")) || !n.matches("[a-z0-9]([a-z0-9.-]*[a-z0-9])?") || n.contains("..")) {
            throw new IllegalArgumentException("域名不对");
        }
        SiteInfo.Category c = SiteInfo.Category.of(p[1]);
        if (c == null) {
            throw new IllegalArgumentException("分类不认识");
        }
        SiteInfo info = SiteInfo.of(p[2], c, p.length > 3 ? p[3] : "");
        return new Decl(n, featured ? info.featured() : info, false);
    }

    List<Site> all() {
        return sites;
    }

    /** “名站导航”格子：按分栏顺序，同一分栏按登记顺序 */
    List<Site> featured() {
        List<Site> out = new ArrayList<>();
        for (SiteInfo.Category c : SiteInfo.Category.values()) {
            for (Site s : sites) {
                if (s.info.featured && s.info.category == c) {
                    out.add(s);
                }
            }
        }
        return out;
    }

    /** 按分栏分好（每栏按登记顺序；没有网站的分栏也在，是空表） */
    Map<SiteInfo.Category, List<Site>> byCategory() {
        Map<SiteInfo.Category, List<Site>> m = new EnumMap<>(SiteInfo.Category.class);
        for (SiteInfo.Category c : SiteInfo.Category.values()) {
            m.put(c, new ArrayList<Site>());
        }
        for (Site s : sites) {
            m.get(s.info.category).add(s);
        }
        return m;
    }

    /** 搜索词最多几个字 */
    static final int MAX_QUERY = 64;

    /**
     * 搜索：按空白分词，每个词都要在名字、域名（两种写法）、一句话、分栏名里出现（不分大小写）。名字里有的排前面，其次域名，其次一句话；
     * 一样时名站在前、再按登记顺序。
     */
    List<Site> search(String query, int max) {
        String q = normalizeQuery(query);
        List<Site> out = new ArrayList<>();
        if (q.isEmpty()) {
            return out;
        }
        String[] words = q.split(" ");
        List<int[]> scored = new ArrayList<>();
        for (int i = 0; i < sites.size(); i++) {
            Site s = sites.get(i);
            String title = s.info.title.toLowerCase(Locale.ROOT);
            String domains = (s.domain + " " + s.display).toLowerCase(Locale.ROOT);
            String rest = (s.info.summary + " " + s.info.category.label).toLowerCase(Locale.ROOT);
            int score = 0;
            boolean all = true;
            for (String w : words) {
                if (title.contains(w)) {
                    score += 100;
                } else if (domains.contains(w)) {
                    score += 10;
                } else if (rest.contains(w)) {
                    score += 1;
                } else {
                    all = false;
                    break;
                }
            }
            if (all) {
                scored.add(new int[]{score * 2 + (s.info.featured ? 1 : 0), i});
            }
        }
        scored.sort((a, b) -> a[0] != b[0] ? Integer.compare(b[0], a[0]) : Integer.compare(a[1], b[1]));
        for (int[] x : scored) {
            if (out.size() >= max) {
                break;
            }
            out.add(sites.get(x[1]));
        }
        return out;
    }

    /** 去掉首尾空白、把连续空白并成一个、转小写、截到 {@value #MAX_QUERY} 个字 */
    static String normalizeQuery(String query) {
        if (query == null) {
            return "";
        }
        String q = query.trim().replaceAll("\\s+", " ").toLowerCase(Locale.ROOT);
        if (q.codePointCount(0, q.length()) > MAX_QUERY) {
            q = q.substring(0, q.offsetByCodePoints(0, MAX_QUERY)).trim();
        }
        return q;
    }
}
