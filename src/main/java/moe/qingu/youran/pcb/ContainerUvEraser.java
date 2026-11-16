package moe.qingu.youran.pcb;

import net.minecraft.entity.player.EntityPlayer;
import net.minecraft.entity.player.InventoryPlayer;
import net.minecraft.inventory.Container;
import net.minecraft.inventory.IContainerListener;
import net.minecraft.inventory.Slot;
import net.minecraft.item.ItemStack;
import net.minecraftforge.fml.relauncher.Side;
import net.minecraftforge.fml.relauncher.SideOnly;
import net.minecraftforge.items.SlotItemHandler;

/** 紫外擦除器的界面：汞灯、工作格、底片 + 玩家物品栏；状态、进度、电量照熔炉的办法（窗口属性）同步 */
public class ContainerUvEraser extends Container {
    /** 窗口属性：0 状态（{@link TileUvEraser.Status} 的序号）、1 进度千分比、2 电量（FE） */
    public static final int VALUES = 3;
    public final TileUvEraser tile;
    private final int[] values = new int[VALUES];
    private final int[] sent = {Integer.MIN_VALUE, Integer.MIN_VALUE, Integer.MIN_VALUE};

    public static final int LAMP_X = 26;
    public static final int WORK_X = 80;
    public static final int FILM_X = 80;
    public static final int LAMP_Y = 36;
    public static final int WORK_Y = 24;
    public static final int FILM_Y = 50;

    public ContainerUvEraser(InventoryPlayer inventory, TileUvEraser tile) {
        this.tile = tile;
        addSlotToContainer(new SlotItemHandler(tile.inv, TileUvEraser.LAMP, LAMP_X, LAMP_Y));
        addSlotToContainer(new SlotItemHandler(tile.inv, TileUvEraser.WORK, WORK_X, WORK_Y));
        addSlotToContainer(new SlotItemHandler(tile.inv, TileUvEraser.FILM, FILM_X, FILM_Y));
        for (int row = 0; row < 3; row++) {
            for (int col = 0; col < 9; col++) {
                addSlotToContainer(new Slot(inventory, col + row * 9 + 9, 8 + col * 18, 84 + row * 18));
            }
        }
        for (int col = 0; col < 9; col++) {
            addSlotToContainer(new Slot(inventory, col, 8 + col * 18, 142));
        }
    }

    @Override
    public boolean canInteractWith(EntityPlayer player) {
        return tile.usableBy(player);
    }

    @Override
    public void detectAndSendChanges() {
        super.detectAndSendChanges();
        int[] now = {tile.status().ordinal(), tile.progressPermille(), tile.energyStored()};
        for (int i = 0; i < VALUES; i++) {
            values[i] = now[i];
            if (now[i] != sent[i]) {
                for (IContainerListener l : listeners) {
                    l.sendWindowProperty(this, i, now[i]);
                }
                sent[i] = now[i];
            }
        }
    }

    @Override
    public void addListener(IContainerListener listener) {
        super.addListener(listener);
        for (int i = 0; i < VALUES; i++) {
            sent[i] = Integer.MIN_VALUE;
        }
    }

    @SideOnly(Side.CLIENT)
    @Override
    public void updateProgressBar(int id, int data) {
        if (id >= 0 && id < VALUES) {
            values[id] = data;
        }
    }

    public int value(int id) {
        return values[id];
    }

    @Override
    public ItemStack transferStackInSlot(EntityPlayer player, int index) {
        Slot slot = inventorySlots.get(index);
        if (slot == null || !slot.getHasStack()) {
            return ItemStack.EMPTY;
        }
        ItemStack stack = slot.getStack();
        ItemStack copy = stack.copy();
        if (index < 3) {
            if (!mergeItemStack(stack, 3, inventorySlots.size(), true)) {
                return ItemStack.EMPTY;
            }
        } else {
            boolean moved = false;
            for (int i = 0; i < 3 && !moved; i++) {
                Slot s = inventorySlots.get(i);
                if (!s.getHasStack() && s.isItemValid(stack)) {
                    s.putStack(stack.splitStack(1));
                    moved = true;
                }
            }
            if (!moved) {
                return ItemStack.EMPTY;
            }
        }
        if (stack.isEmpty()) {
            slot.putStack(ItemStack.EMPTY);
        } else {
            slot.onSlotChanged();
        }
        return copy;
    }
}
