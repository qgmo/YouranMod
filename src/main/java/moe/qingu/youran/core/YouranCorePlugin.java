package moe.qingu.youran.core;

import net.minecraft.launchwrapper.Launch;
import net.minecraftforge.fml.relauncher.CoreModManager;
import net.minecraftforge.fml.relauncher.IFMLLoadingPlugin;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import javax.annotation.Nullable;
import java.io.File;
import java.io.IOException;
import java.net.MalformedURLException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.zip.ZipFile;

/**
 * 没有 MixinBooter 时（服务端 SpongeForge）的引导，照 YouranSCP 的 {@code YouranScpCorePlugin}。
 *
 * <p>SpongeForge 自带的 Mixin 没有“模组上类路径之后再注册”的入口：在这里注册的配置，会在游戏主类加载时准备，
 * 那时 Forge 还没把 mods 里的普通模组加进类路径，IC2 的类找不到。所以先在这里把要修补的模组（IC2、沉浸工程、沉浸原油、匠魂）
 * 的 jar 加进类路径，再注册配置。
 * 提前加进类路径后，Forge 找模组时会在类路径上再认出它一次（报“重复的模组”），所以把它的文件名登记进
 * {@link CoreModManager#getReparseableCoremods()}：类路径扫描跳过它，只从 mods 目录认一次。
 *
 * <p>有 MixinBooter 时什么都不做（交给 {@link YouranLateMixinLoader}）。既没有 SpongeForge 也没有 MixinBooter 时（纯 Forge）
 * 也什么都不做，只记一条日志：这些 Mixin 做的事不生效，别的照常。
 */
@IFMLLoadingPlugin.Name("YouranMod")
@IFMLLoadingPlugin.MCVersion("1.12.2")
@IFMLLoadingPlugin.TransformerExclusions("moe.qingu.youran.core")
public final class YouranCorePlugin implements IFMLLoadingPlugin {
    private static final Logger LOG = LogManager.getLogger("YouranMod");

    @Override
    public String[] getASMTransformerClass() {
        return new String[0];
    }

    @Nullable
    @Override
    public String getModContainerClass() {
        return null;
    }

    @Nullable
    @Override
    public String getSetupClass() {
        return null;
    }

    @Override
    public void injectData(Map<String, Object> data) {
        if (MixinSetup.hasMixinBooter()) {
            LOG.info("有 MixinBooter，Mixin 配置交给它注册");
            return;
        }
        if (!MixinSetup.hasMixin()) {
            LOG.warn("没有 Mixin（既没有 SpongeForge 也没有 MixinBooter），装罐机反应配方、太阳能蒸馏机与精馏塔的提纯、搅拌机的三氯氢硅配方、洗矿机认别的水与洗硅晶圆、匠魂浇铸台收带纯度的混合气不生效");
            return;
        }
        File mods = new File((File) data.get("mcLocation"), "mods");
        List<String> added = new ArrayList<>();
        for (String mainClass : MixinSetup.MAIN_CLASSES) {
            File jar = findModJar(mods, mainClass);
            if (jar == null) {
                continue;
            }
            try {
                Launch.classLoader.addURL(jar.toURI().toURL());
            } catch (MalformedURLException e) {
                LOG.error("{} 加不进类路径，它的 Mixin 不生效", jar, e);
                continue;
            }
            CoreModManager.getReparseableCoremods().add(jar.getName());
            added.add(jar.getName());
        }
        List<String> configs = MixinSetup.lateConfigs();
        if (configs.isEmpty()) {
            LOG.info("mods 里没有工业时代 2、沉浸工程、匠魂，不注册 Mixin");
            return;
        }
        for (String config : configs) {
            MixinRegistrar.add(config);
        }
        LOG.info("已注册 Mixin 配置 {}（提前加进类路径：{}）", configs, added);
    }

    @Nullable
    @Override
    public String getAccessTransformerClass() {
        return null;
    }

    /** mods 与 mods/1.12.2 里含 {@code mainClass}（jar 里的路径）的第一个 jar（按文件名排序） */
    @Nullable
    static File findModJar(File modsDir, String mainClass) {
        List<File> candidates = new ArrayList<>();
        addJars(modsDir, candidates);
        addJars(new File(modsDir, "1.12.2"), candidates);
        for (File f : candidates) {
            try (ZipFile zip = new ZipFile(f)) {
                if (zip.getEntry(mainClass) != null) {
                    return f;
                }
            } catch (IOException e) {
                // 不是有效的 jar，跳过
            }
        }
        return null;
    }

    private static void addJars(File dir, List<File> out) {
        File[] files = dir.listFiles((d, name) -> name.endsWith(".jar"));
        if (files != null) {
            Arrays.sort(files);
            Collections.addAll(out, files);
        }
    }

    /** 碰 Mixin 的类单独放：没有 Mixin 时不加载它 */
    private static final class MixinRegistrar {
        static void add(String config) {
            org.spongepowered.asm.mixin.Mixins.addConfiguration(config);
        }
    }
}
