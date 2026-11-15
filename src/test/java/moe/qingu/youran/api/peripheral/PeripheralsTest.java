package moe.qingu.youran.api.peripheral;

import net.minecraft.init.Bootstrap;
import net.minecraft.item.Item;
import net.minecraft.item.ItemStack;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.util.Arrays;
import java.util.Collections;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class PeripheralsTest {
    @BeforeAll
    static void boot() {
        Bootstrap.register();
    }

    /** 外设物品：种类、能力、没状态时可堆叠；查询按种类去重排序、不是外设的不算 */
    @Test
    void kindsAndCapabilities() {
        PeripheralItem floppy = new PeripheralItem(Peripherals.KIND_FLOPPY, 16);
        PeripheralItem hanzi = new PeripheralItem(Peripherals.KIND_HANZI, 16);
        PeripheralItem locator = new PeripheralItem(Peripherals.KIND_LOCATOR, 16, Peripherals.CAP_LOCATION);
        ItemStack f = new ItemStack(floppy);
        assertEquals(16, floppy.getItemStackLimit(f));
        assertFalse(floppy.hasPeripheralState(f));
        assertEquals(Peripherals.KIND_FLOPPY, Peripherals.kind(f));
        assertNull(Peripherals.kind(new ItemStack(new Item())), "不是外设");
        assertNull(Peripherals.kind(ItemStack.EMPTY));
        List<ItemStack> installed = Arrays.asList(new ItemStack(hanzi), ItemStack.EMPTY, f, new ItemStack(new Item()), new ItemStack(hanzi));
        assertEquals(Arrays.asList("floppy", "hanzi"), new java.util.ArrayList<>(Peripherals.kinds(installed)));
        assertFalse(Peripherals.hasCapability(installed, Peripherals.CAP_LOCATION));
        // 只是“装了就有某项能力”的外设（定位模块）：没有 I/O 端口，能力由别处读
        assertTrue(Peripherals.hasCapability(Collections.singletonList(new ItemStack(locator)), Peripherals.CAP_LOCATION));
        assertThrows(IllegalArgumentException.class, () -> new PeripheralItem("", 16));
    }

    /** 按种类登记、生成（电脑按出厂配置放外设时用） */
    @Test
    void registry() {
        PeripheralItem drive = new PeripheralItem("test:drive", 16);
        Peripherals.register(drive);
        assertSame(drive, Peripherals.create("test:drive").getItem());
        assertTrue(Peripherals.create("test:nosuch").isEmpty());
        assertTrue(Peripherals.knownKinds().contains("test:drive"));
        assertThrows(IllegalArgumentException.class, () -> Peripherals.register(new Item()));
    }

    /** 电脑（方块实体）报装了哪些；不是电脑的方块实体没有 */
    @Test
    void host() {
        PeripheralItem locator = new PeripheralItem(Peripherals.KIND_LOCATOR, 16, Peripherals.CAP_LOCATION);
        class FurnitureComputer extends net.minecraft.tileentity.TileEntity implements PeripheralHost {
            @Override
            public List<ItemStack> installedPeripherals() {
                return Collections.singletonList(new ItemStack(locator));
            }

            @Override
            public boolean acceptsPeripheral(String kind) {
                return Peripherals.KIND_LOCATOR.equals(kind);
            }
        }
        FurnitureComputer pc = new FurnitureComputer();
        assertSame(pc, Peripherals.host(pc));
        assertNull(Peripherals.host(new net.minecraft.tileentity.TileEntityChest()));
        assertTrue(Peripherals.hasCapability(Peripherals.host(pc).installedPeripherals(), Peripherals.CAP_LOCATION));
        assertFalse(pc.acceptsPeripheral(Peripherals.KIND_FLOPPY));
    }
}
