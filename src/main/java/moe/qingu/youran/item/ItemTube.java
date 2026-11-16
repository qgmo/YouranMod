package moe.qingu.youran.item;

import moe.qingu.youran.logic.litho.LampGlass;
import moe.qingu.youran.logic.litho.LampSpec;
import moe.qingu.youran.logic.purity.WaferMold;
import net.minecraft.creativetab.CreativeTabs;
import net.minecraft.item.ItemStack;
import net.minecraft.util.NonNullList;

/**
 * 汞灯的玻璃管两档（服主 11-15 / 11-16）：
 * <ul>
 *     <li>玻璃管（低档）：匠魂熔融玻璃浇进手柄铸模，NBT 记铸模（金 / 粘土，金的好），没有纯度；</li>
 *     <li>石英管：二氧化硅粉进电弧炉，1 个 9；再进一遍多一个 9（同晶圆线的“几个 9”）。</li>
 * </ul>
 */
public class ItemTube extends ItemLampPart {
    private final LampGlass glass;

    public ItemTube(String name, LampGlass glass) {
        super(name, 16, false);
        this.glass = glass;
    }

    public ItemStack make(WaferMold mold, int nines) {
        ItemStack s = new ItemStack(this);
        s.setTagCompound(LampNbt.tag(LampSpec.tube(glass, mold, nines)));
        return s;
    }

    @Override
    public void getSubItems(CreativeTabs tab, NonNullList<ItemStack> items) {
        if (!isInCreativeTab(tab)) {
            return;
        }
        if (glass == LampGlass.GLASS) {
            items.add(make(WaferMold.GOLD, 1));
            items.add(make(WaferMold.CLAY, 1));
        } else {
            items.add(make(null, 1));
            items.add(make(null, 3));
        }
    }
}
