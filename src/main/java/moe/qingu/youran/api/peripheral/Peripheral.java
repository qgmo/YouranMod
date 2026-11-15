package moe.qingu.youran.api.peripheral;

import net.minecraft.item.ItemStack;

import java.util.Collections;
import java.util.Set;

/**
 * 外设物品（由物品类实现）。见包说明。
 */
public interface Peripheral {
    /**
     * 外设种类编号：电脑与模拟器按它认外设，一经定下不再改（存档、079 服务的快照里都记着它）。YouranMod 自己的外设用裸名
     * （{@link Peripherals#KIND_HANZI} 等），别的模组用 {@code 模组名:名字}，免得撞名。
     */
    String peripheralKind(ItemStack stack);

    /**
     * 这块外设有没有自己的状态（状态存在物品 NBT 里，这样的外设不能堆叠）。大多数外设没有状态，可以堆叠。默认没有。
     */
    default boolean hasPeripheralState(ItemStack stack) {
        return false;
    }

    /**
     * 装上就有的能力（不占模拟器资源、由别处读取的，如定位模块的 {@link Peripherals#CAP_LOCATION}）；没有时空集。
     */
    default Set<String> peripheralCapabilities(ItemStack stack) {
        return Collections.emptySet();
    }

    /** 给玩家看的名字的翻译键（默认物品名的翻译键；客户端用 I18n、服务端用 ServerLang 翻） */
    default String peripheralNameKey(ItemStack stack) {
        return stack.getTranslationKey() + ".name";
    }
}
