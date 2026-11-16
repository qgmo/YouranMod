package moe.qingu.youran.client.litho;

import moe.qingu.youran.compat.alchemistry.AlchemistryCompat;
import moe.qingu.youran.item.CreativeStandards;
import moe.qingu.youran.item.ItemMotherboard;
import moe.qingu.youran.litho.SlotHints;
import moe.qingu.youran.logic.litho.ChipPackage;
import moe.qingu.youran.logic.pcb.BoardType;
import moe.qingu.youran.registry.ModItems;
import net.minecraft.init.Items;
import net.minecraft.item.Item;
import net.minecraft.item.ItemStack;
import net.minecraft.util.ResourceLocation;
import net.minecraftforge.fml.relauncher.Side;
import net.minecraftforge.fml.relauncher.SideOnly;
import net.minecraftforge.fml.common.registry.ForgeRegistries;
import net.minecraftforge.oredict.OreDictionary;

import javax.annotation.Nullable;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * 光刻机界面空格子里的半透明示意物品（{@link SlotHints} 的编号 → 候选物品）。矿物词典里有好几样的（硼锭、萤石粉、铝粉）、
 * IC2 外壳有七种材料的，每秒换一样；NBT 物品用创造物品栏的标准变种（晶圆、装齐的个人电脑主板）。
 * 只列格子真正收的东西（见 {@code LithoItems.slotFor}）；激光源格只画氦氖激光管，别的激光源虽然收，但会照坏胶，不引导。
 */
@SideOnly(Side.CLIENT)
final class GhostItems {
    private static final ResourceLocation ELEMENT = new ResourceLocation("alchemistry", "element");
    private static final ResourceLocation IC2_CASING = new ResourceLocation("ic2", "casing");
    private static final Map<String, List<ItemStack>> CACHE = new HashMap<>();

    private GhostItems() {
    }

    /** 这一秒该画的示意物品；没有（没装相应的模组等）时为 null */
    @Nullable
    static ItemStack pick(String id, long timeMs) {
        List<ItemStack> list = CACHE.computeIfAbsent(id, GhostItems::build);
        return list.isEmpty() ? null : list.get((int) ((timeMs / 1000) % list.size()));
    }

    /** 进了新世界 / 资源重载后物品表可能变，重新取 */
    static void clear() {
        CACHE.clear();
    }

    private static List<ItemStack> build(String id) {
        List<ItemStack> l = new ArrayList<>();
        switch (id) {
            case SlotHints.MASK:
                add(l, new ItemStack(ModItems.photomask));
                break;
            case SlotHints.CONTAINER:
                add(l, new ItemStack(Items.BUCKET));
                break;
            case SlotHints.WAFER: {
                ItemStack w = new ItemStack(ModItems.precisionSiliconWafer);
                w.setTagCompound(CreativeStandards.wafer());
                add(l, w);
                break;
            }
            case SlotHints.BORON_INGOT:
                ore(l, "ingotBoron", null);
                break;
            case SlotHints.GLOWSTONE:
                ore(l, "dustGlowstone", new ItemStack(Items.GLOWSTONE_DUST));
                break;
            case SlotHints.ALUMINUM:
                ore(l, "dustAluminum", null);
                break;
            case SlotHints.BORON_ELEMENT:
                element(l, 5);
                break;
            case SlotHints.PHOSPHORUS:
                element(l, 15);
                break;
            case SlotHints.HYDROXIDE:
                add(l, AlchemistryCompat.stack(AlchemistryCompat.HYDROXIDE, 1));
                break;
            case SlotHints.CARBONATE:
                add(l, AlchemistryCompat.stack(AlchemistryCompat.POTASSIUM_CARBONATE, 1));
                break;
            case SlotHints.CASING: {
                Item casing = ForgeRegistries.ITEMS.getValue(IC2_CASING);
                if (casing != null) {
                    for (ChipPackage p : ChipPackage.values()) {
                        add(l, new ItemStack(casing, 1, p.meta));
                    }
                }
                break;
            }
            case SlotHints.BOARD:
                add(l, ItemMotherboard.make(ModItems.motherboard, BoardType.PC, CreativeStandards.board(BoardType.PC)));
                break;
            case SlotHints.LASER:
                add(l, new ItemStack(ModItems.heneLaserTube));
                break;
            default:
                break;
        }
        return l;
    }

    private static void add(List<ItemStack> l, @Nullable ItemStack s) {
        if (s != null && !s.isEmpty()) {
            l.add(s);
        }
    }

    private static void ore(List<ItemStack> l, String name, @Nullable ItemStack fallback) {
        for (ItemStack s : OreDictionary.getOres(name)) {
            ItemStack c = s.copy();
            if (c.getMetadata() == OreDictionary.WILDCARD_VALUE) {
                c.setItemDamage(0);
            }
            add(l, c);
        }
        if (l.isEmpty()) {
            add(l, fallback);
        }
    }

    private static void element(List<ItemStack> l, int z) {
        Item e = ForgeRegistries.ITEMS.getValue(ELEMENT);
        if (e != null) {
            add(l, new ItemStack(e, 1, z));
        }
    }
}
