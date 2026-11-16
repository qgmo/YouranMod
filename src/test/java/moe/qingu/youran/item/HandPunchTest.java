package moe.qingu.youran.item;

import net.minecraft.entity.player.EntityPlayer;
import net.minecraft.init.Bootstrap;
import net.minecraft.init.Items;
import net.minecraft.inventory.Container;
import net.minecraft.inventory.InventoryCraftResult;
import net.minecraft.inventory.InventoryCrafting;
import net.minecraft.inventory.SlotCrafting;
import net.minecraft.item.ItemStack;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.util.Random;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class HandPunchTest {
    @BeforeAll
    static void boot() {
        Bootstrap.register();
    }

    static final class Bare extends Container {
        @Override
        public boolean canInteractWith(EntityPlayer playerIn) {
            return false;
        }
    }

    /** 自动合成（RFTools 合成器那样的空界面）算机器合成；有“合成结果”格的界面算玩家合成；沉浸工程装配机设样板的不算 */
    @Test
    void tellsMachineFromPlayerCrafting() {
        InventoryCrafting machine = new InventoryCrafting(new Bare(), 3, 3);
        assertTrue(HandPunch.isMachineCrafting(machine));

        Bare gui = new Bare();
        InventoryCrafting grid = new InventoryCrafting(gui, 3, 3);
        gui.inventorySlots.add(new SlotCrafting(null, grid, new InventoryCraftResult(), 0, 0, 0));
        assertFalse(HandPunch.isMachineCrafting(grid));

        assertFalse(HandPunch.isMachineCrafting(null));
    }

    /** 掷过就去掉标记；概率 100% 一定变成坏的，0% 一定不变 */
    @Test
    void resolveRemovesTheMark() {
        ItemStack sure = HandPunch.mark(new ItemStack(Items.PAPER, 1, 0), 5, 1.0);
        HandPunch.resolve(sure, new Random(1));
        assertEquals(5, sure.getItemDamage());
        assertFalse(sure.hasTagCompound());

        ItemStack never = HandPunch.mark(new ItemStack(Items.PAPER, 1, 0), 5, 0.0);
        HandPunch.resolve(never, new Random(1));
        assertEquals(0, never.getItemDamage());
        assertFalse(never.hasTagCompound());
    }
}
