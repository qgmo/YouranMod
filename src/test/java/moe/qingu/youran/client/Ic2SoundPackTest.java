package moe.qingu.youran.client;

import net.minecraft.util.ResourceLocation;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.File;
import java.io.FileNotFoundException;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** IC2 声音转接：jar 根下 ic2/sounds 的大小写路径 → youranmod:sounds/ic2/小写路径 */
class Ic2SoundPackTest {
    @Test
    void mapsLowercasePathsToIc2JarEntries(@TempDir Path dir) throws Exception {
        File jar = dir.resolve("ic2.jar").toFile();
        byte[] data = "OggS-test".getBytes(StandardCharsets.UTF_8);
        try (ZipOutputStream z = new ZipOutputStream(new FileOutputStream(jar))) {
            z.putNextEntry(new ZipEntry("ic2/sounds/Machines/ForcefieldLoop.ogg"));
            z.write(data);
            z.closeEntry();
            z.putNextEntry(new ZipEntry("ic2/sounds/Machines/MachineOverload.ogg"));
            z.write(1);
            z.closeEntry();
            z.putNextEntry(new ZipEntry("assets/ic2/lang/en_us.lang"));
            z.write(2);
            z.closeEntry();
        }
        Ic2SoundPack pack = new Ic2SoundPack();
        pack.open(jar);
        ResourceLocation hum = new ResourceLocation("youranmod", "sounds/ic2/machines/forcefieldloop.ogg");
        assertTrue(pack.resourceExists(hum));
        assertTrue(pack.resourceExists(new ResourceLocation("youranmod", "sounds/ic2/machines/machineoverload.ogg")));
        try (InputStream in = pack.getInputStream(hum)) {
            byte[] read = new byte[data.length];
            assertEquals(data.length, in.read(read));
            assertArrayEquals(data, read);
        }
        assertFalse(pack.resourceExists(new ResourceLocation("youranmod", "sounds/ic2/machines/nothing.ogg")));
        assertFalse(pack.resourceExists(new ResourceLocation("ic2", "sounds/ic2/machines/forcefieldloop.ogg")), "只管 youranmod 域");
        assertFalse(pack.resourceExists(new ResourceLocation("youranmod", "sounds/ic2/machines/forcefieldloop.ogg.mcmeta")));
        assertThrows(FileNotFoundException.class, () -> pack.getInputStream(new ResourceLocation("youranmod", "sounds/ic2/x.ogg")));
    }

    @Test
    void emptyWithoutIc2() {
        Ic2SoundPack pack = new Ic2SoundPack();
        assertFalse(pack.resourceExists(new ResourceLocation("youranmod", "sounds/ic2/machines/forcefieldloop.ogg")));
    }
}
