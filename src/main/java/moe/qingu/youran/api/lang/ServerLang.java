package moe.qingu.youran.api.lang;

import net.minecraft.item.ItemStack;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import javax.annotation.Nullable;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.IllegalFormatException;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.Function;
import java.util.function.Supplier;

/**
 * 服务端翻译：服务端本来只有英文（没有原版的中文语言文件，也不读模组 jar 里的别的语言），这里另读一份，给只在服务端用的
 * 名字（搜索、排序、发给 LLM 的名字等）用。玩家看到的文字照旧发翻译键、由玩家的客户端翻译。
 * <p>
 * 一个键按优先级从高到低查（规则同 YouranAPI 的 {@code Translations}，语言目录两边共用一份）：
 * <ol>
 * <li>语言目录（{@code config/youranmod.cfg} 的 {@code lang.dir}，默认 {@code config/youranapi/lang}）：其中的 {@code .lang} 文件与
 * 资源包（文件夹或 zip，读其中所有语言的 {@code assets/*}{@code /lang/<语言>.lang}），按文件名排序，同一个键以后面的为准；</li>
 * <li>所有已加载模组的 jar（开发环境里是文件夹）里的 {@code assets/*}{@code /lang/<语言>.lang}；</li>
 * <li>英文：上面两处的 {@code en_us.lang}，再是服务端自己的翻译（{@code I18n}）；</li>
 * <li>键名。</li>
 * </ol>
 * 所有语言都读，{@link #find}、{@link #get}、{@link #format} 支持任意语言代码（{@code xx_yy}，不分大小写）；{@link #names}、{@link #itemNames}
 * 固定取 {@link #NAME_LOCALES}（英文、简体、繁体）。语言目录里直接放的 {@code .lang} 文件由文件名决定语言：
 * {@code <语言>.lang} 或 {@code <语言>-<任意>.lang}（如 {@code zh_cn.lang}、{@code zh_tw.lang}、{@code zh_cn-覆盖.lang}），
 * 文件名对不上的记警告、跳过；同一种语言内按文件名排序（不含扩展名，{@code zh_cn.lang} 在 {@code zh_cn-覆盖.lang} 前面），后面的覆盖前面的。
 * 与原版一致，查不到时只退回 {@code en_us}，不在简繁之间互相回退。规则同 YouranAPI。
 * <p>
 * 开服前（{@code FMLServerAboutToStartEvent}）YouranMod 在后台线程加载，加载完成后只读；加载完成前查到的只有服务端英文与键名。
 * 要等中文名的调用方用 {@link #isLoaded()}、{@link #onLoaded(Runnable)} 或 {@link #version()}（加载完成的次数，适合做缓存的键）。
 * 坏文件只记警告、跳过，不影响开服。
 * <p>
 * 只在服务端有意义。读文件的实现只在 YouranMod 的完整包里（{@code server.lang}，按类名加载）；客户端包里这个类照样在
 * （两端共用的代码可以放心引用），但什么都不读：查到的只有服务端英文与键名，{@link #isLoaded()} 在第一次 {@link #reload()} 后为 true。
 * 所有方法都可以在任意线程调用。
 */
public final class ServerLang {
    public static final String EN_US = "en_us";
    public static final String ZH_CN = "zh_cn";
    public static final String ZH_TW = "zh_tw";
    /** {@link #names(String...)}、{@link #itemNames} 取的语言，按这个顺序（英文、简体、繁体）；其他接口支持任意语言代码 */
    public static final List<String> NAME_LOCALES = Collections.unmodifiableList(Arrays.asList(EN_US, ZH_CN, ZH_TW));

    /** 读文件的实现（只在完整包里；客户端包里找不到时安静跳过） */
    static final String LOADER = "moe.qingu.youran.server.lang.ServerLangLoader";
    private static final Logger LOG = LogManager.getLogger("YouranMod");

    private static volatile LangSource source = LangSource.EMPTY;
    private static volatile boolean loaded;
    private static volatile int version;
    private static final List<Runnable> LISTENERS = new CopyOnWriteArrayList<>();
    /** 第几次开始加载（只认最后一次开始的那次的结果）；用 ServerLang.class 同步 */
    private static int generation;
    /** 服务端自己的英文；测试里换掉 */
    static volatile Function<String, String> serverEnglish = ServerLang::i18n;

    private ServerLang() {
    }

    // ---------------------------------------------------------------- 加载

    /** 至少加载完成过一次（重新加载期间仍为 true，用的是上一次的表，新表加载完成后才换上） */
    public static boolean isLoaded() {
        return loaded;
    }

    /** 加载完成的次数（每次加载、重新加载完成加一）；可以拿来当缓存的键 */
    public static int version() {
        return version;
    }

    /** 当前这份表各来源的条数（日志用） */
    public static String summary() {
        return source.summary();
    }

    /**
     * 登记加载完成时的回调：以后每次加载完成（含重新加载）都调用一次，在加载线程上调用（不是主线程，不要在里面碰世界）。
     * 登记时已经加载完成过的，马上在当前线程先调用一次（恰好碰上加载完成时可能多调一次，回调要能重复调用，如清缓存）。
     * 回调抛的异常记日志，不影响别的回调。
     */
    public static void onLoaded(Runnable callback) {
        if (callback == null) {
            throw new NullPointerException("callback");
        }
        LISTENERS.add(callback);
        if (loaded) {
            run(callback);
        }
    }

    /**
     * 开始（重新）加载：在后台线程上读语言目录与模组 jar，完成后换上新表、调用回调。马上返回，可以在主线程调用。
     * YouranMod 在每次开服前自己调用一次；语言目录里的文件改了以后可以再调。
     */
    public static void reload() {
        Supplier<LangSource> loader = createLoader();
        if (loader == null) {
            finish(nextGeneration(), LangSource.EMPTY);
            return;
        }
        start(loader, true);
    }

    /** 测试用：用给定的加载器加载；background 为 false 时在当前线程上加载完才返回 */
    static void start(Supplier<LangSource> loader, boolean background) {
        final int mine = nextGeneration();
        Runnable task = () -> {
            LangSource loadedSource;
            try {
                loadedSource = loader.get();
            } catch (RuntimeException | LinkageError e) {
                LOG.warn("服务端翻译加载失败，沿用原来的表", e);
                loadedSource = null;
            }
            finish(mine, loadedSource);
        };
        if (!background) {
            task.run();
            return;
        }
        Thread t = new Thread(task, "YouranMod-lang");
        t.setDaemon(true);
        t.start();
    }

    private static synchronized int nextGeneration() {
        return ++generation;
    }

    /** 换上加载好的表（null = 加载失败，沿用原来的）；不是最后一次开始的加载就丢掉 */
    private static void finish(int mine, @Nullable LangSource loadedSource) {
        synchronized (ServerLang.class) {
            if (mine != generation) {
                return;
            }
            if (loadedSource != null) {
                source = loadedSource;
            }
            loaded = true;
            version++;
        }
        if (loadedSource != null && loadedSource != LangSource.EMPTY) {
            LOG.info("服务端翻译：{}", loadedSource.summary());
        }
        for (Runnable r : LISTENERS) {
            run(r);
        }
    }

    private static void run(Runnable r) {
        try {
            r.run();
        } catch (RuntimeException | LinkageError e) {
            LOG.warn("服务端翻译加载完成的回调出错", e);
        }
    }

    private static boolean loaderMissingLogged;

    @Nullable
    @SuppressWarnings("unchecked")
    private static synchronized Supplier<LangSource> createLoader() {
        try {
            return (Supplier<LangSource>) Class.forName(LOADER).getDeclaredConstructor().newInstance();
        } catch (ClassNotFoundException e) {
            if (!loaderMissingLogged) {
                loaderMissingLogged = true;
                LOG.info("这是 YouranMod 的客户端包，没有服务端翻译（只有服务端英文）");
            }
        } catch (ReflectiveOperationException | LinkageError | ClassCastException e) {
            LOG.error("加载服务端翻译的实现失败，只有服务端英文", e);
        }
        return null;
    }

    /** 测试用：回到没加载过的样子 */
    static synchronized void resetForTest() {
        source = LangSource.EMPTY;
        loaded = false;
        version = 0;
        generation++;
        LISTENERS.clear();
        serverEnglish = ServerLang::i18n;
    }

    // ---------------------------------------------------------------- 查

    /** 语言代码统一成小写、下划线（{@code zh_CN}、{@code zh-cn} 都当 {@code zh_cn}） */
    static String normalize(String locale) {
        return locale == null ? EN_US : locale.trim().replace('-', '_').toLowerCase(Locale.ROOT);
    }

    /**
     * 这个语言里这个键的值，不回退到别的语言：语言目录 → 模组 jar；{@code en_us} 最后再查服务端自己的翻译。都没有时为 null。
     * 值是格式串（可能带 {@code %s}）。
     */
    @Nullable
    public static String find(String locale, String key) {
        if (key == null) {
            return null;
        }
        String loc = normalize(locale);
        String v = source.find(loc, key);
        if (v == null && loc.equals(EN_US)) {
            v = serverEnglish.apply(key);
        }
        return v;
    }

    /** 这个语言里这个键的值：查不到时用英文（{@link #find} 的 {@code en_us}），再查不到是键名。不会返回 null */
    public static String get(String locale, String key) {
        if (key == null) {
            return "";
        }
        String v = find(locale, key);
        if (v == null && !normalize(locale).equals(EN_US)) {
            v = find(EN_US, key);
        }
        return v != null ? v : key;
    }

    /** {@link #get} 再填参数（{@code String.format}）；格式串和参数对不上时返回没填参数的格式串 */
    public static String format(String locale, String key, Object... args) {
        return fill(get(locale, key), args);
    }

    static String fill(String pattern, Object... args) {
        if (args == null || args.length == 0) {
            return pattern;
        }
        try {
            return String.format(Locale.ROOT, pattern, args);
        } catch (IllegalFormatException e) {
            return pattern;
        }
    }

    /**
     * 这些键的各种名字：每个键依次取英文、简体、繁体（{@link #find}，不回退），去掉首尾空白、去重，查不到的和空的不放。
     * 键可以是 null（跳过）。给要“认得各种叫法”的地方用，如 SCP-294 发给 LLM 的候选名。
     */
    public static List<String> names(String... keys) {
        Set<String> out = new LinkedHashSet<>();
        if (keys != null) {
            for (String key : keys) {
                if (key == null) {
                    continue;
                }
                for (String loc : NAME_LOCALES) {
                    add(out, find(loc, key));
                }
            }
        }
        return new ArrayList<>(out);
    }

    private static void add(Set<String> out, @Nullable String name) {
        if (name != null && !name.trim().isEmpty()) {
            out.add(name.trim());
        }
    }

    // ---------------------------------------------------------------- 物品

    /**
     * 物品的显示名（不管改过的名字），按游戏里 {@code getItemStackDisplayName} 的规则用这个语言的翻译算：药水（含喷溅、滞留、
     * 药箭）按药水类型，附魔书带上附魔（只有一个附魔时，如“附魔书（锋利 V）”），刷怪蛋带上生物（“生成 马”），玩家头颅带上名字，
     * 成书用书名，旗帜、盾牌按颜色，其余按翻译键 + {@code .name}。这个语言里查不到时为 null；{@code en_us} 最后用物品自己在
     * 服务端的名字。
     */
    @Nullable
    public static String findItemName(String locale, ItemStack stack) {
        return ItemNames.find(normalize(locale), stack);
    }

    /** {@link #findItemName}；查不到时用英文，再查不到是翻译键 + {@code .name}。不会返回 null */
    public static String itemName(String locale, ItemStack stack) {
        String v = findItemName(locale, stack);
        if (v == null && !normalize(locale).equals(EN_US)) {
            v = findItemName(EN_US, stack);
        }
        return v != null ? v : ItemNames.fallbackKey(stack);
    }

    /** 物品的各种名字：英文、简体、繁体（{@link #findItemName}），去重，查不到的不放 */
    public static List<String> itemNames(ItemStack stack) {
        Set<String> out = new LinkedHashSet<>();
        for (String loc : NAME_LOCALES) {
            add(out, findItemName(loc, stack));
        }
        return new ArrayList<>(out);
    }

    /** 服务端自己的英文（独立服务端上是 en_us 与各模组的 en_us）；没有这个键时为 null */
    @Nullable
    @SuppressWarnings("deprecation")
    private static String i18n(String key) {
        try {
            return net.minecraft.util.text.translation.I18n.canTranslate(key)
                    ? net.minecraft.util.text.translation.I18n.translateToLocal(key) : null;
        } catch (RuntimeException | LinkageError e) {
            return null;
        }
    }
}
