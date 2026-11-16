package moe.qingu.youran.litho;

import moe.qingu.youran.logic.litho.LithoStructure;
import moe.qingu.youran.logic.litho.LithoStructure.Part;
import moe.qingu.youran.registry.ModBlocks;
import net.minecraft.block.state.IBlockState;
import net.minecraft.entity.player.EntityPlayer;
import net.minecraft.tileentity.TileEntity;
import net.minecraft.util.EnumFacing;
import net.minecraft.util.math.BlockPos;
import net.minecraft.world.World;

import java.util.function.BooleanSupplier;

/**
 * 光刻机的结构检查与成形（锤子敲对准台时由多方块接口调用）。照沉浸工程：朝向取被敲的那一面（敲顶面、底面时取玩家朝向），
 * 正常、镜像各查一次；通过后除光源外每一格换成 {@link BlockLithoPart}，光源原样保留、记下主方块。
 * 不引用沉浸工程的类（沉浸工程的方块按注册名认，见 {@link LithoParts}）。
 */
public final class LithoFormation {
    private LithoFormation() {
    }

    /** 敲在对准台 {@code clicked} 的 {@code side} 面上；{@code post} 在检查通过、替换之前调用（沉浸工程的 Post 事件，返回 false 表示被取消） */
    public static boolean tryForm(World world, BlockPos clicked, EnumFacing side, EntityPlayer player, BooleanSupplier post) {
        if (world.getBlockState(clicked).getBlock() != ModBlocks.alignmentTable) {
            return false;
        }
        // 正面朝着玩家：敲的是正面，往背面的方向就是被敲那一面的反方向
        EnumFacing facing = side.getAxis() == EnumFacing.Axis.Y ? player.getHorizontalFacing() : side.getOpposite();
        boolean mirrored = false;
        if (!check(world, clicked, facing, false)) {
            if (!check(world, clicked, facing, true)) {
                return false;
            }
            mirrored = true;
        }
        if (!post.getAsBoolean()) {
            return false;
        }
        form(world, clicked, facing, mirrored);
        return true;
    }

    static BlockPos posOf(BlockPos master, EnumFacing facing, boolean mirrored, int h, int l, int w) {
        return master.offset(facing, l - LithoStructure.MASTER_L).offset(TileLithoPart.wDir(facing, mirrored), w - LithoStructure.MASTER_W)
                .up(h - LithoStructure.MASTER_H);
    }

    static boolean check(World world, BlockPos master, EnumFacing facing, boolean mirrored) {
        for (int h = 0; h < LithoStructure.HEIGHT; h++) {
            for (int l = 0; l < LithoStructure.LENGTH; l++) {
                for (int w = 0; w < LithoStructure.WIDTH; w++) {
                    char c = LithoStructure.at(h, l, w);
                    if (c == LithoStructure.EMPTY) {
                        continue;
                    }
                    BlockPos p = posOf(master, facing, mirrored, h, l, w);
                    if (!world.isBlockLoaded(p)) {
                        return false;
                    }
                    IBlockState state = world.getBlockState(p);
                    if (c == LithoStructure.LIGHT_SOURCE) {
                        if (!LithoParts.isLightSource(state)) {
                            return false;
                        }
                        TileLightSource light = BlockLightSource.at(world, p);
                        if (light == null || (light.master() != null && !light.master().equals(master))) {
                            return false;
                        }
                        continue;
                    }
                    Part part = LithoStructure.Part.bySymbol(c);
                    if (part == null || !LithoParts.matches(part, state)) {
                        return false;
                    }
                }
            }
        }
        return true;
    }

    static void form(World world, BlockPos master, EnumFacing facing, boolean mirrored) {
        BlockLithoPart block = (BlockLithoPart) ModBlocks.lithoPart;
        // 先放主方块（其他格要找它）；漏斗、投掷器被替换时自己会把里面的东西掉出来（原版 breakBlock）
        for (int pass = 0; pass < 2; pass++) {
            for (int h = 0; h < LithoStructure.HEIGHT; h++) {
                for (int l = 0; l < LithoStructure.LENGTH; l++) {
                    for (int w = 0; w < LithoStructure.WIDTH; w++) {
                        Part part = LithoParts.partAt(h, l, w);
                        if (part == null || (part == Part.ALIGNER) != (pass == 0)) {
                            continue;
                        }
                        BlockPos p = posOf(master, facing, mirrored, h, l, w);
                        world.setBlockState(p, block.stateFor(part), 2);
                        TileEntity te = world.getTileEntity(p);
                        if (te instanceof TileLithoPart) {
                            ((TileLithoPart) te).setup(LithoStructure.index(h, l, w), master, facing, mirrored);
                        }
                    }
                }
            }
        }
        TileEntity m = world.getTileEntity(master);
        if (m instanceof TileLithoMaster) {
            ((TileLithoMaster) m).onFormed();
        }
        // 方块实体的数据（朝向、格子）设好后再通知客户端
        for (int h = 0; h < LithoStructure.HEIGHT; h++) {
            for (int l = 0; l < LithoStructure.LENGTH; l++) {
                for (int w = 0; w < LithoStructure.WIDTH; w++) {
                    if (LithoStructure.at(h, l, w) != LithoStructure.EMPTY) {
                        BlockPos p = posOf(master, facing, mirrored, h, l, w);
                        IBlockState s = world.getBlockState(p);
                        world.notifyBlockUpdate(p, s, s, 3);
                        world.notifyNeighborsOfStateChange(p, s.getBlock(), true);
                    }
                }
            }
        }
    }
}
