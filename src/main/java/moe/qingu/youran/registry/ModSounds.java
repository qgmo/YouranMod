package moe.qingu.youran.registry;

import moe.qingu.youran.YouranMod;
import moe.qingu.youran.litho.LithoSoundCues;
import net.minecraft.util.ResourceLocation;
import net.minecraft.util.SoundEvent;
import net.minecraftforge.registries.IForgeRegistry;

import javax.annotation.Nullable;

/**
 * 声音事件。文件都不在本模组里：{@code assets/youranmod/sounds.json} 的条目指向整合包里别的模组的声音（IC2 的在
 * {@code client.Ic2SoundPack} 里转接），没装对应模组时只是播不出声。
 */
public final class ModSounds {
    /** 光刻机曝光时的低沉嗡嗡声（循环） */
    public static final SoundEvent LITHO_HUM = event("litho.hum");
    /** DUV（ArF 准分子）曝光时叠加的细嗞嗞声（循环） */
    public static final SoundEvent LITHO_DUV = event("litho.duv");
    /** 每曝光完一格的咔嗒 */
    public static final SoundEvent LITHO_STEP = event("litho.step");
    /** 激光对准完成、一批做完的提示音 */
    public static final SoundEvent LITHO_CHIME = event("litho.chime");
    /** 故障停机的报警 */
    public static final SoundEvent LITHO_ALARM = event("litho.alarm");

    private ModSounds() {
    }

    private static SoundEvent event(String name) {
        ResourceLocation id = new ResourceLocation(YouranMod.MODID, name);
        return new SoundEvent(id).setRegistryName(id);
    }

    public static void register(IForgeRegistry<SoundEvent> registry) {
        registry.registerAll(LITHO_HUM, LITHO_DUV, LITHO_STEP, LITHO_CHIME, LITHO_ALARM);
    }

    /** 光刻机一次性声音的事件编号（{@link LithoSoundCues}）对应的声音；没有时为 null */
    @Nullable
    public static SoundEvent forLithoEvent(int event) {
        switch (event) {
            case LithoSoundCues.EVENT_STEP:
                return LITHO_STEP;
            case LithoSoundCues.EVENT_ALIGNED:
            case LithoSoundCues.EVENT_BATCH:
                return LITHO_CHIME;
            case LithoSoundCues.EVENT_FAULT:
                return LITHO_ALARM;
            default:
                return null;
        }
    }
}
