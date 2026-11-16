package moe.qingu.youran.litho;

import moe.qingu.youran.item.LampNbt;
import moe.qingu.youran.logic.litho.LampSpec;
import net.minecraft.block.state.IBlockState;
import net.minecraft.nbt.NBTTagCompound;
import net.minecraft.network.NetworkManager;
import net.minecraft.network.play.server.SPacketUpdateTileEntity;
import net.minecraft.tileentity.TileEntity;
import net.minecraft.util.math.BlockPos;
import net.minecraft.world.World;

import javax.annotation.Nullable;

/**
 * 汞灯光源的方块实体（设计稿 3.3）：数据一直待在这里，成形、拆开都不搬家，所以不丢不刷。
 * 存原始量（玻璃管、铸模 / 纯度、已用量，见 {@link LampNbt}），亮度、寿命现算；另记接入的主方块位置（成形时设，拆开时清）。
 */
public class TileLightSource extends TileEntity {
    private NBTTagCompound lamp = LampNbt.tag(LampNbt.read(null));
    @Nullable
    private BlockPos master;
    /** 正在曝光（客户端用来发光） */
    private boolean lit;
    /** 被创造模式玩家敲掉时不掉物品（和原版一致） */
    boolean noDrop;

    public LampSpec spec() {
        return LampNbt.read(lamp);
    }

    /** 物品放下时把数据写回来（{@code onBlockPlacedBy}） */
    public void setLampData(@Nullable NBTTagCompound data) {
        lamp = LampNbt.tag(LampNbt.read(data));
        markDirty();
    }

    /** 掉落物品用的数据 */
    public NBTTagCompound lampData() {
        return lamp.copy();
    }

    /** 曝光一层：已用量加 1，汞照用量扣 */
    public void addUse() {
        lamp = LampNbt.tag(spec().afterExposure());
        markDirty();
    }

    @Nullable
    public BlockPos master() {
        return master;
    }

    public void link(@Nullable BlockPos masterPos) {
        this.master = masterPos;
        if (masterPos == null) {
            setLit(false);
        }
        markDirty();
        sync();
    }

    public boolean lit() {
        return lit;
    }

    public void setLit(boolean on) {
        if (lit != on) {
            lit = on;
            sync();
            if (world != null) {
                world.checkLight(pos);
            }
        }
    }

    private void sync() {
        if (world != null && !world.isRemote) {
            IBlockState s = world.getBlockState(pos);
            world.notifyBlockUpdate(pos, s, s, 3);
        }
    }

    @Override
    public void readFromNBT(NBTTagCompound nbt) {
        super.readFromNBT(nbt);
        readCustom(nbt);
    }

    @Override
    public NBTTagCompound writeToNBT(NBTTagCompound nbt) {
        super.writeToNBT(nbt);
        writeCustom(nbt);
        return nbt;
    }

    private void readCustom(NBTTagCompound nbt) {
        lamp = LampNbt.tag(LampNbt.read(nbt.getCompoundTag("Lamp")));
        master = nbt.hasKey("Master") ? BlockPos.fromLong(nbt.getLong("Master")) : null;
        lit = nbt.getBoolean("Lit");
    }

    private void writeCustom(NBTTagCompound nbt) {
        nbt.setTag("Lamp", lamp.copy());
        if (master != null) {
            nbt.setLong("Master", master.toLong());
        }
        nbt.setBoolean("Lit", lit);
    }

    @Override
    public NBTTagCompound getUpdateTag() {
        return writeToNBT(new NBTTagCompound());
    }

    @Override
    public SPacketUpdateTileEntity getUpdatePacket() {
        NBTTagCompound tag = new NBTTagCompound();
        writeCustom(tag);
        return new SPacketUpdateTileEntity(pos, 0, tag);
    }

    @Override
    public void onDataPacket(NetworkManager net, SPacketUpdateTileEntity pkt) {
        boolean wasLit = lit;
        readCustom(pkt.getNbtCompound());
        if (wasLit != lit && world != null) {
            world.checkLight(pos);
        }
    }

    @Override
    public boolean shouldRefresh(World world, BlockPos pos, IBlockState oldState, IBlockState newState) {
        return oldState.getBlock() != newState.getBlock();
    }
}
