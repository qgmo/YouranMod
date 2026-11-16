package moe.qingu.youran.litho;

import moe.qingu.youran.item.ItemPhotomask;
import moe.qingu.youran.logic.litho.ChipPackage;
import moe.qingu.youran.registry.ModItems;
import net.minecraft.item.ItemStack;
import net.minecraft.util.ResourceLocation;
import net.minecraftforge.oredict.OreDictionary;

import javax.annotation.Nullable;

/** 光刻机认的物品：晶圆、掺杂与金属化的辅料、IC2 外壳、掩模 */
public final class LithoItems {
    static final ResourceLocation ALCHEMISTRY_ELEMENT = new ResourceLocation("alchemistry", "element");
    static final ResourceLocation IC2_CASING = new ResourceLocation("ic2", "casing");
    static final int BORON = 5;
    static final int PHOSPHORUS = 15;

    private LithoItems() {
    }

    public static boolean isWafer(ItemStack s) {
        return !s.isEmpty() && s.getItem() == ModItems.precisionSiliconWafer;
    }

    public static boolean isMask(ItemStack s) {
        return !s.isEmpty() && s.getItem() instanceof ItemPhotomask;
    }

    public static boolean isBoronIngot(ItemStack s) {
        return hasOre(s, "ingotBoron");
    }

    public static boolean isGlowstone(ItemStack s) {
        return hasOre(s, "dustGlowstone");
    }

    public static boolean isAluminumDust(ItemStack s) {
        return hasOre(s, "dustAluminum");
    }

    public static boolean isBoronElement(ItemStack s) {
        return isElement(s, BORON);
    }

    public static boolean isPhosphorus(ItemStack s) {
        return isElement(s, PHOSPHORUS);
    }

    static boolean isElement(ItemStack s, int z) {
        return !s.isEmpty() && ALCHEMISTRY_ELEMENT.equals(s.getItem().getRegistryName()) && s.getMetadata() == z;
    }

    /** IC2 外壳的材料；不是外壳时为 null */
    @Nullable
    public static ChipPackage casing(ItemStack s) {
        if (s.isEmpty() || !IC2_CASING.equals(s.getItem().getRegistryName())) {
            return null;
        }
        return ChipPackage.byMeta(s.getMetadata());
    }

    /** 这个物品该放进哪一格（物品入口按这个分）；不收时为 −1 */
    public static int slotFor(ItemStack s) {
        if (isWafer(s)) {
            return LithoSlots.WAFER_FIRST;
        }
        if (isBoronIngot(s)) {
            return LithoSlots.BORON_INGOT;
        }
        if (isGlowstone(s)) {
            return LithoSlots.GLOWSTONE;
        }
        if (isBoronElement(s)) {
            return LithoSlots.BORON_ELEMENT;
        }
        if (isPhosphorus(s)) {
            return LithoSlots.PHOSPHORUS;
        }
        if (isAluminumDust(s)) {
            return LithoSlots.ALUMINUM;
        }
        if (casing(s) != null) {
            return LithoSlots.CASING;
        }
        return -1;
    }

    static boolean hasOre(ItemStack s, String ore) {
        if (s.isEmpty() || !OreDictionary.doesOreNameExist(ore)) {
            return false;
        }
        int id = OreDictionary.getOreID(ore);
        for (int i : OreDictionary.getOreIDs(s)) {
            if (i == id) {
                return true;
            }
        }
        return false;
    }
}
