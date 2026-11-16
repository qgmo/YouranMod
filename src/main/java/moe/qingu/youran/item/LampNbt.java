package moe.qingu.youran.item;

import moe.qingu.youran.fluid.FluidPurity;
import moe.qingu.youran.logic.litho.LampGas;
import moe.qingu.youran.logic.litho.LampGlass;
import moe.qingu.youran.logic.litho.LampSpec;
import moe.qingu.youran.logic.purity.Purity;
import moe.qingu.youran.logic.purity.WaferMold;
import net.minecraft.nbt.NBTTagCompound;
import net.minecraftforge.common.util.Constants;

import javax.annotation.Nullable;

/**
 * 玻璃管、汞灯、光源的 NBT：
 * <pre>
 * 玻璃管：{Glass:"glass", Mold:"gold"} 或 {Glass:"quartz", Nines:3}
 * 汞灯、光源：再加 {Gas:"xenon", Used:120, Mercury:8, MercuryWear:20}
 * </pre>
 * 纯度的键同晶圆（几个 9），铸模的键同晶圆（{@link WaferMold#TAG}）。
 */
public final class LampNbt {
    public static final String TAG_USED = "Used";
    public static final String TAG_MERCURY = "Mercury";
    public static final String TAG_MERCURY_WEAR = "MercuryWear";

    private LampNbt() {
    }

    public static NBTTagCompound tag(LampSpec spec) {
        NBTTagCompound tag = new NBTTagCompound();
        tag.setString(LampGlass.TAG, spec.glass.id);
        if (spec.glass == LampGlass.QUARTZ) {
            tag.setInteger(FluidPurity.TAG_NINES, spec.nines);
        } else if (spec.mold != null) {
            tag.setString(WaferMold.TAG, spec.mold.id);
        }
        if (spec.gas != null) {
            tag.setString(LampGas.TAG, spec.gas.id);
            tag.setInteger(TAG_USED, spec.used);
            tag.setInteger(TAG_MERCURY, spec.mercury);
            tag.setInteger(TAG_MERCURY_WEAR, spec.mercuryWear);
        }
        return tag;
    }

    /** 读出来；没有标记的按低档玻璃管（粘土） */
    public static LampSpec read(@Nullable NBTTagCompound tag) {
        if (tag == null) {
            return LampSpec.tube(LampGlass.GLASS, null, Purity.DEFAULT_NINES);
        }
        LampGlass glass = LampGlass.byId(tag.getString(LampGlass.TAG));
        WaferMold mold = tag.hasKey(WaferMold.TAG, Constants.NBT.TAG_STRING) ? WaferMold.byId(tag.getString(WaferMold.TAG)) : null;
        LampGas gas = tag.hasKey(LampGas.TAG, Constants.NBT.TAG_STRING) ? LampGas.byId(tag.getString(LampGas.TAG)) : null;
        return new LampSpec(glass, mold, FluidPurity.nines(tag), gas, tag.getInteger(TAG_USED), tag.getInteger(TAG_MERCURY),
                tag.getInteger(TAG_MERCURY_WEAR));
    }

    /** 只抄玻璃管的部分（种类、铸模、纯度） */
    public static LampSpec tubeOf(@Nullable NBTTagCompound from) {
        LampSpec s = read(from);
        return LampSpec.tube(s.glass, s.mold, s.nines);
    }
}
