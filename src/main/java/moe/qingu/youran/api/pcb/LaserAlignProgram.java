package moe.qingu.youran.api.pcb;

import net.minecraft.nbt.NBTTagCompound;

import javax.annotation.Nullable;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;

/**
 * 激光对准控制程序（服主 11-16）：对准台上那块个人电脑主板 ROM 里该装的程序。正版有两种来源：
 * <ul>
 *     <li>掩模 ROM：光刻机按“激光对准控制程序”掩模直接做，内容 {@code {Mask:"laser_align_rom"}}；</li>
 *     <li>EPROM：烧进去的字节与 YouranMod 自带的这份镜像（{@value #RESOURCE}，8 KB 的 8088 伺服程序，源码在仓库
 *     {@code src/main/rom/laser_align.asm}）完全一样才认——字节数、SHA-256 都比（同电脑开机检查 ROM 的规则，见 {@link RomContent#genuine}）。</li>
 * </ul>
 * 镜像到玩家磁盘上：MineBay 卖装着它的软盘（{@code item.ToolDisk#ALIGN_ROM}，079 服务里 {@code align} 镜像的 {@code ALIGN.ROM}，
 * 与这份逐字节相同）；也可以从已有对准台的主板拷出来（以后）。
 */
public final class LaserAlignProgram {
    /** 掩模 ROM 的内容 id（同芯片型号 id） */
    public static final String MASK = "laser_align_rom";
    public static final String RESOURCE = "/assets/youranmod/rom/laser_align.bin";

    private static byte[] image;

    private LaserAlignProgram() {
    }

    /** 正版镜像的字节（读不到时为空数组，EPROM 一律不认） */
    public static synchronized byte[] image() {
        if (image == null) {
            try (InputStream in = LaserAlignProgram.class.getResourceAsStream(RESOURCE)) {
                if (in == null) {
                    image = new byte[0];
                } else {
                    ByteArrayOutputStream out = new ByteArrayOutputStream();
                    byte[] buf = new byte[4096];
                    for (int n; (n = in.read(buf)) > 0; ) {
                        out.write(buf, 0, n);
                    }
                    image = out.toByteArray();
                }
            } catch (IOException e) {
                image = new byte[0];
            }
        }
        return image.clone();
    }

    public static int length() {
        return image().length;
    }

    public static String sha256() {
        return RomContent.sha256(image());
    }

    /** 这份 ROM 内容是不是正版的激光对准控制程序（掩模 ROM，或字节完全一样的 EPROM） */
    public static boolean genuine(@Nullable NBTTagCompound rom) {
        byte[] img = image();
        return RomContent.genuine(rom, MASK, img.length == 0 ? -1 : img.length, img.length == 0 ? null : RomContent.sha256(img));
    }
}
