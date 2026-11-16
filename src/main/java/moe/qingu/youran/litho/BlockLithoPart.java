package moe.qingu.youran.litho;

import moe.qingu.youran.logic.litho.LithoStructure.Part;
import moe.qingu.youran.logic.litho.LithoStructure.Port;
import net.minecraft.block.Block;
import net.minecraft.block.SoundType;
import net.minecraft.block.material.Material;
import net.minecraft.block.properties.PropertyDirection;
import net.minecraft.block.properties.PropertyEnum;
import net.minecraft.block.state.BlockFaceShape;
import net.minecraft.block.state.BlockStateContainer;
import net.minecraft.block.state.IBlockState;
import net.minecraft.entity.player.EntityPlayer;
import net.minecraft.item.ItemStack;
import net.minecraft.tileentity.TileEntity;
import net.minecraft.util.BlockRenderLayer;
import net.minecraft.util.EnumFacing;
import net.minecraft.util.EnumHand;
import net.minecraft.util.IStringSerializable;
import net.minecraft.util.NonNullList;
import net.minecraft.util.math.AxisAlignedBB;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.RayTraceResult;
import net.minecraft.util.text.TextComponentTranslation;
import net.minecraft.world.IBlockAccess;
import net.minecraft.world.World;

import javax.annotation.Nullable;
import java.util.Locale;

/**
 * 成形后的光刻机方块（设计稿四.3：不能继承沉浸工程的方块类，自己写）。元数据是这一格原来是什么（{@link Part}，16 种），
 * 成形后照原来的样子画（沉浸工程的贴图），接口格加上蓝点（入）或橙点（出）。朝向不进元数据，由方块实体给（实际状态）。
 *
 * <p>右键：对准台、黄玻璃开光刻机界面（光源那格见 {@link BlockLightSource}）；拿沉浸工程锤子敲红石口反相。
 * 被敲掉：整台拆开（{@link TileLithoMaster#disassemble}），这一格掉原来的方块；主方块的物品格掉出来。
 */
public class BlockLithoPart extends Block {
    public enum PartProp implements IStringSerializable {
        STEEL_SHEET, IRON_SHEET, SCAFFOLDING, HEAVY, LIGHT, REDSTONE, BLAST_BRICK, RADIATOR, PIPE, YELLOW_GLASS, GLASS, FENCE,
        HOPPER, DROPPER, ALIGNER, PIPE_OUT;

        @Override
        public String getName() {
            return name().toLowerCase(Locale.ROOT);
        }

        Part part() {
            return Part.values()[ordinal()];
        }
    }

    public static final PropertyEnum<PartProp> PART = PropertyEnum.create("part", PartProp.class);
    public static final PropertyDirection FACE = PropertyDirection.create("face", EnumFacing.Plane.HORIZONTAL);
    static final AxisAlignedBB POST = new AxisAlignedBB(0.375, 0, 0.375, 0.625, 1, 0.625);

    public BlockLithoPart(String name) {
        super(Material.IRON);
        setRegistryName(name);
        setTranslationKey(name);
        setHardness(3.0F);
        setResistance(15.0F);
        setSoundType(SoundType.METAL);
        setDefaultState(blockState.getBaseState().withProperty(PART, PartProp.STEEL_SHEET).withProperty(FACE, EnumFacing.NORTH));
    }

    @Override
    protected BlockStateContainer createBlockState() {
        return new BlockStateContainer(this, PART, FACE);
    }

    @Override
    public int getMetaFromState(IBlockState state) {
        return state.getValue(PART).ordinal();
    }

    @SuppressWarnings("deprecation")
    @Override
    public IBlockState getStateFromMeta(int meta) {
        return getDefaultState().withProperty(PART, PartProp.values()[Math.max(0, Math.min(15, meta))]);
    }

    public IBlockState stateFor(Part part) {
        return getDefaultState().withProperty(PART, PartProp.values()[part.ordinal()]);
    }

    static Part part(IBlockState state) {
        return state.getValue(PART).part();
    }

    @SuppressWarnings("deprecation")
    @Override
    public IBlockState getActualState(IBlockState state, IBlockAccess world, BlockPos pos) {
        TileEntity te = world instanceof net.minecraft.world.ChunkCache
                ? ((net.minecraft.world.ChunkCache) world).getTileEntity(pos, net.minecraft.world.chunk.Chunk.EnumCreateEntityType.CHECK)
                : world.getTileEntity(pos);
        EnumFacing face = EnumFacing.NORTH;
        if (te instanceof TileLithoPart) {
            TileLithoPart p = (TileLithoPart) te;
            EnumFacing out = p.outward();
            face = out != null ? out : p.facing();
        }
        return state.withProperty(FACE, face);
    }

    @Override
    public boolean hasTileEntity(IBlockState state) {
        return true;
    }

    @Override
    public TileEntity createTileEntity(World world, IBlockState state) {
        return part(state) == Part.ALIGNER ? new TileLithoMaster() : new TileLithoPart();
    }

    // ---- 渲染、形状 ----

    @Override
    public boolean canRenderInLayer(IBlockState state, BlockRenderLayer layer) {
        Part p = part(state);
        if (p.translucent()) {
            return layer == BlockRenderLayer.TRANSLUCENT;
        }
        if (p.cutout() || p == Part.FENCE) {
            return layer == BlockRenderLayer.CUTOUT;
        }
        return layer == BlockRenderLayer.SOLID;
    }

    @SuppressWarnings("deprecation")
    @Override
    public boolean isOpaqueCube(IBlockState state) {
        Part p = part(state);
        return !(p.translucent() || p.cutout() || p == Part.FENCE);
    }

    @SuppressWarnings("deprecation")
    @Override
    public boolean isFullCube(IBlockState state) {
        return part(state) != Part.FENCE;
    }

    @SuppressWarnings("deprecation")
    @Override
    public int getLightOpacity(IBlockState state) {
        Part p = part(state);
        return p == Part.GLASS || p == Part.YELLOW_GLASS || p == Part.FENCE ? 0 : 255;
    }

    @SuppressWarnings("deprecation")
    @Override
    public boolean shouldSideBeRendered(IBlockState state, IBlockAccess world, BlockPos pos, EnumFacing side) {
        IBlockState other = world.getBlockState(pos.offset(side));
        Part p = part(state);
        if ((p == Part.GLASS || p == Part.YELLOW_GLASS) && other.getBlock() == this && part(other) == p) {
            return false;
        }
        return super.shouldSideBeRendered(state, world, pos, side);
    }

    @SuppressWarnings("deprecation")
    @Override
    public AxisAlignedBB getBoundingBox(IBlockState state, IBlockAccess world, BlockPos pos) {
        return part(state) == Part.FENCE ? POST : FULL_BLOCK_AABB;
    }

    @SuppressWarnings("deprecation")
    @Override
    public BlockFaceShape getBlockFaceShape(IBlockAccess world, IBlockState state, BlockPos pos, EnumFacing face) {
        return part(state) == Part.FENCE ? BlockFaceShape.MIDDLE_POLE : BlockFaceShape.SOLID;
    }

    // ---- 红石口 ----

    @Override
    public boolean canConnectRedstone(IBlockState state, IBlockAccess world, BlockPos pos, @Nullable EnumFacing side) {
        return part(state) == Part.REDSTONE;
    }

    // ---- 交互 ----

    static boolean isHammer(ItemStack stack) {
        return !stack.isEmpty() && stack.getItem().getToolClasses(stack).contains("IE_HAMMER");
    }

    @Override
    public boolean onBlockActivated(World world, BlockPos pos, IBlockState state, EntityPlayer player, EnumHand hand, EnumFacing side,
                                    float hitX, float hitY, float hitZ) {
        TileEntity te = world.getTileEntity(pos);
        if (!(te instanceof TileLithoPart) || !((TileLithoPart) te).formed()) {
            return false;
        }
        TileLithoPart p = (TileLithoPart) te;
        ItemStack held = player.getHeldItem(hand);
        if (isHammer(held) && p.port() == Port.REDSTONE) {
            if (!world.isRemote) {
                TileLithoMaster m = p.master();
                if (m != null) {
                    m.toggleRedstone();
                    player.sendStatusMessage(new TextComponentTranslation(m.rsInverted
                            ? "youranmod.litho.redstone.inverted" : "youranmod.litho.redstone.normal"), true);
                }
            }
            return true;
        }
        if (!part(state).opensGui()) {
            return false;
        }
        if (!world.isRemote) {
            TileLithoMaster m = p.master();
            if (m != null) {
                LithoGuiHandler.openMain(player, world, m.getPos());
            }
        }
        return true;
    }

    // ---- 拆开、掉落 ----

    @Override
    public void breakBlock(World world, BlockPos pos, IBlockState state) {
        TileEntity te = world.getTileEntity(pos);
        if (!world.isRemote && te instanceof TileLithoPart && ((TileLithoPart) te).formed() && !TileLithoMaster.disassembling) {
            TileLithoMaster m = ((TileLithoPart) te).master();
            if (m != null) {
                m.disassemble(pos);
            }
        }
        super.breakBlock(world, pos, state);
    }

    @Override
    public void getDrops(NonNullList<ItemStack> drops, IBlockAccess world, BlockPos pos, IBlockState state, int fortune) {
        ItemStack s = LithoParts.item(part(state));
        if (!s.isEmpty()) {
            drops.add(s);
        }
    }

    @Override
    public ItemStack getPickBlock(IBlockState state, RayTraceResult target, World world, BlockPos pos, EntityPlayer player) {
        return LithoParts.item(part(state));
    }
}
