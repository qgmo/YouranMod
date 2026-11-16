package moe.qingu.youran.pcb;

import moe.qingu.youran.YouranMod;
import moe.qingu.youran.registry.YouranTab;
import net.minecraft.block.Block;
import net.minecraft.block.BlockHorizontal;
import net.minecraft.block.ITileEntityProvider;
import net.minecraft.block.SoundType;
import net.minecraft.block.material.Material;
import net.minecraft.block.state.BlockStateContainer;
import net.minecraft.block.state.IBlockState;
import net.minecraft.client.resources.I18n;
import net.minecraft.client.util.ITooltipFlag;
import net.minecraft.entity.EntityLivingBase;
import net.minecraft.entity.player.EntityPlayer;
import net.minecraft.inventory.InventoryHelper;
import net.minecraft.item.ItemStack;
import net.minecraft.tileentity.TileEntity;
import net.minecraft.util.EnumFacing;
import net.minecraft.util.EnumHand;
import net.minecraft.util.NonNullList;
import net.minecraft.util.text.TextFormatting;
import net.minecraft.util.math.BlockPos;
import net.minecraft.world.IBlockAccess;
import net.minecraft.world.World;
import net.minecraftforge.fml.relauncher.Side;
import net.minecraftforge.fml.relauncher.SideOnly;

import javax.annotation.Nullable;
import java.util.List;

/**
 * 紫外擦除器的方块（服主 11-16）：一格，正面朝放的人，右键开界面。逻辑见 {@link TileUvEraser}。
 * <p>
 * 拆不了（服主 11-16）：不管怎么破坏（挖、炸、别的模组拆），机器本身什么都不掉（精准采集也不掉），里面的东西原样掉出来
 * （汞灯带着剩下的汞与寿命、工作格、底片格）。要挪地方用 Carry On 整个搬：它先取走方块实体再拆方块，所以搬的时候不会掉东西，
 * 放下时方块实体的数据（灯、内容、电、进度）原样读回去，接着做。不进 Carry On 的黑名单。
 */
public class BlockUvEraser extends Block implements ITileEntityProvider {
    public BlockUvEraser(String name) {
        super(Material.IRON);
        setRegistryName(name);
        setTranslationKey(name);
        setHardness(3.0F);
        setResistance(10.0F);
        setSoundType(SoundType.METAL);
        setCreativeTab(YouranTab.tab);
        setDefaultState(blockState.getBaseState().withProperty(BlockHorizontal.FACING, EnumFacing.NORTH));
    }

    @Override
    protected BlockStateContainer createBlockState() {
        return new BlockStateContainer(this, BlockHorizontal.FACING);
    }

    @SuppressWarnings("deprecation")
    @Override
    public IBlockState getStateFromMeta(int meta) {
        return getDefaultState().withProperty(BlockHorizontal.FACING, EnumFacing.byHorizontalIndex(meta & 3));
    }

    @Override
    public int getMetaFromState(IBlockState state) {
        return state.getValue(BlockHorizontal.FACING).getHorizontalIndex();
    }

    @Override
    public void onBlockPlacedBy(World world, BlockPos pos, IBlockState state, EntityLivingBase placer, ItemStack stack) {
        world.setBlockState(pos, state.withProperty(BlockHorizontal.FACING, placer.getHorizontalFacing().getOpposite()), 2);
    }

    @Nullable
    @Override
    public TileEntity createNewTileEntity(World world, int meta) {
        return new TileUvEraser();
    }

    @Override
    public boolean onBlockActivated(World world, BlockPos pos, IBlockState state, EntityPlayer player, EnumHand hand, EnumFacing facing,
                                    float hitX, float hitY, float hitZ) {
        if (!world.isRemote) {
            player.openGui(YouranMod.instance, moe.qingu.youran.litho.LithoGuiHandler.UV_ERASER, world, pos.getX(), pos.getY(), pos.getZ());
        }
        return true;
    }

    /** 机器本身不掉落（服主 11-16：挖掉就坏）；里面的东西在 {@link #breakBlock} 里掉 */
    @Override
    public void getDrops(NonNullList<ItemStack> drops, IBlockAccess world, BlockPos pos, IBlockState state, int fortune) {
    }

    @Override
    public boolean canSilkHarvest(World world, BlockPos pos, IBlockState state, EntityPlayer player) {
        return false;
    }

    @SideOnly(Side.CLIENT)
    @Override
    public void addInformation(ItemStack stack, @Nullable World world, List<String> lines, ITooltipFlag flag) {
        lines.add(TextFormatting.GRAY + I18n.format("youranmod.uv_eraser.tooltip.carry"));
    }

    @Override
    public void breakBlock(World world, BlockPos pos, IBlockState state) {
        TileEntity te = world.getTileEntity(pos);
        if (te instanceof TileUvEraser) {
            for (int i = 0; i < ((TileUvEraser) te).inv.getSlots(); i++) {
                ItemStack s = ((TileUvEraser) te).inv.getStackInSlot(i);
                if (!s.isEmpty()) {
                    InventoryHelper.spawnItemStack(world, pos.getX(), pos.getY(), pos.getZ(), s);
                }
            }
        }
        super.breakBlock(world, pos, state);
    }
}
