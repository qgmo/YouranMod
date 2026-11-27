package moe.qingu.youran.api.network.identity;

import net.minecraft.item.Item;
import net.minecraft.item.ItemStack;
import net.minecraft.util.ResourceLocation;

/**
 * 身份模块这个物品（{@code youranmod:identity_module}，服主 11-27）：和定位模块并列的外设，装在电脑外设槽里才能签操作者证明
 * （{@link OperatorProofs}，一键登录用）；没装照样上网，只是不能一键登录。签的是电脑现在的操作者。外设种类 {@code identity}，
 * 能力 {@code identity}（{@code api.peripheral.Peripherals}）。
 */
public final class IdentityModule {
    /** 注册名（不带模组 ID） */
    public static final String NAME = "identity_module";
    public static final ResourceLocation ID = new ResourceLocation("youranmod", NAME);

    private IdentityModule() {
    }

    /** 是不是身份模块 */
    public static boolean is(ItemStack stack) {
        return stack != null && !stack.isEmpty() && ID.equals(stack.getItem().getRegistryName());
    }

    /** 这个物品；还没注册（注册事件之前）为 null */
    public static Item item() {
        return Item.REGISTRY.getObject(ID);
    }
}
