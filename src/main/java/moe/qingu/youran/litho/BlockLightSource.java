package moe.qingu.youran.litho;

import moe.qingu.youran.registry.YouranTab;
import net.minecraft.block.Block;
import net.minecraft.block.SoundType;
import net.minecraft.block.material.Material;
import net.minecraft.block.state.IBlockState;
import net.minecraft.entity.EntityLivingBase;
import net.minecraft.entity.player.EntityPlayer;
import net.minecraft.item.ItemStack;
import net.minecraft.tileentity.TileEntity;
import net.minecraft.util.EnumFacing;
import net.minecraft.util.EnumHand;
import net.minecraft.util.NonNullList;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.RayTraceResult;
import net.minecraft.world.IBlockAccess;
import net.minecraft.world.World;

import javax.annotation.Nullable;

/**
 * 光源方块（服主 11-15）：第一档汞灯光源。光刻机结构里的一格，成形时**不被替换**、原样留在结构里（设计稿 3.3），
 * 数据在自己的方块实体（{@link TileLightSource}）里。
 *
 * <p>掉落只走一条路：方块被移除时（{@link #breakBlock}，方块实体还在）生成带数据的物品，{@link #getDrops} 不给东西；
 * 创造模式不掉。爆炸、玩家挖都走这里，不会掉两个，也不会按爆炸概率丢灯。被敲掉时如果接在一台成形的光刻机上，先让整台拆开。
 */
public class BlockLightSource extends Block {
    public BlockLightSource(String name) {
        super(Material.IRON);
        setRegistryName(name);
        setTranslationKey(name);
        setHardness(3.0F);
        setResistance(10.0F);
        setSoundType(SoundType.METAL);
        setCreativeTab(YouranTab.tab);
    }

    @Override
    public boolean hasTileEntity(IBlockState state) {
        return true;
    }

    @Override
    public TileEntity createTileEntity(World world, IBlockState state) {
        return new TileLightSource();
    }

    @Override
    public void onBlockPlacedBy(World world, BlockPos pos, IBlockState state, EntityLivingBase placer, ItemStack stack) {
        TileEntity te = world.getTileEntity(pos);
        if (te instanceof TileLightSource) {
            ((TileLightSource) te).setLampData(stack.getTagCompound());
        }
    }

    @Override
    public boolean removedByPlayer(IBlockState state, World world, BlockPos pos, EntityPlayer player, boolean willHarvest) {
        TileEntity te = world.getTileEntity(pos);
        if (te instanceof TileLightSource && player.capabilities.isCreativeMode) {
            ((TileLightSource) te).noDrop = true;
        }
        return super.removedByPlayer(state, world, pos, player, willHarvest);
    }

    @Override
    public void breakBlock(World world, BlockPos pos, IBlockState state) {
        TileEntity te = world.getTileEntity(pos);
        if (te instanceof TileLightSource && !world.isRemote) {
            TileLightSource light = (TileLightSource) te;
            BlockPos masterPos = light.master();
            if (masterPos != null && world.isBlockLoaded(masterPos)) {
                TileEntity m = world.getTileEntity(masterPos);
                if (m instanceof TileLithoMaster) {
                    ((TileLithoMaster) m).disassemble(pos);
                }
            }
            if (!light.noDrop && world.getGameRules().getBoolean("doTileDrops")) {
                spawnAsEntity(world, pos, itemFor(light));
            }
        }
        super.breakBlock(world, pos, state);
    }

    /** 带数据的光源物品 */
    public ItemStack itemFor(TileLightSource light) {
        ItemStack stack = new ItemStack(this);
        stack.setTagCompound(light.lampData());
        return stack;
    }

    @Override
    public void getDrops(NonNullList<ItemStack> drops, IBlockAccess world, BlockPos pos, IBlockState state, int fortune) {
        // 掉落只在 breakBlock 里生成（带数据）
    }

    @Override
    public ItemStack getPickBlock(IBlockState state, RayTraceResult target, World world, BlockPos pos, EntityPlayer player) {
        TileEntity te = world.getTileEntity(pos);
        return te instanceof TileLightSource ? itemFor((TileLightSource) te) : new ItemStack(this);
    }

    @Override
    public boolean onBlockActivated(World world, BlockPos pos, IBlockState state, EntityPlayer player, EnumHand hand, EnumFacing side,
                                    float hitX, float hitY, float hitZ) {
        TileEntity te = world.getTileEntity(pos);
        if (!(te instanceof TileLightSource) || ((TileLightSource) te).master() == null) {
            return false;
        }
        if (!world.isRemote) {
            BlockPos m = ((TileLightSource) te).master();
            if (world.isBlockLoaded(m) && world.getTileEntity(m) instanceof TileLithoMaster) {
                LithoGuiHandler.openMain(player, world, m);
            }
        }
        return true;
    }

    @Override
    public int getLightValue(IBlockState state, IBlockAccess world, BlockPos pos) {
        TileEntity te = world.getTileEntity(pos);
        return te instanceof TileLightSource && ((TileLightSource) te).lit() ? 12 : 0;
    }

    @Nullable
    public static TileLightSource at(World world, BlockPos pos) {
        if (!world.isBlockLoaded(pos)) {
            return null;
        }
        TileEntity te = world.getTileEntity(pos);
        return te instanceof TileLightSource ? (TileLightSource) te : null;
    }
}
