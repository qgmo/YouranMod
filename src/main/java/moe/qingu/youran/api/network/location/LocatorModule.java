package moe.qingu.youran.api.network.location;

import net.minecraft.item.Item;
import net.minecraft.item.ItemStack;
import net.minecraft.util.ResourceLocation;

/**
 * 定位模块这个物品（{@code youranmod:locator_module}）。电脑模组在界面里给它留一个卡槽，装上时给设备声明
 * {@code DeviceCapability.LOCATION}，拿出来时去掉。
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
