package moe.qingu.youran.item;

import moe.qingu.youran.fluid.FluidPurity;
import moe.qingu.youran.logic.purity.Purity;
import moe.qingu.youran.logic.purity.WaferMold;
import net.minecraft.nbt.NBTTagCompound;
import net.minecraftforge.common.util.Constants;

import javax.annotation.Nullable;

/**
 * 硅晶圆、高精硅晶圆的 NBT（{@link ItemSiliconWafer}）：{@code {Nines: 9, Mold: "gold"}}。纯度的键与流体相同（几个 9），
 * 铸模见 {@link WaferMold#TAG}。只碰 NBT，不碰物品，单元测试也能用。
 */
public final class WaferNbt {
    private WaferNbt() {
    }

    /** 晶圆的 NBT：纯度总写上（物品不用像流体那样为合并省掉），有铸模时写铸模 */
    public static NBTTagCompound tag(int nines, @Nullable WaferMold mold) {
        NBTTagCompound tag = new NBTTagCompound();
        tag.setInteger(FluidPurity.TAG_NINES, Purity.sanitize(nines));
        if (mold != null) {
            tag.setString(WaferMold.TAG, mold.id);
        }
        return tag;
    }

    /** 照抄纯度与铸模（清洗成高精硅晶圆时用）：只留这两样，别的键不带过去 */
    public static NBTTagCompound copy(@Nullable NBTTagCompound from) {
        return tag(nines(from), mold(from));
    }

    /** 纯度（几个 9）；没有标记时 1 个 */
    public static int nines(@Nullable NBTTagCompound tag) {
        return FluidPurity.nines(tag);
    }

    /** 铸模；没有标记（或认不出）时为 null */
    @Nullable
    public static WaferMold mold(@Nullable NBTTagCompound tag) {
        if (tag == null || !tag.hasKey(WaferMold.TAG, Constants.NBT.TAG_STRING)) {
            return null;
        }
        return WaferMold.byId(tag.getString(WaferMold.TAG));
    }
}
