package moe.qingu.youran.api.peripheral;

import net.minecraft.item.ItemStack;

import java.util.List;

/**
 * 能装外设的电脑（由方块实体实现）：YouranSCP 的电脑、家具电脑、以后别的模组的电脑。{@link Peripherals#installed} 等按它查。
 */
public interface PeripheralHost {
    /** 装着的外设物品（只读；空格不算）。在服务端调用。 */
    List<ItemStack> installedPeripherals();

    /**
     * 这台电脑收不收这种外设（外设槽据此拒收；默认都收）。电脑认哪几种由电脑自己定，如个人电脑收软驱、汉卡，普通电脑只收汉卡。
     */
    default boolean acceptsPeripheral(String kind) {
        return true;
    }
}
