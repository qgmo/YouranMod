package moe.qingu.youran.litho;

import moe.qingu.youran.logic.litho.LithoStructure;
import moe.qingu.youran.logic.litho.LithoStructure.Part;
import moe.qingu.youran.logic.litho.LithoStructure.Port;
import net.minecraft.block.state.IBlockState;
import net.minecraft.nbt.NBTTagCompound;
import net.minecraft.network.NetworkManager;
import net.minecraft.network.play.server.SPacketUpdateTileEntity;
import net.minecraft.tileentity.TileEntity;
import net.minecraft.util.EnumFacing;
import net.minecraft.util.math.BlockPos;
import net.minecraft.world.World;
import net.minecraftforge.common.capabilities.Capability;
import net.minecraftforge.energy.CapabilityEnergy;
import net.minecraftforge.fluids.capability.CapabilityFluidHandler;
import net.minecraftforge.items.CapabilityItemHandler;

import javax.annotation.Nullable;

/**
 * 成形后光刻机的一格（主方块见 {@link TileLithoMaster}）。记自己在结构里的格子、朝向、镜像、主方块的位置；
 * 接口格按“格 + 朝向”把流体、物品、电的能力转给主方块。不 tick。
 *
 * <p>不继承沉浸工程的方块实体（设计稿四.2 建议继承 {@code TileEntityMultiblockMetal}，实现时改了，见交付文档）：
 * 光刻机的十步工序、对准、掩模槽都不用它的配方队列，自己写更直接；也避开了它的格编号字段 {@code pos} 与原版坐标字段同名。
 */
public class TileLithoPart extends TileEntity {
    protected boolean formed;
    /** 格编号（{@link LithoStructure#index}） */
    protected int index = -1;
    /** 主方块相对这一格的位置 */
    protected BlockPos masterOffset = BlockPos.ORIGIN;
    /** 从正面往背面的方向（l 增加的方向） */
    protected EnumFacing facing = EnumFacing.NORTH;
    protected boolean mirrored;

    void setup(int index, BlockPos masterPos, EnumFacing facing, boolean mirrored) {
        this.formed = true;
        this.index = index;
        this.masterOffset = masterPos.subtract(pos);
        this.facing = facing;
        this.mirrored = mirrored;
        markDirty();
    }

    public boolean formed() {
        return formed;
    }

    public EnumFacing facing() {
        return facing;
    }

    public boolean mirrored() {
        return mirrored;
    }

    public int h() {
        return LithoStructure.hOf(index);
    }

    public int l() {
        return LithoStructure.lOf(index);
    }

    public int w() {
        return LithoStructure.wOf(index);
    }

    /** 形状键（{@link LithoBounds#key}）；没成形或还没收到数据时为 −1 */
    public int shapeKey() {
        return formed && index >= 0 ? LithoBounds.key(index, facing, mirrored) : -1;
    }

    public BlockPos masterPos() {
        return pos.add(masterOffset);
    }

    @Nullable
    public Port port() {
        return index < 0 ? null : Port.at(h(), l(), w());
    }

    public Part part() {
        return Part.byMeta(getBlockMetadata());
    }

    /** w 增加的方向（站在正面看往右；镜像时往左） */
    public static EnumFacing wDir(EnumFacing facing, boolean mirrored) {
        return mirrored ? facing.rotateYCCW() : facing.rotateY();
    }

    /** 接口朝外的那一面；各面都收的接口与不是接口的格子为 null */
    @Nullable
    public EnumFacing outward() {
        Port p = port();
        if (p == null) {
            return null;
        }
        switch (p.face) {
            case BACK:
                return facing;
            case LEFT:
                return wDir(facing, mirrored).getOpposite();
            case RIGHT:
                return wDir(facing, mirrored);
            default:
                return null;
        }
    }

    @Nullable
    public TileLithoMaster master() {
        if (!formed || world == null) {
            return null;
        }
        BlockPos m = masterPos();
        if (!world.isBlockLoaded(m)) {
            return null;
        }
        TileEntity te = world.getTileEntity(m);
        return te instanceof TileLithoMaster && ((TileLithoMaster) te).formed ? (TileLithoMaster) te : null;
    }

    /** 这一面（null 表示不分面）认不认这个接口 */
    private boolean portSide(Port p, @Nullable EnumFacing side) {
        if (p.face == LithoStructure.Face.ANY || side == null) {
            return true;
        }
        return side == outward();
    }

    @Override
    public boolean hasCapability(Capability<?> capability, @Nullable EnumFacing side) {
        return getCapability(capability, side) != null || super.hasCapability(capability, side);
    }

    @Nullable
    @Override
    public <T> T getCapability(Capability<T> capability, @Nullable EnumFacing side) {
        Port p = port();
        if (p != null && formed && portSide(p, side)) {
            TileLithoMaster m = master();
            if (m != null) {
                if (capability == CapabilityFluidHandler.FLUID_HANDLER_CAPABILITY && p.isFluid()) {
                    return CapabilityFluidHandler.FLUID_HANDLER_CAPABILITY.cast(m.fluidPort(p));
                }
                if (capability == CapabilityEnergy.ENERGY && p == Port.ENERGY) {
                    return CapabilityEnergy.ENERGY.cast(m.energy());
                }
                if (capability == CapabilityItemHandler.ITEM_HANDLER_CAPABILITY && (p == Port.ITEM_IN || p == Port.ITEM_OUT)) {
                    return CapabilityItemHandler.ITEM_HANDLER_CAPABILITY.cast(p == Port.ITEM_IN ? m.itemInput() : m.itemOutput());
                }
            }
        }
        return super.getCapability(capability, side);
    }

    // ---- 存档与同步 ----

    @Override
    public void readFromNBT(NBTTagCompound nbt) {
        super.readFromNBT(nbt);
        readPart(nbt);
    }

    @Override
    public NBTTagCompound writeToNBT(NBTTagCompound nbt) {
        super.writeToNBT(nbt);
        writePart(nbt);
        return nbt;
    }

    protected void readPart(NBTTagCompound nbt) {
        formed = nbt.getBoolean("Formed");
        index = nbt.getInteger("Index");
        masterOffset = BlockPos.fromLong(nbt.getLong("MasterOffset"));
        facing = EnumFacing.byHorizontalIndex(nbt.getInteger("Facing"));
        mirrored = nbt.getBoolean("Mirrored");
    }

    protected void writePart(NBTTagCompound nbt) {
        nbt.setBoolean("Formed", formed);
        nbt.setInteger("Index", index);
        nbt.setLong("MasterOffset", masterOffset.toLong());
        nbt.setInteger("Facing", facing.getHorizontalIndex());
        nbt.setBoolean("Mirrored", mirrored);
    }

    @Override
    public NBTTagCompound getUpdateTag() {
        NBTTagCompound tag = super.writeToNBT(new NBTTagCompound());
        writePart(tag);
        return tag;
    }

    @Override
    public SPacketUpdateTileEntity getUpdatePacket() {
        NBTTagCompound tag = new NBTTagCompound();
        writePart(tag);
        return new SPacketUpdateTileEntity(pos, 0, tag);
    }

    @Override
    public void onDataPacket(NetworkManager net, SPacketUpdateTileEntity pkt) {
        readPart(pkt.getNbtCompound());
        if (world != null) {
            world.markBlockRangeForRenderUpdate(pos, pos);
        }
    }

    @Override
    public boolean shouldRefresh(World world, BlockPos pos, IBlockState oldState, IBlockState newState) {
        return oldState.getBlock() != newState.getBlock();
    }
}
