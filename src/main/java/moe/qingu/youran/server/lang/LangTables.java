package moe.qingu.youran.server.lang;

import moe.qingu.youran.api.lang.LangSource;
import moe.qingu.youran.api.lang.ServerLang;

import javax.annotation.Nullable;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Enumeration;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.function.Consumer;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;

/**
 * 读好的服务端翻译表（规则见 {@link ServerLang}）：每个语言两层，语言目录与模组 jar。建好之后只读。
 */
public final class LangTables implements LangSource {
    /** 资源包与 jar 里的语言文件：assets/域/lang/语言.lang（语言代码不分大小写，如老写法 zh_CN.lang） */
    private static final Pattern PACK_ENTRY = Pattern.compile("^assets/[^/]+/lang/([a-z]{2,3}_[a-z0-9]{2,4})\\.lang$", Pattern.CASE_INSENSITIVE);
    /** 文件名是语言代码的 .lang（语言目录里直接放的） */
    private static final Pattern LOCALE_NAME = Pattern.compile("^[a-z]{2,3}_[a-z0-9]{2,4}$");

    private final Map<String, Map<String, String>> dir;
    private final Map<String, Map<String, String>> mods;
    private final String summary;

    private LangTables(Map<String, Map<String, String>> dir, Map<String, Map<String, String>> mods, String summary) {
        this.dir = dir;
        this.mods = mods;
        this.summary = summary;
    }

    @Nullable
    @Override
    public String find(String locale, String key) {
        Map<String, String> d = dir.get(locale);
        String v = d == null ? null : d.get(key);
        if (v == null) {
            Map<String, String> m = mods.get(locale);
            v = m == null ? null : m.get(key);
        }
        return v;
    }

    @Override
    public String summary() {
        return summary;
    }

    /** 语言目录里直接放的 .lang 文件算哪个语言：文件名是读的语言代码的算那个语言，是别的语言代码的不读（null），其余算简体 */
    @Nullable
    static String localeOfFile(String fileName, Collection<String> locales) {
        String base = fileName.substring(0, fileName.length() - ".lang".length()).toLowerCase(Locale.ROOT);
        if (locales.contains(base)) {
            return base;
        }
        if (LOCALE_NAME.matcher(base).matches()) {
            return null;
        }
        return locales.contains(ServerLang.ZH_CN) ? ServerLang.ZH_CN : null;
    }

    /**
     * 加载。语言目录不存在时当作空的（不创建）。单个文件读不了时记一条警告、跳过，不影响其他来源。
     *
     * @param langDir 语言目录，可以是 null
     * @param modSources 模组与服务端的 jar 或文件夹（开发环境）；按文件名排序，重复的只读一次
     * @param locales 读哪些语言（小写）
     * @param warn 警告
     */
    public static LangTables load(@Nullable Path langDir, List<Path> modSources, Collection<String> locales, Consumer<String> warn) {
        Map<String, Map<String, String>> dir = tables(locales);
        Map<String, Map<String, String>> mods = tables(locales);
        StringBuilder sb = new StringBuilder("语言目录 ").append(langDir);
        List<String> dirParts = new ArrayList<>();
        List<String> skipped = new ArrayList<>();
        if (langDir == null || !Files.isDirectory(langDir)) {
            sb.append(" 不存在");
        } else {
            Map<String, Path> entries = new TreeMap<>();
            try (DirectoryStream<Path> ds = Files.newDirectoryStream(langDir)) {
                for (Path p : ds) {
                    entries.put(p.getFileName().toString(), p);
                }
            } catch (IOException | RuntimeException e) {
                warn.accept("语言目录 " + langDir + " 读不了（" + e + "），只用模组自带的语言文件");
            }
            for (Map.Entry<String, Path> e : entries.entrySet()) {
                String name = e.getKey();
                Path p = e.getValue();
                String lower = name.toLowerCase(Locale.ROOT);
                Map<String, Integer> counts = new LinkedHashMap<>();
                try {
                    if (Files.isDirectory(p)) {
                        readPackDir(p, locales, dir, counts, warn);
                    } else if (lower.endsWith(".lang")) {
                        String loc = localeOfFile(name, locales);
                        if (loc == null) {
                            skipped.add(name);
                            continue;
                        }
                        counts.put(loc, readFile(p, dir.get(loc)));
                    } else if (lower.endsWith(".zip") || lower.endsWith(".jar")) {
                        readZip(p, locales, dir, counts, warn);
                    } else {
                        skipped.add(name);
                        continue;
                    }
                } catch (IOException | RuntimeException ex) {
                    warn.accept("语言目录：读不了 " + p + "，已跳过（" + ex + "）");
                    continue;
                }
                dirParts.add(name + (counts.isEmpty() ? " 里没有要读的语言文件" : " " + counts(counts)));
            }
            sb.append(' ').append(dirParts.size()).append(" 个来源");
            if (!dirParts.isEmpty()) {
                sb.append("（").append(String.join("、", dirParts)).append("）");
            }
            if (!skipped.isEmpty()) {
                sb.append("，跳过 ").append(String.join("、", skipped));
            }
        }

        Map<String, Path> sorted = new TreeMap<>();
        Set<Path> seen = new LinkedHashSet<>();
        for (Path j : modSources) {
            Path norm = j.toAbsolutePath().normalize();
            if (seen.add(norm)) {
                sorted.put(norm.getFileName() + "|" + norm, norm);
            }
        }
        int modCount = 0;
        Map<String, Integer> modCounts = new LinkedHashMap<>();
        for (Path j : sorted.values()) {
            Map<String, Integer> counts = new LinkedHashMap<>();
            try {
                if (Files.isDirectory(j)) {
                    readPackDir(j, locales, mods, counts, warn);
                } else if (Files.isRegularFile(j)) {
                    readZip(j, locales, mods, counts, warn);
                } else {
                    continue;
                }
            } catch (IOException | RuntimeException ex) {
                warn.accept("读不了 " + j.getFileName() + " 里的语言文件，已跳过（" + ex + "）");
                continue;
            }
            if (!counts.isEmpty()) {
                modCount++;
                counts.forEach((k, v) -> modCounts.merge(k, v, Integer::sum));
            }
        }
        sb.append("；模组 ").append(modCount).append(" 个").append(modCounts.isEmpty() ? "" : " " + counts(modCounts));
        sb.append("；合计");
        for (String loc : locales) {
            Set<String> keys = new java.util.HashSet<>(dir.get(loc).keySet());
            keys.addAll(mods.get(loc).keySet());
            sb.append(' ').append(loc).append(' ').append(keys.size());
        }
        return new LangTables(dir, mods, sb.toString());
    }

    private static Map<String, Map<String, String>> tables(Collection<String> locales) {
        Map<String, Map<String, String>> m = new HashMap<>();
        for (String loc : locales) {
            m.put(loc, new HashMap<>());
        }
        return m;
    }

    private static String counts(Map<String, Integer> counts) {
        StringBuilder sb = new StringBuilder();
        for (Map.Entry<String, Integer> e : counts.entrySet()) {
            sb.append(sb.length() == 0 ? "" : " ").append(e.getKey()).append(' ').append(e.getValue()).append(" 条");
        }
        return sb.toString();
    }

    private static int readFile(Path p, Map<String, String> into) throws IOException {
        try (InputStream in = Files.newInputStream(p)) {
            Map<String, String> m = LangFile.read(in);
            into.putAll(m);
            return m.size();
        }
    }

    /** 资源包文件夹：读 assets/域/lang/语言.lang（按域名、文件名排序）；单个文件坏了记警告、跳过 */
    private static void readPackDir(Path pack, Collection<String> locales, Map<String, Map<String, String>> into,
                                    Map<String, Integer> counts, Consumer<String> warn) throws IOException {
        Path assets = pack.resolve("assets");
        if (!Files.isDirectory(assets)) {
            return;
        }
        Map<String, Path> files = new TreeMap<>();
        try (Stream<Path> domains = Files.list(assets)) {
            for (Path d : (Iterable<Path>) domains::iterator) {
                Path lang = d.resolve("lang");
                if (!Files.isDirectory(lang)) {
                    continue;
                }
                try (Stream<Path> fs = Files.list(lang)) {
                    for (Path f : (Iterable<Path>) fs::iterator) {
                        String rel = "assets/" + d.getFileName() + "/lang/" + f.getFileName();
                        if (Files.isRegularFile(f) && PACK_ENTRY.matcher(rel).matches()) {
                            files.put(rel, f);
                        }
                    }
                }
            }
        }
        for (Map.Entry<String, Path> e : files.entrySet()) {
            String loc = localeOf(e.getKey(), locales);
            if (loc == null) {
                continue;
            }
            try {
                counts.merge(loc, readFile(e.getValue(), into.get(loc)), Integer::sum);
            } catch (IOException | RuntimeException ex) {
                warn.accept("读不了 " + e.getValue() + "，已跳过（" + ex + "）");
            }
        }
    }

    /** zip / jar：读 assets/域/lang/语言.lang（按条目名排序）；单个条目坏了记警告、跳过 */
    private static void readZip(Path zip, Collection<String> locales, Map<String, Map<String, String>> into,
                                Map<String, Integer> counts, Consumer<String> warn) throws IOException {
        try (ZipFile z = new ZipFile(zip.toFile())) {
            Map<String, ZipEntry> entries = new TreeMap<>();
            Enumeration<? extends ZipEntry> en = z.entries();
            while (en.hasMoreElements()) {
                ZipEntry e = en.nextElement();
                if (!e.isDirectory() && PACK_ENTRY.matcher(e.getName()).matches()) {
                    entries.put(e.getName(), e);
                }
            }
            for (ZipEntry e : entries.values()) {
                String loc = localeOf(e.getName(), locales);
                if (loc == null) {
                    continue;
                }
                try (InputStream in = z.getInputStream(e)) {
                    Map<String, String> m = LangFile.read(in);
                    into.get(loc).putAll(m);
                    counts.merge(loc, m.size(), Integer::sum);
                } catch (IOException | RuntimeException ex) {
                    warn.accept("读不了 " + zip.getFileName() + " 里的 " + e.getName() + "，已跳过（" + ex + "）");
                }
            }
        }
    }

    @Nullable
    private static String localeOf(String entry, Collection<String> locales) {
        Matcher m = PACK_ENTRY.matcher(entry);
        if (!m.matches()) {
            return null;
        }
        String loc = m.group(1).toLowerCase(Locale.ROOT);
        return locales.contains(loc) ? loc : null;
    }
}
