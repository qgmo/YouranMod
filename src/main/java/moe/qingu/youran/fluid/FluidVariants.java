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
 *     <li>光刻胶 {@code photoresist}：负胶、正胶同一种流体，{@code {Resist:"negative"}} / {@code {Resist:"positive"}}（{@link ResistType}）；</li>
 *     <li>变质的光刻胶（服主 11-16）：再加 {@code {Exposed:1b}}，已经见过光（如 SCP-294 吐出来的
 *     {@code {Resist:"negative", Exposed:1b}}）。光刻机照样收、照样涂，但涂过它的晶圆做出来的芯片一定报废。
 *     NBT 不同，Forge 的罐子不会把它和好的光刻胶混在一起。</li>
 * </ul>
 * 装罐机反应配方表只按名字比流体（纯逻辑，不看 NBT），所以给它一个“键”：普通流体就是注册名，变种是 {@code 注册名#变种}
 * （{@code arene#azide}、{@code photoresist#negative}、{@code photoresist#negative_exposed}）。{@link #key} 从流体算键，{@link #stack} 从键做流体。
 */
public final class FluidVariants {
    /** 芳烃上的叠氮基标记 */
    public static final String TAG_AZIDE = "Azide";
    /** 光刻胶上的“已曝光（变质）”标记；YouranSCP 的 SCP-294 用同一个名字，别改 */
    public static final String TAG_EXPOSED = "Exposed";
    private static final String EXPOSED_SUFFIX = "_exposed";
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

    /** 是不是变质（已曝光）的光刻胶；种类认不出的不算光刻胶，也就不算 */
    public static boolean isExposedResist(@Nullable FluidStack stack) {
        return resist(stack) != null && stack.tag.getBoolean(TAG_EXPOSED);
    }

    /** {@code amount} mB 带叠氮基的芳烃 */
    public static FluidStack azideArene(int amount) {
        NBTTagCompound tag = new NBTTagCompound();
        tag.setBoolean(TAG_AZIDE, true);
        return new FluidStack(ModFluids.arene, amount, tag);
    }

    /** {@code amount} mB 这种光刻胶（好的） */
    public static FluidStack photoresist(ResistType type, int amount) {
        return photoresist(type, amount, false);
    }

    /** {@code amount} mB 这种光刻胶；{@code exposed} 为变质的 */
    public static FluidStack photoresist(ResistType type, int amount, boolean exposed) {
        NBTTagCompound tag = new NBTTagCompound();
        tag.setString(ResistType.TAG, type.id);
        if (exposed) {
            tag.setBoolean(TAG_EXPOSED, true);
        }
        return new FluidStack(ModFluids.photoresist, amount, tag);
    }

    /** 光刻胶的键：{@code photoresist#negative} */
    public static String photoresistKey(ResistType type) {
        return photoresistKey(type, false);
    }

    /** 光刻胶的键：{@code photoresist#negative}，变质的 {@code photoresist#negative_exposed} */
    public static String photoresistKey(ResistType type, boolean exposed) {
        return PHOTORESIST + "#" + type.id + (exposed ? EXPOSED_SUFFIX : "");
    }

    /** 这份流体的键：变种是 {@code 注册名#变种}，别的就是注册名（带纯度的三氯氢硅等也是注册名） */
    public static String key(FluidStack stack) {
        if (isAzideArene(stack)) {
            return AZIDE_ARENE_KEY;
        }
        ResistType r = resist(stack);
        if (r != null) {
            return photoresistKey(r, isExposedResist(stack));
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
            boolean exposed = variant.endsWith(EXPOSED_SUFFIX);
            ResistType type = ResistType.byId(exposed ? variant.substring(0, variant.length() - EXPOSED_SUFFIX.length()) : variant);
            return type == null ? null : photoresist(type, amount, exposed);
        }
        return null;
    }
}
