package moe.qingu.youran.pcb;

import moe.qingu.youran.item.ItemPcbFilm;
import moe.qingu.youran.logic.pcb.BoardType;
import moe.qingu.youran.logic.pcb.PcbNumbers;
import moe.qingu.youran.logic.pcb.PcbStage;
import moe.qingu.youran.registry.ModItems;
import net.minecraft.item.ItemStack;

/**
 * 紫外擦除器认的活（服主 11-16）：
 * <ul>
 *     <li>涂胶主板 + 线路底片：曝光，出曝光后的主板（底片留着）；</li>
 *     <li>烧过的 EPROM：擦掉内容（{@code item.ItemEprom}）。</li>
 * </ul>
 * 每种活要多少“光”：基准 tick 数，灯按现在的亮度（0–1）每 tick 给这么多（灯越亮越快）。
 */
public final class UvTargets {
    private UvTargets() {
    }

    /** 这件东西（配上这张底片）在擦除器里要做的活的基准 tick；没活可做时为 0 */
    public static int workTicks(ItemStack work, ItemStack film) {
        if (work.isEmpty()) {
            return 0;
        }
        if (work.getItem() == ModItems.pcb && ItemPcbFilm.type(film) != null
                && PcbStage.afterExposure(work.getMetadata(), ItemPcbFilm.type(film)) >= 0) {
            return PcbNumbers.UV_EXPOSE_TICKS;
        }
        if (moe.qingu.youran.item.ItemEprom.erasable(work)) {
            return PcbNumbers.UV_ERASE_TICKS;
        }
        return 0;
    }

    /** 做完以后的样子 */
    public static ItemStack finished(ItemStack work, ItemStack film) {
        if (work.getItem() == ModItems.pcb) {
            BoardType t = ItemPcbFilm.type(film);
            int meta = t == null ? -1 : PcbStage.afterExposure(work.getMetadata(), t);
            return meta < 0 ? work : new ItemStack(ModItems.pcb, work.getCount(), meta);
        }
        return moe.qingu.youran.item.ItemEprom.erased(work);
    }

    /** 能放进工作格的东西：涂胶主板、EPROM */
    public static boolean acceptsWork(ItemStack s) {
        return !s.isEmpty() && (s.getItem() == ModItems.pcb || s.getItem() instanceof moe.qingu.youran.item.ItemEprom);
    }
}
