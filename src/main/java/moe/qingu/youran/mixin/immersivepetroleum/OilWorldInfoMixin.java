package moe.qingu.youran.mixin.immersivepetroleum;

import flaxbeard.immersivepetroleum.api.crafting.PumpjackHandler;
import moe.qingu.youran.compat.immersivepetroleum.GeneratedReservoir;
import moe.qingu.youran.logic.ip.ReservoirCache;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;

/** 油井信息记下“是不是这次开服现算的、算出来时是什么样”（审查 IP-L1，见 {@link GeneratedReservoir}）。不存档 */
@Mixin(value = PumpjackHandler.OilWorldInfo.class, remap = false)
public abstract class OilWorldInfoMixin implements GeneratedReservoir {
    @Shadow
    public PumpjackHandler.ReservoirType type;
    @Shadow
    public PumpjackHandler.ReservoirType overrideType;
    @Shadow
    public int capacity;
    @Shadow
    public int current;

    @Unique
    private boolean youran$generated;
    @Unique
    private PumpjackHandler.ReservoirType youran$generatedType;
    @Unique
    private int youran$generatedCapacity;

    @Override
    public void youran$markGenerated() {
        youran$generated = true;
        youran$generatedType = type;
        youran$generatedCapacity = capacity;
    }

    @Override
    public boolean youran$regenerable() {
        return ReservoirCache.regenerable(youran$generated, youran$generatedType, youran$generatedCapacity,
                type, overrideType, capacity, current);
    }
}
