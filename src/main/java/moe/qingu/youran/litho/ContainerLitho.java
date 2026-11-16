package moe.qingu.youran.litho;

import moe.qingu.youran.net.LithoMessages;
import moe.qingu.youran.net.YouranNetwork;
import net.minecraft.entity.player.EntityPlayer;
import net.minecraft.entity.player.EntityPlayerMP;
import net.minecraft.entity.player.InventoryPlayer;
import net.minecraft.inventory.Container;
import net.minecraft.inventory.IContainerListener;
import net.minecraft.inventory.Slot;
import net.minecraft.item.ItemStack;
import net.minecraft.nbt.NBTTagCompound;
import net.minecraftforge.items.SlotItemHandler;

/**
 * 光刻机主界面的容器：掩模槽（只在这里出现）、五个罐的容器格、晶圆、辅料、外壳、芯片输出，加玩家背包。
 * 罐、电、工序、对准率、光源这些数据由服务端每隔几 tick 打包发过来（变了才发）。
 */
public class ContainerLitho extends Container implements LithoSyncTarget {
    public static final int WIDTH = 240;
    public static final int HEIGHT = 240;
    /** 五个罐的格子的 x（罐的计量条在格子正上方） */
    public static final int[] TANK_X = {8, 30, 52, 74, 100};
    public static final int TANK_Y = 18;
    public static final int TANK_H = 52;
    public static final int CONTAINER_Y = 74;
    public static final int PLAYER_X = 40;
    public static final int PLAYER_Y = 158;

    public final TileLithoMaster master;
    private NBTTagCompound lastSent;
    private int ticks;
    /** 客户端：服务端发来的数据 */
    public NBTTagCompound data = new NBTTagCompound();

    public ContainerLitho(InventoryPlayer playerInv, TileLithoMaster master) {
        this.master = master;
        net.minecraftforge.items.IItemHandler inv = master.inventory();
        addSlotToContainer(new SlotItemHandler(inv, LithoSlots.MASK, 132, 18));
        for (int i = 0; i < LithoSlots.CONTAINERS; i++) {
            addSlotToContainer(new SlotItemHandler(inv, LithoSlots.CONTAINER_FIRST + i, TANK_X[i], CONTAINER_Y));
        }
        addSlotToContainer(new SlotItemHandler(inv, LithoSlots.WAFER_FIRST, 162, 18));
        addSlotToContainer(new SlotItemHandler(inv, LithoSlots.WAFER_FIRST + 1, 180, 18));
        int[] aux = {LithoSlots.BORON_INGOT, LithoSlots.GLOWSTONE, LithoSlots.BORON_ELEMENT, LithoSlots.PHOSPHORUS, LithoSlots.ALUMINUM, LithoSlots.CASING};
        for (int i = 0; i < aux.length; i++) {
            addSlotToContainer(new SlotItemHandler(inv, aux[i], 132 + i * 18, 42));
        }
        for (int i = 0; i < LithoSlots.OUTPUTS; i++) {
            addSlotToContainer(new SlotItemHandler(inv, LithoSlots.OUTPUT_FIRST + i, 162 + i * 18, 66));
        }
        for (int r = 0; r < 3; r++) {
            for (int c = 0; c < 9; c++) {
                addSlotToContainer(new Slot(playerInv, c + r * 9 + 9, PLAYER_X + c * 18, PLAYER_Y + r * 18));
            }
        }
        for (int c = 0; c < 9; c++) {
            addSlotToContainer(new Slot(playerInv, c, PLAYER_X + c * 18, PLAYER_Y + 58));
        }
    }

    int machineSlots() {
        return 1 + LithoSlots.CONTAINERS + LithoSlots.WAFERS + 6 + LithoSlots.OUTPUTS;
    }

    @Override
    public boolean canInteractWith(EntityPlayer player) {
        return master.usableBy(player);
    }

    @Override
    public ItemStack transferStackInSlot(EntityPlayer player, int index) {
        Slot slot = inventorySlots.get(index);
        if (slot == null || !slot.getHasStack()) {
            return ItemStack.EMPTY;
        }
        ItemStack stack = slot.getStack();
        ItemStack copy = stack.copy();
        int machine = machineSlots();
        if (index < machine) {
            if (!mergeItemStack(stack, machine, inventorySlots.size(), true)) {
                return ItemStack.EMPTY;
            }
        } else {
            boolean moved = false;
            for (int i = 0; i < machine && !stack.isEmpty(); i++) {
                Slot target = inventorySlots.get(i);
                if (target.isItemValid(stack)) {
                    int before = stack.getCount();
                    mergeItemStack(stack, i, i + 1, false);
                    moved |= stack.getCount() != before;
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

    @Override
    public void detectAndSendChanges() {
        super.detectAndSendChanges();
        if (master.getWorld().isRemote || ticks++ % 4 != 0) {
            return;
        }
        NBTTagCompound now = master.guiData();
        if (now.equals(lastSent)) {
            return;
        }
        lastSent = now;
        for (IContainerListener l : listeners) {
            if (l instanceof EntityPlayerMP) {
                YouranNetwork.CHANNEL.sendTo(new LithoMessages.Sync(windowId, now), (EntityPlayerMP) l);
            }
        }
    }

    @Override
    public void addListener(IContainerListener listener) {
        super.addListener(listener);
        lastSent = null;
    }

    @Override
    public void receive(NBTTagCompound data) {
        this.data = data;
    }
}
