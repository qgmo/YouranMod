package moe.qingu.youran.api.network.location;

import net.minecraft.item.Item;
import net.minecraft.item.ItemStack;
import net.minecraft.util.ResourceLocation;

/**
 * 定位模块这个物品（{@code youranmod:locator_module}）：装在电脑外设槽里才能签位置证明（{@link LocationProofs}）；
 * 装没装由电脑模组自己判断（外设能力 {@code location}，见 {@code api.peripheral.Peripherals}）。
 */
public final class LocatorModule {
    /** 注册名（不带模组 ID） */
    public static final String NAME = "locator_module";
    public static final ResourceLocation ID = new ResourceLocation("youranmod", NAME);

    private LocatorModule() {
    }

    /** 是不是定位模块 */
    public static boolean is(ItemStack stack) {
        return stack != null && !stack.isEmpty() && ID.equals(stack.getItem().getRegistryName());
    }

    /** 这个物品；还没注册（注册事件之前）为 null */
    public static Item item() {
        return Item.REGISTRY.getObject(ID);
    }
}
