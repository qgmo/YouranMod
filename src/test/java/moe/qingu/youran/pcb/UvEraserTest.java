package moe.qingu.youran.pcb;

import net.minecraft.init.Blocks;
import net.minecraft.init.Bootstrap;
import net.minecraft.init.Items;
import net.minecraft.item.ItemStack;
import net.minecraft.nbt.JsonToNBT;
import net.minecraft.nbt.NBTTagCompound;
import net.minecraft.util.NonNullList;
import net.minecraft.util.math.BlockPos;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class UvEraserTest {
    @BeforeAll
    static void boot() {
        Bootstrap.register();
        // 写 NBT 要方块实体登记过 id（游戏里由 ModBlocks 登记）
        net.minecraft.tileentity.TileEntity.register("youranmod:uv_eraser_test", TileUvEraser.class);
    }

    /** 服主 11-16：挖掉、炸掉就坏，机器本身不掉（精准采集也不掉）；里面的东西由 breakBlock 掉出 */
    @Test
    void machineItselfDropsNothing() {
        BlockUvEraser block = new BlockUvEraser("uv_eraser_test");
        NonNullList<ItemStack> drops = NonNullList.create();
        block.getDrops(drops, null, BlockPos.ORIGIN, block.getDefaultState(), 3);
        assertTrue(drops.isEmpty());
        assertFalse(block.canSilkHarvest(null, BlockPos.ORIGIN, block.getDefaultState(), null));
        // 还挖得动（Carry On 不搬挖不动的方块）
        assertTrue(block.getDefaultState().getBlockHardness(null, BlockPos.ORIGIN) >= 0);
    }

    /** Carry On 搬运：拿起时存方块实体的 NBT，放下时改了坐标再读回去；灯（带数据）、内容、电、进度都要原样接上 */
    @Test
    void carriedDataResumes() throws Exception {
        TileUvEraser a = new TileUvEraser();
        ItemStack lamp = new ItemStack(Items.PAPER);
        lamp.setTagCompound(JsonToNBT.getTagFromJson("{Used:10,MercuryWear:10,Glass:\"quartz\",Gas:\"argon\",Nines:2,Mercury:9}"));
        a.inv.setStackInSlot(TileUvEraser.LAMP, lamp);
        a.inv.setStackInSlot(TileUvEraser.WORK, new ItemStack(Items.BOOK, 1, 0));
        a.inv.setStackInSlot(TileUvEraser.FILM, new ItemStack(Blocks.GLASS_PANE));
        a.work = 5_432_100L;
        a.lampTicks = 777;
        a.energy.set(12_345);
        NBTTagCompound carried = a.writeToNBT(new NBTTagCompound());
        // 放到别处：Carry On 改 x / y / z 再读
        carried.setInteger("x", 100);
        carried.setInteger("y", 64);
        carried.setInteger("z", -30);
        TileUvEraser b = new TileUvEraser();
        b.readFromNBT(carried);
        assertEquals(new BlockPos(100, 64, -30), b.getPos());
        for (int i = 0; i < 3; i++) {
            assertTrue(ItemStack.areItemStacksEqual(a.inv.getStackInSlot(i), b.inv.getStackInSlot(i)), "格 " + i);
        }
        assertEquals(lamp.getTagCompound(), b.inv.getStackInSlot(TileUvEraser.LAMP).getTagCompound());
        assertEquals(5_432_100L, b.work, "读回内容时不能把进度清掉");
        assertEquals(777, b.lampTicks);
        assertEquals(12_345, b.energy.getEnergyStored());
    }
}
