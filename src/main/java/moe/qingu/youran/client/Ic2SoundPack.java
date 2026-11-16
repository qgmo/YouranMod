package moe.qingu.youran.client;

import com.google.common.collect.ImmutableSet;
import moe.qingu.youran.YouranMod;
import net.minecraft.client.resources.IResourcePack;
import net.minecraft.client.resources.data.IMetadataSection;
import net.minecraft.client.resources.data.MetadataSerializer;
import net.minecraft.util.ResourceLocation;
import net.minecraftforge.fml.common.Loader;
import net.minecraftforge.fml.common.ModContainer;
import net.minecraftforge.fml.relauncher.Side;
import net.minecraftforge.fml.relauncher.SideOnly;

import javax.annotation.Nullable;
import java.awt.image.BufferedImage;
import java.io.File;
import java.io.FileNotFoundException;
import java.io.IOException;
import java.io.InputStream;
import java.util.Enumeration;
import java.util.HashMap;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;

/**
 * 把 IC2 的声音转接给原版声音系统，不复制文件：IC2 2.8 的声音放在 jar 根下的 {@code ic2/sounds/Machines/MagnetizerLoop.ogg}，
 * 有大写，也不在 {@code assets/} 里，原版按“域:路径”找不到（1.12 的资源路径一律小写）。这个资源包只读 IC2 的 jar，
 * 把 {@code youranmod:sounds/ic2/<小写路径>.ogg} 对到 jar 里同名（忽略大小写）的 {@code ic2/sounds/<路径>.ogg}，
 * 所以 {@code sounds.json} 里写 {@code youranmod:ic2/machines/forcefieldloop} 就行。没装 IC2 或找不到文件时什么都不提供，只是对应的声音放不出来。
 */
@SideOnly(Side.CLIENT)
public final class Ic2SoundPack implements IResourcePack {
    private static final String PREFIX = "sounds/ic2/";
    private static final String SOURCE_PREFIX = "ic2/sounds/";

    /** 小写的“sounds/ic2/…ogg” → jar 里的真实条目名 */
    private final Map<String, String> index = new HashMap<>();
    @Nullable
    private ZipFile zip;

    /** 取 IC2 的 jar；没装、不是 jar（开发环境的目录）时返回一个空的资源包 */
    public static Ic2SoundPack create() {
        Ic2SoundPack pack = new Ic2SoundPack();
        ModContainer ic2 = Loader.instance().getIndexedModList().get("ic2");
        File source = ic2 == null ? null : ic2.getSource();
        if (source != null && source.isFile()) {
            try {
                pack.open(source);
            } catch (IOException e) {
                YouranMod.LOG.warn("读不了 IC2 的 jar，光刻机的 IC2 声音放不出来：{}", e.toString());
            }
        } else {
            YouranMod.LOG.info("没找到 IC2 的 jar，光刻机的嗡嗡声与报警放不出来");
        }
        return pack;
    }

    void open(File jar) throws IOException {
        zip = new ZipFile(jar);
        Enumeration<? extends ZipEntry> e = zip.entries();
        while (e.hasMoreElements()) {
            ZipEntry entry = e.nextElement();
            String name = entry.getName();
            if (!entry.isDirectory() && name.startsWith(SOURCE_PREFIX) && name.endsWith(".ogg")) {
                index.put(PREFIX + name.substring(SOURCE_PREFIX.length()).toLowerCase(Locale.ROOT), name);
            }
        }
    }

    @Override
    public InputStream getInputStream(ResourceLocation location) throws IOException {
        String real = YouranMod.MODID.equals(location.getNamespace()) ? index.get(location.getPath()) : null;
        if (real == null || zip == null) {
            throw new FileNotFoundException(location.toString());
        }
        return zip.getInputStream(zip.getEntry(real));
    }

    @Override
    public boolean resourceExists(ResourceLocation location) {
        return YouranMod.MODID.equals(location.getNamespace()) && index.containsKey(location.getPath());
    }

    @Override
    public Set<String> getResourceDomains() {
        return ImmutableSet.of(YouranMod.MODID);
    }

    @Nullable
    @Override
    public <T extends IMetadataSection> T getPackMetadata(MetadataSerializer serializer, String sectionName) {
        return null;
    }

    @Override
    public BufferedImage getPackImage() throws IOException {
        throw new FileNotFoundException("no icon");
    }

    @Override
    public String getPackName() {
        return "YouranMod IC2 sounds";
    }
}
