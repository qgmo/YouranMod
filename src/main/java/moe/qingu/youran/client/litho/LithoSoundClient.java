package moe.qingu.youran.client.litho;

import moe.qingu.youran.litho.LithoSoundCues;
import moe.qingu.youran.litho.TileLithoMaster;
import moe.qingu.youran.registry.ModSounds;
import net.minecraft.client.Minecraft;
import net.minecraft.entity.player.EntityPlayer;
import net.minecraft.util.SoundCategory;
import net.minecraft.util.SoundEvent;
import net.minecraftforge.fml.relauncher.Side;
import net.minecraftforge.fml.relauncher.SideOnly;

/**
 * 光刻机声音的客户端一侧：方块实体更新包里的嗡嗡声模式 → 开 / 关两条循环（低沉的嗡嗡声；DUV 时再叠一条细嗞嗞声），
 * 方块事件 → 一次性的声音。每台机器每 {@link #CHECK_INTERVAL} tick 才看一次，几台机器同时开也几乎不花时间。
 */
@SideOnly(Side.CLIENT)
public final class LithoSoundClient {
    public static final int CHECK_INTERVAL = 10;
    /** 嗡嗡声的音量：大于 1 时原版把听得到的范围放大（16 × 音量），响度仍封顶在 1，所以这里主要是范围，同柴油发电机那样的大声 */
    static final float HUM_VOLUME = 1.5F;
    /** 低一点，更低沉（IC2 力场发生器的循环原音基频约 120 Hz） */
    static final float HUM_PITCH = 0.85F;
    static final float DUV_VOLUME = 0.6F;
    static final float DUV_PITCH = 1.0F;
    /** 一次性的声音：音量 1.5（范围同嗡嗡声），咔嗒小一点 */
    static final float ONCE_VOLUME = 1.5F;
    static final float STEP_VOLUME = 1.0F;

    private LithoSoundClient() {
    }

    private static final class Loops {
        LithoLoopSound hum;
        LithoLoopSound duv;
    }

    /** 这台机器现在该有的模式（由方块实体同步；循环声音自己也问它） */
    static int mode(TileLithoMaster tile) {
        return tile.humMode();
    }

    /** 每 {@link #CHECK_INTERVAL} tick 一次：该响而没在响、玩家又在范围里就开一条 */
    public static void tick(TileLithoMaster tile, int mode) {
        if (mode == LithoSoundCues.HUM_OFF) {
            return;
        }
        EntityPlayer player = Minecraft.getMinecraft().player;
        if (player == null || tile.isInvalid()
                || player.getDistanceSqToCenter(tile.getPos()) > (LithoLoopSound.RANGE - 2) * (LithoLoopSound.RANGE - 2)) {
            return;
        }
        Loops loops = tile.clientSounds instanceof Loops ? (Loops) tile.clientSounds : null;
        if (loops == null) {
            loops = new Loops();
            tile.clientSounds = loops;
        }
        if (loops.hum == null || loops.hum.isDonePlaying()) {
            loops.hum = new LithoLoopSound(tile, ModSounds.LITHO_HUM, HUM_VOLUME, HUM_PITCH, m -> m != LithoSoundCues.HUM_OFF);
            Minecraft.getMinecraft().getSoundHandler().playSound(loops.hum);
        }
        if (mode == LithoSoundCues.HUM_DUV && (loops.duv == null || loops.duv.isDonePlaying())) {
            loops.duv = new LithoLoopSound(tile, ModSounds.LITHO_DUV, DUV_VOLUME, DUV_PITCH, m -> m == LithoSoundCues.HUM_DUV);
            Minecraft.getMinecraft().getSoundHandler().playSound(loops.duv);
        }
    }

    /** 一次性的声音（方块事件），从主方块的位置发出 */
    public static void playOnce(TileLithoMaster tile, int event) {
        SoundEvent sound = ModSounds.forLithoEvent(event);
        if (sound == null || tile.getWorld() == null) {
            return;
        }
        float volume = event == LithoSoundCues.EVENT_STEP ? STEP_VOLUME : ONCE_VOLUME;
        tile.getWorld().playSound(tile.getPos().getX() + 0.5, tile.getPos().getY() + 0.5, tile.getPos().getZ() + 0.5,
                sound, SoundCategory.BLOCKS, volume, LithoSoundCues.pitch(event), false);
    }
}
