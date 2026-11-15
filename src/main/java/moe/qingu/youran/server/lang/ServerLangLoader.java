package moe.qingu.youran.server.lang;

import moe.qingu.youran.YouranMod;
import moe.qingu.youran.api.lang.LangSource;
import moe.qingu.youran.api.lang.ServerLang;
import moe.qingu.youran.config.LangConfig;
import net.minecraftforge.fml.common.Loader;
import net.minecraftforge.fml.common.ModContainer;

import java.io.File;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Supplier;

/**
 * 读服务端翻译（只在完整包里，由 {@link ServerLang} 按类名加载）：语言目录（{@link LangConfig#dir}）与所有已加载模组的 jar。
 * 在 {@link ServerLang} 的加载线程上调用。
 */
public final class ServerLangLoader implements Supplier<LangSource> {
    @Override
    public LangSource get() {
        List<Path> sources = new ArrayList<>();
        for (ModContainer m : Loader.instance().getModList()) {
            File f = m.getSource();
            if (f != null && f.exists()) {
                sources.add(f.toPath());
            }
        }
        return LangTables.load(langDir(), sources, msg -> YouranMod.LOG.warn("服务端翻译：{}", msg));
    }

    /** 语言目录：相对路径相对于服务器目录（config 目录的上一级）；留空 = 不读 */
    static Path langDir() {
        String set = LangConfig.dir == null ? "" : LangConfig.dir.trim();
        if (set.isEmpty()) {
            return null;
        }
        Path p = Paths.get(set);
        return p.isAbsolute() ? p : Loader.instance().getConfigDir().getParentFile().toPath().resolve(p).normalize();
    }
}
