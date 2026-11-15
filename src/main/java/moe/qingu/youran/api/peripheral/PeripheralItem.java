package moe.qingu.youran.api.peripheral;

import net.minecraft.item.Item;
import net.minecraft.item.ItemStack;

import java.util.Arrays;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.Set;

/**
 * 外设物品的基类：种类编号、能力固定，没有状态时可以堆叠。子类照常设注册名、翻译键、创造模式物品栏；要登记到
 * {@link Peripherals#register} 才能按种类找到（电脑按出厂配置放外设时用）。
 */
public class PeripheralItem extends Item implements Peripheral {
    private final String kind;
    private final Set<String> capabilities;

    /**
     * @param kind         种类编号（见 {@link Peripheral#peripheralKind}）
     * @param maxStackSize 堆叠数（有状态的外设应为 1）
     * @param capabilities 装上就有的能力（见 {@link Peripheral#peripheralCapabilities}）
     */
    public PeripheralItem(String kind, int maxStackSize, String... capabilities) {
        if (kind == null || kind.isEmpty()) {
            throw new IllegalArgumentException("外设种类编号不能是空的");
        }
        this.kind = kind;
        this.capabilities = Collections.unmodifiableSet(new LinkedHashSet<>(Arrays.asList(capabilities)));
        setMaxStackSize(maxStackSize);
    }

    @Override
    public String peripheralKind(ItemStack stack) {
        return kind;
    }

    @Override
    public Set<String> peripheralCapabilities(ItemStack stack) {
        return capabilities;
    }

    /** 这种外设的种类编号 */
    public String kind() {
        return kind;
    }
}
