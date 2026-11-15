package moe.qingu.youran.core;

import org.junit.jupiter.api.Test;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.nio.file.Files;
import java.util.Collections;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

class MixinSetupTest {
    @Test
    void ic2ConfigOnlyWithIc2() {
        assertEquals(Collections.singletonList("mixins.youranmod.ic2.json"), MixinSetup.configs(true));
        assertEquals(Collections.emptyList(), MixinSetup.configs(false));
    }

    /** 沉浸工程的配置要装了沉浸工程；沉浸原油的配置要沉浸工程、沉浸原油都在 */
    @Test
    void configsByInstalledMods() {
        assertEquals(java.util.Arrays.asList("mixins.youranmod.ic2.json", "mixins.youranmod.ie.json", "mixins.youranmod.ip.json"),
                MixinSetup.configs(true, true, true));
        assertEquals(Collections.singletonList("mixins.youranmod.ie.json"), MixinSetup.configs(false, true, false));
        assertEquals(Collections.singletonList("mixins.youranmod.ic2.json"), MixinSetup.configs(true, false, true));
        assertEquals(Collections.emptyList(), MixinSetup.configs(false, false, false));
    }

    /** 匠魂的配置只看匠魂装没装，排在最后 */
    @Test
    void ticConfig() {
        assertEquals(java.util.Arrays.asList("mixins.youranmod.ic2.json", "mixins.youranmod.ie.json", "mixins.youranmod.ip.json",
                "mixins.youranmod.tic.json"), MixinSetup.configs(true, true, true, true));
        assertEquals(Collections.singletonList("mixins.youranmod.tic.json"), MixinSetup.configs(false, false, true, true));
        assertEquals(MixinSetup.configs(true, true, false), MixinSetup.configs(true, true, false, false));
        assertEquals(4, MixinSetup.MAIN_CLASSES.length);
        assertEquals(MixinSetup.TIC_MAIN_CLASS, MixinSetup.MAIN_CLASSES[3]);
    }

    /** 核心插件在 mods 与 mods/1.12.2 里按主类找 IC2 的 jar */
    @Test
    void findsIc2JarByMainClass() throws IOException {
        File mods = Files.createTempDirectory("mods").toFile();
        File sub = new File(mods, "1.12.2");
        sub.mkdirs();
        jar(new File(mods, "a-other.jar"), "other/Main.class");
        new File(mods, "broken.jar").createNewFile();
        assertNull(YouranCorePlugin.findModJar(mods, MixinSetup.IC2_MAIN_CLASS));
        File ic2 = jar(new File(sub, "industrialcraft-2-2.8.221-ex112.jar"), MixinSetup.IC2_MAIN_CLASS);
        assertEquals(ic2, YouranCorePlugin.findModJar(mods, MixinSetup.IC2_MAIN_CLASS));
    }

    private static File jar(File file, String entry) throws IOException {
        try (ZipOutputStream out = new ZipOutputStream(new FileOutputStream(file))) {
            out.putNextEntry(new ZipEntry(entry));
            out.write(new byte[]{1});
            out.closeEntry();
        }
        return file;
    }
}
