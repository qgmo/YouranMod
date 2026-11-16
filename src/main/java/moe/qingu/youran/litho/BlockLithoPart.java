package moe.qingu.youran.litho;

import moe.qingu.youran.logic.litho.LithoStructure.Part;
import moe.qingu.youran.logic.litho.LithoStructure.Port;
import net.minecraft.block.Block;
import net.minecraft.block.SoundType;
import net.minecraft.block.material.Material;
import net.minecraft.block.properties.IProperty;
import net.minecraft.block.properties.PropertyEnum;
import net.minecraft.block.state.BlockFaceShape;
import net.minecraft.block.state.BlockStateContainer;
import net.minecraft.block.state.IBlockState;
import net.minecraft.entity.Entity;
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
import net.minecraft.util.math.Vec3d;
import net.minecraft.util.text.TextComponentTranslation;
import net.minecraft.world.IBlockAccess;
import net.minecraft.world.World;
import net.minecraftforge.common.property.ExtendedBlockState;
import net.minecraftforge.common.property.IExtendedBlockState;
import net.minecraftforge.common.property.IUnlistedProperty;

import javax.annotation.Nullable;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Locale;

/**
 * 成形后的光刻机方块（设计稿四.3：不能继承沉浸工程的方块类，自己写）。元数据是这一格原来是什么（{@link Part}，16 种）。
 *
 * <p>外观照沉浸工程的多方块：成形后看不出格子，是一台整机（{@link moe.qingu.youran.logic.litho.LithoShape}）。每一格画整机模型落在
 * 自己格里的那部分（客户端的 {@code LithoBakedModel}，按扩展状态里的形状键取），碰撞箱、选取框也按格贴着外形（{@link LithoBounds}）。
 * 和沉浸工程的金属多方块一样不挡光（小间里不黑）。
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
    /** 形状键（{@link LithoBounds#key}），客户端模型按它取这一格的面；没成形时没有 */
    public static final IUnlistedProperty<Integer> SHAPE = new IUnlistedProperty<Integer>() {
        @Override
        public String getName() {
            return "shape";
        }

        @Override
        public boolean isValid(Integer value) {
            return value != null && value >= 0;
        }

        @Override
        public Class<Integer> getType() {
            return Integer.class;
        }

        @Override
        public String valueToString(Integer value) {
            return String.valueOf(value);
        }
    };

    public BlockLithoPart(String name) {
        super(Material.IRON);
        setRegistryName(name);
        setTranslationKey(name);
        setHardness(3.0F);
        setResistance(15.0F);
        setSoundType(SoundType.METAL);
        setLightOpacity(0);
        setDefaultState(blockState.getBaseState().withProperty(PART, PartProp.STEEL_SHEET));
    }

    @Override
    protected BlockStateContainer createBlockState() {
        return new ExtendedBlockState(this, new IProperty[]{PART}, new IUnlistedProperty[]{SHAPE});
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

    /** 这一格的形状键；没成形、还没收到数据时为 −1。渲染线程上（{@code ChunkCache}）不新建方块实体 */
    public static int shapeKey(IBlockAccess world, BlockPos pos) {
        TileEntity te = world instanceof net.minecraft.world.ChunkCache
                ? ((net.minecraft.world.ChunkCache) world).getTileEntity(pos, net.minecraft.world.chunk.Chunk.EnumCreateEntityType.CHECK)
                : world.getTileEntity(pos);
        return te instanceof TileLithoPart ? ((TileLithoPart) te).shapeKey() : -1;
    }

    @Override
    public IBlockState getExtendedState(IBlockState state, IBlockAccess world, BlockPos pos) {
        int key = shapeKey(world, pos);
        return key >= 0 && state instanceof IExtendedBlockState ? ((IExtendedBlockState) state).withProperty(SHAPE, key) : state;
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
        return layer == BlockRenderLayer.SOLID || layer == BlockRenderLayer.CUTOUT || layer == BlockRenderLayer.TRANSLUCENT;
    }

    @SuppressWarnings("deprecation")
    @Override
    public boolean isOpaqueCube(IBlockState state) {
        return false;
    }

    @SuppressWarnings("deprecation")
    @Override
    public boolean isFullCube(IBlockState state) {
        return false;
    }

    @Override
    public boolean isSideSolid(IBlockState state, IBlockAccess world, BlockPos pos, EnumFacing side) {
        return true;
    }

    @SuppressWarnings("deprecation")
    @Override
    public BlockFaceShape getBlockFaceShape(IBlockAccess world, IBlockState state, BlockPos pos, EnumFacing face) {
        return BlockFaceShape.SOLID;
    }

    @SuppressWarnings("deprecation")
    @Override
    public AxisAlignedBB getBoundingBox(IBlockState state, IBlockAccess world, BlockPos pos) {
        int key = shapeKey(world, pos);
        return key < 0 ? FULL_BLOCK_AABB : LithoBounds.outline(key);
    }

    /** 选取框（世界坐标）；没成形时为空，按整格 */
    public static List<AxisAlignedBB> selectionBoxes(IBlockAccess world, BlockPos pos) {
        int key = shapeKey(world, pos);
        if (key < 0) {
            return Collections.emptyList();
        }
        List<AxisAlignedBB> out = new ArrayList<>();
        for (AxisAlignedBB b : LithoBounds.selection(key)) {
            out.add(b.offset(pos));
        }
        return out;
    }

    @SuppressWarnings("deprecation")
    @Override
    public void addCollisionBoxToList(IBlockState state, World world, BlockPos pos, AxisAlignedBB mask, List<AxisAlignedBB> list,
                                      @Nullable Entity entity, boolean isActualState) {
        int key = shapeKey(world, pos);
        if (key < 0) {
            super.addCollisionBoxToList(state, world, pos, mask, list, entity, isActualState);
            return;
        }
        for (AxisAlignedBB b : LithoBounds.collision(key)) {
            AxisAlignedBB w = b.offset(pos);
            if (mask.intersects(w)) {
                list.add(w);
            }
        }
    }

    @SuppressWarnings("deprecation")
    @Nullable
    @Override
    public RayTraceResult collisionRayTrace(IBlockState state, World world, BlockPos pos, Vec3d start, Vec3d end) {
        int key = shapeKey(world, pos);
        if (key < 0) {
            return super.collisionRayTrace(state, world, pos, start, end);
        }
        RayTraceResult best = null;
        double bestDist = Double.POSITIVE_INFINITY;
        for (AxisAlignedBB b : LithoBounds.selection(key)) {
            RayTraceResult r = rayTrace(pos, start, end, b);
            if (r != null) {
                double d = r.hitVec.squareDistanceTo(start);
                if (d < bestDist) {
                    best = r;
                    bestDist = d;
                }
            }
        }
        return best;
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
