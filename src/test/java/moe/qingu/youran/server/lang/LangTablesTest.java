package moe.qingu.youran.server.lang;

import moe.qingu.youran.api.lang.ServerLang;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class LangTablesTest {
    @TempDir
    Path tmp;

    private final List<String> warnings = new ArrayList<>();

    private LangTables load(Path dir, Path... mods) {
        return LangTables.load(dir, Arrays.asList(mods), ServerLang.LOCALES, warnings::add);
    }

    private static void write(Path p, String text) throws IOException {
        Files.createDirectories(p.getParent());
        Files.write(p, text.getBytes(StandardCharsets.UTF_8));
    }

    /** zip：条目名与内容交替 */
    private static void zip(Path p, String... nameAndText) throws IOException {
        Files.createDirectories(p.getParent());
        try (ZipOutputStream z = new ZipOutputStream(Files.newOutputStream(p))) {
            for (int i = 0; i < nameAndText.length; i += 2) {
                z.putNextEntry(new ZipEntry(nameAndText[i]));
                z.write(nameAndText[i + 1].getBytes(StandardCharsets.UTF_8));
                z.closeEntry();
            }
        }
    }

    @Test
    void langDirBeatsModsAndModsBeatNothing() throws IOException {
        Path dir = tmp.resolve("lang");
        write(dir.resolve("zh_cn.lang"), "item.beef.cooked.name=牛排\n");
        Path mod = tmp.resolve("mods/a.jar");
        zip(mod, "assets/minecraft/lang/zh_cn.lang", "item.beef.cooked.name=熟牛肉\nitem.only.mod.name=模组里的\n",
                "assets/minecraft/lang/en_us.lang", "item.beef.cooked.name=Steak\n");
        LangTables t = load(dir, mod);
        assertEquals("牛排", t.find("zh_cn", "item.beef.cooked.name"));
        assertEquals("模组里的", t.find("zh_cn", "item.only.mod.name"));
        assertEquals("Steak", t.find("en_us", "item.beef.cooked.name"));
        assertNull(t.find("zh_cn", "item.nothing.name"));
        assertNull(t.find("zh_tw", "item.beef.cooked.name"), "不回退到别的语言");
        assertTrue(warnings.isEmpty(), warnings.toString());
    }

    @Test
    void laterFileNameWinsInLangDir() throws IOException {
        Path dir = tmp.resolve("lang");
        write(dir.resolve("zz-覆盖.lang"), "item.saddle.name=马鞍\n");
        write(dir.resolve("zh_cn.lang"), "item.saddle.name=鞍\nitem.book.name=书\n");
        write(dir.resolve("a-早的.lang"), "item.book.name=被覆盖\n");
        LangTables t = load(dir);
        assertEquals("马鞍", t.find("zh_cn", "item.saddle.name"), "zz-覆盖.lang 排在 zh_cn.lang 后面");
        assertEquals("书", t.find("zh_cn", "item.book.name"), "zh_cn.lang 排在 a-早的.lang 后面");
    }

    @Test
    void modsAreSortedByFileName() throws IOException {
        Path b = tmp.resolve("mods/b.jar");
        Path a = tmp.resolve("other/a.jar");
        zip(b, "assets/b/lang/zh_cn.lang", "k=乙\n");
        zip(a, "assets/a/lang/zh_cn.lang", "k=甲\n");
        assertEquals("乙", load(null, b, a).find("zh_cn", "k"));
        assertEquals("乙", load(null, a, b).find("zh_cn", "k"));
    }

    @Test
    void resourcePackZipAndFolderInLangDir() throws IOException {
        Path dir = tmp.resolve("lang");
        zip(dir.resolve("a-汉化.zip"), "assets/somemod/lang/zh_cn.lang", "k.zip=压缩包\nk.both=压缩包\n",
                "assets/somemod/lang/zh_tw.lang", "k.zip=壓縮包\n",
                "assets/somemod/lang/ja_jp.lang", "k.zip=圧縮\n",
                "pack.mcmeta", "{}",
                "assets/somemod/textures/x.png", "png");
        write(dir.resolve("b-文件夹/assets/other/lang/zh_cn.lang"), "k.folder=文件夹\nk.both=文件夹\n");
        write(dir.resolve("b-文件夹/assets/other/lang/zh_TW.lang"), "k.folder=資料夾\n");
        LangTables t = load(dir);
        assertEquals("压缩包", t.find("zh_cn", "k.zip"));
        assertEquals("壓縮包", t.find("zh_tw", "k.zip"));
        assertNull(t.find("ja_jp", "k.zip"), "不读的语言");
        assertEquals("文件夹", t.find("zh_cn", "k.folder"));
        assertEquals("資料夾", t.find("zh_tw", "k.folder"), "老写法 zh_TW.lang 也认");
        assertEquals("文件夹", t.find("zh_cn", "k.both"), "文件夹排在 zip 后面");
        assertTrue(warnings.isEmpty(), warnings.toString());
    }

    @Test
    void traditionalChinese() throws IOException {
        Path dir = tmp.resolve("lang");
        write(dir.resolve("zh_cn.lang"), "item.beef.cooked.name=牛排\n");
        write(dir.resolve("zh_tw.lang"), "item.beef.cooked.name=牛排（繁）\n");
        write(dir.resolve("zh_tw-x.lang"), "item.x.name=算簡體\n");
        Path mod = tmp.resolve("mods/m.jar");
        zip(mod, "assets/m/lang/zh_tw.lang", "item.m.name=模組\n", "assets/m/lang/zh_CN.lang", "item.m.name=模组\n");
        LangTables t = load(dir, mod);
        assertEquals("牛排（繁）", t.find("zh_tw", "item.beef.cooked.name"));
        assertEquals("牛排", t.find("zh_cn", "item.beef.cooked.name"));
        assertEquals("模組", t.find("zh_tw", "item.m.name"));
        assertEquals("模组", t.find("zh_cn", "item.m.name"), "模组 jar 里老写法 zh_CN.lang 也认");
        assertEquals("算簡體", t.find("zh_cn", "item.x.name"), "文件名不是语言代码的算简体（同 YouranAPI）");
    }

    @Test
    void localeOfFileInLangDir() {
        assertEquals("zh_cn", LangTables.localeOfFile("zh_cn.lang", ServerLang.LOCALES));
        assertEquals("zh_tw", LangTables.localeOfFile("zh_TW.lang", ServerLang.LOCALES));
        assertEquals("en_us", LangTables.localeOfFile("en_us.lang", ServerLang.LOCALES));
        assertEquals("zh_cn", LangTables.localeOfFile("zz-覆盖.lang", ServerLang.LOCALES));
        assertNull(LangTables.localeOfFile("ja_jp.lang", ServerLang.LOCALES), "别的语言不读");
    }

    @Test
    void otherFilesInLangDirAreSkipped() throws IOException {
        Path dir = tmp.resolve("lang");
        write(dir.resolve("说明.txt"), "k=不是语言文件\n");
        write(dir.resolve("ja_jp.lang"), "k=日本語\n");
        write(dir.resolve("zh_cn.lang"), "k=中文\n");
        LangTables t = load(dir);
        assertEquals("中文", t.find("zh_cn", "k"));
        assertTrue(t.summary().contains("跳过 ja_jp.lang、说明.txt"), t.summary());
        assertTrue(warnings.isEmpty());
    }

    @Test
    void brokenFilesOnlyWarn() throws IOException {
        Path dir = tmp.resolve("lang");
        write(dir.resolve("a-坏的.zip"), "这不是 zip");
        write(dir.resolve("zh_cn.lang"), "k.ok=好的\n");
        Path big = dir.resolve("zz-太大.lang");
        try (OutputStream out = Files.newOutputStream(big)) {
            byte[] line = "k.big=很长很长很长很长很长很长很长很长很长很长很长很长很长很长很长很长\n".getBytes(StandardCharsets.UTF_8);
            for (long n = 0; n <= LangFile.MAX_BYTES / line.length + 1; n++) {
                out.write(line);
            }
        }
        Path brokenJar = tmp.resolve("mods/broken.jar");
        write(brokenJar, "not a jar");
        Path missingJar = tmp.resolve("mods/missing.jar");
        Path goodJar = tmp.resolve("mods/good.jar");
        zip(goodJar, "assets/g/lang/zh_cn.lang", "k.mod=模组\n");
        LangTables t = load(dir, brokenJar, missingJar, goodJar);
        assertEquals("好的", t.find("zh_cn", "k.ok"));
        assertEquals("模组", t.find("zh_cn", "k.mod"));
        assertNull(t.find("zh_cn", "k.big"), "超过大小上限的整个跳过");
        assertEquals(3, warnings.size(), warnings.toString());
        assertTrue(warnings.get(0).contains("a-坏的.zip"), warnings.toString());
        assertTrue(warnings.get(1).contains("zz-太大.lang"), warnings.toString());
        assertTrue(warnings.get(2).contains("broken.jar"), warnings.toString());
    }

    @Test
    void missingOrUnsetLangDir() throws IOException {
        Path mod = tmp.resolve("mods/m.jar");
        zip(mod, "assets/m/lang/zh_cn.lang", "k=模组\n");
        LangTables none = load(null, mod);
        assertEquals("模组", none.find("zh_cn", "k"));
        LangTables missing = load(tmp.resolve("没有这个目录"), mod);
        assertEquals("模组", missing.find("zh_cn", "k"));
        assertTrue(missing.summary().contains("不存在"), missing.summary());
        assertTrue(!Files.exists(tmp.resolve("没有这个目录")), "不自动创建");
        assertTrue(warnings.isEmpty());
        assertNull(LangTables.load(null, Collections.<Path>emptyList(), ServerLang.LOCALES, warnings::add).find("zh_cn", "k"));
    }

    @Test
    void modFolderInDevelopmentAndDuplicatesReadOnce() throws IOException {
        Path folder = tmp.resolve("classes");
        write(folder.resolve("assets/youranmod/lang/zh_cn.lang"), "k.dev=开发\n");
        write(folder.resolve("assets/youranmod/lang/en_us.lang"), "k.dev=dev\n");
        LangTables t = load(null, folder, folder.resolve("../classes"));
        assertEquals("开发", t.find("zh_cn", "k.dev"));
        assertEquals("dev", t.find("en_us", "k.dev"));
        assertTrue(t.summary().contains("模组 1 个"), t.summary());
    }
}
