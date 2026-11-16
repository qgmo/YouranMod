package moe.qingu.youran.pcb;

import moe.qingu.youran.item.LampNbt;
import moe.qingu.youran.item.ItemPcbFilm;
import moe.qingu.youran.logic.litho.LampSpec;
import moe.qingu.youran.logic.pcb.PcbNumbers;
import moe.qingu.youran.registry.ModItems;
import net.minecraft.entity.player.EntityPlayer;
import net.minecraft.item.ItemStack;
import net.minecraft.nbt.NBTTagCompound;
import net.minecraft.tileentity.TileEntity;
import net.minecraft.util.EnumFacing;
import net.minecraft.util.ITickable;
import net.minecraftforge.common.capabilities.Capability;
import net.minecraftforge.energy.CapabilityEnergy;
import net.minecraftforge.energy.EnergyStorage;
import net.minecraftforge.items.CapabilityItemHandler;
import net.minecraftforge.items.IItemHandler;
import net.minecraftforge.items.ItemStackHandler;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;

/**
 * 紫外擦除器（服主 11-16）：单方块，汞灯在界面里放（照光刻机的汞灯数据：亮着就用寿命、用汞，灯越好越快），工作格放 EPROM（擦除）
 * 或涂胶主板（配上底片格的线路底片曝光）。通电（FE）才亮。做完的东西留在工作格里（不再有活可做），拿走或从下面抽走。
 * <ul>
 *     <li>进度按“光”算：每 tick 加灯现在的亮度（0–1），够基准 tick 数（{@link UvTargets#workTicks}）就做完；</li>
 *     <li>灯亮着每 {@link PcbNumbers#UV_TICKS_PER_LAMP_USE} tick 算汞灯用了一次（同光刻机曝光一层）；</li>
 *     <li>中途拿走：这次不算（EPROM 内容不变、主板还是涂胶的），重新放进来从头开始。</li>
 * </ul>
 * 漏斗：从上面、侧面往工作格放（只收能做的东西），从下面只抽得出做完的。汞灯、底片只能手放。
 */
public class TileUvEraser extends TileEntity implements ITickable {
    public static final int LAMP = 0;
    public static final int WORK = 1;
    public static final int FILM = 2;

    /** 状态（界面显示，翻译键 youranmod.uv_eraser.status.*） */
    public enum Status {
        IDLE, NO_LAMP, LAMP_OUT, NO_MERCURY, NO_POWER, WORKING, DONE
    }

    final ItemStackHandler inv = new ItemStackHandler(3) {
        @Override
        public boolean isItemValid(int slot, @Nonnull ItemStack stack) {
            switch (slot) {
                case LAMP:
                    return stack.getItem() == ModItems.mercuryLamp;
                case WORK:
                    return UvTargets.acceptsWork(stack);
                case FILM:
                    return ItemPcbFilm.type(stack) != null;
                default:
                    return false;
            }
        }

        @Override
        public int getSlotLimit(int slot) {
            return 1;
        }

        @Override
        protected void onContentsChanged(int slot) {
            if (slot != LAMP) {
                work = 0;
            }
            markDirty();
        }
    };

    final UvEnergy energy = new UvEnergy();

    /** 电（FE）：外面只能往里充 */
    final class UvEnergy extends EnergyStorage {
        UvEnergy() {
            super(PcbNumbers.UV_ENERGY_CAPACITY, PcbNumbers.UV_ENERGY_INPUT, 0);
        }

        @Override
        public int receiveEnergy(int maxReceive, boolean simulate) {
            int got = super.receiveEnergy(maxReceive, simulate);
            if (got > 0 && !simulate) {
                markDirty();
            }
            return got;
        }

        boolean consume(int amount) {
            if (energy < amount) {
                return false;
            }
            energy -= amount;
            return true;
        }

        void set(int value) {
            energy = Math.max(0, Math.min(capacity, value));
        }
    }

    /** 已经积累的“光”（tick × 亮度，千分之一为单位） */
    long work;
    /** 灯这次亮了多少 tick（够一次用量就记到灯上） */
    int lampTicks;
    Status status = Status.IDLE;
    boolean lit;

    public IItemHandler inventory() {
        return inv;
    }

    public Status status() {
        return status;
    }

    public int energyStored() {
        return energy.getEnergyStored();
    }

    /** 进度（千分比） */
    public int progressPermille() {
        int need = UvTargets.workTicks(inv.getStackInSlot(WORK), inv.getStackInSlot(FILM));
        return need <= 0 ? 0 : (int) Math.min(1000, work / need);
    }

    @Override
    public void update() {
        if (world == null || world.isRemote) {
            return;
        }
        ItemStack target = inv.getStackInSlot(WORK);
        ItemStack film = inv.getStackInSlot(FILM);
        int need = UvTargets.workTicks(target, film);
        Status before = status;
        boolean wasLit = lit;
        lit = false;
        if (need <= 0) {
            status = target.isEmpty() ? Status.IDLE : Status.DONE;
            work = 0;
        } else {
            ItemStack lampStack = inv.getStackInSlot(LAMP);
            LampSpec lamp = lampStack.getItem() == ModItems.mercuryLamp ? LampNbt.read(lampStack.getTagCompound()) : null;
            if (lamp == null || lamp.gas == null) {
                status = Status.NO_LAMP;
            } else if (lamp.burntOut()) {
                status = Status.LAMP_OUT;
            } else if (lamp.mercury <= 0) {
                status = Status.NO_MERCURY;
            } else if (!energy.consume(PcbNumbers.UV_ENERGY_PER_TICK)) {
                status = Status.NO_POWER;
            } else {
                status = Status.WORKING;
                lit = true;
                work += Math.round(lamp.brightness() * 1000);
                if (++lampTicks >= PcbNumbers.UV_TICKS_PER_LAMP_USE) {
                    lampTicks = 0;
                    lampStack.setTagCompound(LampNbt.tag(lamp.afterExposure()));
                }
                if (work >= need * 1000L) {
                    ItemStack done = UvTargets.finished(target, film);
                    work = 0;
                    inv.setStackInSlot(WORK, done);
                    status = Status.DONE;
                }
                markDirty();
            }
        }
        if (before != status || wasLit != lit) {
            markDirty();
        }
    }

    public boolean usableBy(EntityPlayer player) {
        return !isInvalid() && world.getTileEntity(pos) == this && player.getDistanceSq(pos) <= 64;
    }

    @Override
    public void readFromNBT(NBTTagCompound tag) {
        super.readFromNBT(tag);
        inv.deserializeNBT(tag.getCompoundTag("Inv"));
        energy.set(tag.getInteger("Energy"));
        work = tag.getLong("Work");
        lampTicks = tag.getInteger("LampTicks");
    }

    @Override
    public NBTTagCompound writeToNBT(NBTTagCompound tag) {
        super.writeToNBT(tag);
        tag.setTag("Inv", inv.serializeNBT());
        tag.setInteger("Energy", energy.getEnergyStored());
        tag.setLong("Work", work);
        tag.setInteger("LampTicks", lampTicks);
        return tag;
    }

    @Override
    public boolean hasCapability(Capability<?> cap, @Nullable EnumFacing facing) {
        return cap == CapabilityEnergy.ENERGY || cap == CapabilityItemHandler.ITEM_HANDLER_CAPABILITY || super.hasCapability(cap, facing);
    }

    @Nullable
    @Override
    public <T> T getCapability(Capability<T> cap, @Nullable EnumFacing facing) {
        if (cap == CapabilityEnergy.ENERGY) {
            return CapabilityEnergy.ENERGY.cast(energy);
        }
        if (cap == CapabilityItemHandler.ITEM_HANDLER_CAPABILITY) {
            return CapabilityItemHandler.ITEM_HANDLER_CAPABILITY.cast(new Automation(facing == EnumFacing.DOWN));
        }
        return super.getCapability(cap, facing);
    }

    /** 漏斗：只碰工作格；下面只抽做完的，别的面只放 */
    final class Automation implements IItemHandler {
        private final boolean bottom;

        Automation(boolean bottom) {
            this.bottom = bottom;
        }

        @Override
        public int getSlots() {
            return 1;
        }

        @Nonnull
        @Override
        public ItemStack getStackInSlot(int slot) {
            return inv.getStackInSlot(WORK);
        }

        @Nonnull
        @Override
        public ItemStack insertItem(int slot, @Nonnull ItemStack stack, boolean simulate) {
            if (bottom || UvTargets.workTicks(stack, inv.getStackInSlot(FILM)) <= 0) {
                return stack;
            }
            return inv.insertItem(WORK, stack, simulate);
        }

        @Nonnull
        @Override
        public ItemStack extractItem(int slot, int amount, boolean simulate) {
            ItemStack s = inv.getStackInSlot(WORK);
            if (!bottom || s.isEmpty() || UvTargets.workTicks(s, inv.getStackInSlot(FILM)) > 0) {
                return ItemStack.EMPTY;
            }
            return inv.extractItem(WORK, amount, simulate);
        }

        @Override
        public int getSlotLimit(int slot) {
            return 1;
        }
    }
}
