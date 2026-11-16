package moe.qingu.youran.client.litho;

import moe.qingu.youran.litho.TileLithoMaster;
import net.minecraft.client.Minecraft;
import net.minecraft.client.audio.MovingSound;
import net.minecraft.entity.player.EntityPlayer;
import net.minecraft.util.SoundCategory;
import net.minecraft.util.SoundEvent;
import net.minecraftforge.fml.relauncher.Side;
import net.minecraftforge.fml.relauncher.SideOnly;

import java.util.function.IntPredicate;

/**
 * 光刻机主方块位置上的循环声音（类型同沉浸工程的柴油发电机，用原版的 {@link MovingSound}，所以沉浸工程的耳罩管得到：
 * 它拦的是类别 BLOCKS 的 {@code PlaySoundEvent}，把 ITickableSound 包一层改音量，结束与否仍问我们这一层）。
 * 因为耳罩会把它换成包装对象，不能用 {@code SoundHandler.stopSound(本对象)} 去停，只置 {@code donePlaying}，由声音系统每 tick 问到。
 * 机器停了（模式不对）、方块实体没了（拆了、区块卸载、换世界）、玩家走远了，都自己结束；走回来由 {@link LithoSoundClient} 重新开。
 */
@SideOnly(Side.CLIENT)
final class LithoLoopSound extends MovingSound {
    /** 听得到的范围（格）：音量设 1.5 时原版把线性衰减的范围放大成 16 × 1.5 */
    static final float RANGE = 24F;
    /** 走出这么远就停掉（比听得到的范围多留一点，免得在边上反复开关） */
    static final double STOP_DISTANCE = RANGE + 6;

    private final TileLithoMaster tile;
    private final IntPredicate modeOk;
    private int age;

    LithoLoopSound(TileLithoMaster tile, SoundEvent event, float volume, float pitch, IntPredicate modeOk) {
        super(event, SoundCategory.BLOCKS);
        this.tile = tile;
        this.modeOk = modeOk;
        this.volume = volume;
        this.pitch = pitch;
        this.repeat = true;
        this.repeatDelay = 0;
        this.xPosF = tile.getPos().getX() + 0.5F;
        this.yPosF = tile.getPos().getY() + 0.5F;
        this.zPosF = tile.getPos().getZ() + 0.5F;
    }

    @Override
    public void update() {
        if (++age % 10 != 0) {
            return;
        }
        Minecraft mc = Minecraft.getMinecraft();
        EntityPlayer player = mc.player;
        if (player == null || mc.world == null || tile.isInvalid() || tile.getWorld() != mc.world
                || !mc.world.isBlockLoaded(tile.getPos()) || !modeOk.test(LithoSoundClient.mode(tile))
                || player.getDistanceSq(xPosF, yPosF, zPosF) > STOP_DISTANCE * STOP_DISTANCE) {
            donePlaying = true;
        }
    }
}
