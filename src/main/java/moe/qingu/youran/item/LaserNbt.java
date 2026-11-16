package moe.qingu.youran.item;

import moe.qingu.youran.fluid.FluidPurity;
import moe.qingu.youran.logic.litho.ExcimerSpec;
import moe.qingu.youran.logic.litho.HeNeSpec;
import moe.qingu.youran.logic.litho.LampGlass;
import moe.qingu.youran.logic.litho.LampSpec;
import moe.qingu.youran.logic.purity.Purity;
import moe.qingu.youran.logic.purity.WaferMold;
import net.minecraft.nbt.NBTTagCompound;
import net.minecraftforge.common.util.Constants;

import javax.annotation.Nullable;

/**
 * ArF 激光腔、准分子激光光源、氦氖激光管的 NBT：
 * <pre>
 * 激光腔 / 准分子激光光源：{Nines:3, Fluorine:2370, Neon:48, Argon:750, Used:315}   // 氟按千分之一个记
 * 氦氖激光管：{Glass:"glass", Mold:"gold", Helium:20000, Neon:2, Stamp:123456L}  // 或 {Glass:"quartz", Nines:2, …}；氦是 Stamp 那一刻的量（千分之一个）
 * </pre>
 * 纯度、玻璃管的键同汞灯（{@link LampNbt}）。
 */
public final class LaserNbt {
    public static final String TAG_FLUORINE = "Fluorine";
    public static final String TAG_NEON = "Neon";
    public static final String TAG_ARGON = "Argon";
    public static final String TAG_USED = "Used";
    public static final String TAG_HELIUM = "Helium";
    public static final String TAG_STAMP = "Stamp";

    private LaserNbt() {
    }

    public static NBTTagCompound tag(ExcimerSpec s) {
        NBTTagCompound t = new NBTTagCompound();
        t.setInteger(FluidPurity.TAG_NINES, s.nines);
        t.setInteger(TAG_FLUORINE, s.fluorineMilli);
        t.setInteger(TAG_NEON, s.neon);
        t.setInteger(TAG_ARGON, s.argonMb);
        t.setInteger(TAG_USED, s.used);
        return t;
    }

    /** 读激光腔；没有标记的按 1 个 9 的空腔 */
    public static ExcimerSpec excimer(@Nullable NBTTagCompound t) {
        if (t == null) {
            return ExcimerSpec.empty(Purity.DEFAULT_NINES);
        }
        return new ExcimerSpec(FluidPurity.nines(t), t.getInteger(TAG_FLUORINE), t.getInteger(TAG_NEON), t.getInteger(TAG_ARGON), t.getInteger(TAG_USED));
    }

    /** 用这根石英管（{@link LampNbt} 格式）装出来的空激光腔 */
    public static ExcimerSpec cavityOf(@Nullable NBTTagCompound quartzTube) {
        return ExcimerSpec.empty(LampNbt.read(quartzTube).nines);
    }

    public static NBTTagCompound tag(HeNeSpec s) {
        NBTTagCompound t = LampNbt.tag(LampSpec.tube(s.glass, s.mold, s.nines));
        t.setInteger(TAG_HELIUM, s.heliumMilli);
        t.setInteger(TAG_NEON, s.neon);
        if (s.stamp >= 0) {
            t.setLong(TAG_STAMP, s.stamp);
        }
        return t;
    }

    public static HeNeSpec hene(@Nullable NBTTagCompound t) {
        if (t == null) {
            return HeNeSpec.empty(LampGlass.GLASS, null, Purity.DEFAULT_NINES);
        }
        LampSpec tube = LampNbt.read(t);
        long stamp = t.hasKey(TAG_STAMP, Constants.NBT.TAG_ANY_NUMERIC) ? t.getLong(TAG_STAMP) : -1;
        return new HeNeSpec(tube.glass, tube.mold, tube.nines, t.getInteger(TAG_HELIUM), t.getInteger(TAG_NEON), stamp);
    }

    /** 用这根玻璃管（低档或石英，{@link LampNbt} 格式）装出来的空氦氖激光管 */
    public static HeNeSpec heneOf(@Nullable NBTTagCompound tube) {
        LampSpec s = LampNbt.tubeOf(tube);
        WaferMold mold = s.mold;
        return HeNeSpec.empty(s.glass, mold, s.nines);
    }
}
