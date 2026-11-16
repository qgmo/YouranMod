package moe.qingu.youran.fluid;

import moe.qingu.youran.logic.litho.ResistType;
import moe.qingu.youran.registry.ModFluids;
import net.minecraft.nbt.NBTTagCompound;
import net.minecraftforge.common.util.Constants;
import net.minecraftforge.fluids.Fluid;
import net.minecraftforge.fluids.FluidRegistry;
import net.minecraftforge.fluids.FluidStack;

import javax.annotation.Nullable;

/**
 * 靠 NBT 区分的流体“变种”（服主 11-15）：
 * <ul>
 *     <li>带叠氮基的芳烃：芳烃 {@code arene} 加标记 {@code {Azide:1b}}，不另注册流体；</li>
 *     <li>光刻胶 {@code photoresist}：负胶、正胶同一种流体，{@code {Resist:"negative"}} / {@code {Resist:"positive"}}（{@link ResistType}）。</li>
 * </ul>
 * 装罐机反应配方表只按名字比流体（纯逻辑，不看 NBT），所以给它一个“键”：普通流体就是注册名，变种是 {@code 注册名#变种}
 * （{@code arene#azide}、{@code photoresist#negative}）。{@link #key} 从流体算键，{@link #stack} 从键做流体。
 */
public final class FluidVariants {
    /** 芳烃上的叠氮基标记 */
    public static final String TAG_AZIDE = "Azide";
    public static final String ARENE = "arene";
    public static final String PHOTORESIST = "photoresist";
    public static final String AZIDE_ARENE_KEY = ARENE + "#azide";

    private FluidVariants() {
    }

    /** 是不是带叠氮基的芳烃 */
    public static boolean isAzideArene(@Nullable FluidStack stack) {
        return stack != null && ModFluids.arene != null && stack.getFluid() == ModFluids.arene && hasAzide(stack.tag);
    }

    /** 是不是普通芳烃（不带叠氮基） */
    public static boolean isPlainArene(@Nullable FluidStack stack) {
        return stack != null && ModFluids.arene != null && stack.getFluid() == ModFluids.arene && !hasAzide(stack.tag);
    }

    static boolean hasAzide(@Nullable NBTTagCompound tag) {
        return tag != null && tag.getBoolean(TAG_AZIDE);
    }

    /** 光刻胶的种类；不是光刻胶或没有标记时为 null */
    @Nullable
    public static ResistType resist(@Nullable FluidStack stack) {
        if (stack == null || ModFluids.photoresist == null || stack.getFluid() != ModFluids.photoresist || stack.tag == null
                || !stack.tag.hasKey(ResistType.TAG, Constants.NBT.TAG_STRING)) {
            return null;
        }
        return ResistType.byId(stack.tag.getString(ResistType.TAG));
    }

    /** {@code amount} mB 带叠氮基的芳烃 */
    public static FluidStack azideArene(int amount) {
        NBTTagCompound tag = new NBTTagCompound();
        tag.setBoolean(TAG_AZIDE, true);
        return new FluidStack(ModFluids.arene, amount, tag);
    }

    /** {@code amount} mB 这种光刻胶 */
    public static FluidStack photoresist(ResistType type, int amount) {
        NBTTagCompound tag = new NBTTagCompound();
        tag.setString(ResistType.TAG, type.id);
        return new FluidStack(ModFluids.photoresist, amount, tag);
    }

    /** 光刻胶的键：{@code photoresist#negative} */
    public static String photoresistKey(ResistType type) {
        return PHOTORESIST + "#" + type.id;
    }

    /** 这份流体的键：变种是 {@code 注册名#变种}，别的就是注册名（带纯度的三氯氢硅等也是注册名） */
    public static String key(FluidStack stack) {
        if (isAzideArene(stack)) {
            return AZIDE_ARENE_KEY;
        }
        ResistType r = resist(stack);
        if (r != null) {
            return photoresistKey(r);
        }
        return stack.getFluid().getName();
    }

    /** 按键做流体；流体没注册（或认不出变种）时为 null */
    @Nullable
    public static FluidStack stack(String key, int amount) {
        int hash = key.indexOf('#');
        if (hash < 0) {
            return FluidRegistry.getFluidStack(key, amount);
        }
        String name = key.substring(0, hash);
        String variant = key.substring(hash + 1);
        Fluid fluid = FluidRegistry.getFluid(name);
        if (fluid == null) {
            return null;
        }
        if (ARENE.equals(name) && "azide".equals(variant)) {
            return azideArene(amount);
        }
        if (PHOTORESIST.equals(name)) {
            ResistType type = ResistType.byId(variant);
            return type == null ? null : photoresist(type, amount);
        }
        return null;
    }
}
