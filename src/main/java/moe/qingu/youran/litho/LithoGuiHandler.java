package moe.qingu.youran.litho;

import moe.qingu.youran.YouranMod;
import net.minecraft.entity.player.EntityPlayer;
import net.minecraft.tileentity.TileEntity;
import net.minecraft.util.math.BlockPos;
import net.minecraft.world.World;
import net.minecraftforge.fml.common.network.IGuiHandler;

import javax.annotation.Nullable;

/** 本模组的界面：光刻机的主界面、对准显微镜（1、2），紫外擦除器（3） */
public class LithoGuiHandler implements IGuiHandler {
    public static final int MAIN = 1;
    public static final int MICROSCOPE = 2;
    /** 紫外擦除器（服主 11-16） */
    public static final int UV_ERASER = 3;

    public static void openMain(EntityPlayer player, World world, BlockPos master) {
        player.openGui(YouranMod.instance, MAIN, world, master.getX(), master.getY(), master.getZ());
    }

    public static void openMicroscope(EntityPlayer player, World world, BlockPos master) {
        player.openGui(YouranMod.instance, MICROSCOPE, world, master.getX(), master.getY(), master.getZ());
    }

    @Nullable
    static moe.qingu.youran.pcb.TileUvEraser uvEraser(World world, int x, int y, int z) {
        BlockPos p = new BlockPos(x, y, z);
        if (!world.isBlockLoaded(p)) {
            return null;
        }
        TileEntity te = world.getTileEntity(p);
        return te instanceof moe.qingu.youran.pcb.TileUvEraser ? (moe.qingu.youran.pcb.TileUvEraser) te : null;
    }

    @Nullable
    static TileLithoMaster master(World world, int x, int y, int z) {
        BlockPos p = new BlockPos(x, y, z);
        if (!world.isBlockLoaded(p)) {
            return null;
        }
        TileEntity te = world.getTileEntity(p);
        return te instanceof TileLithoMaster ? (TileLithoMaster) te : null;
    }

    @Nullable
    @Override
    public Object getServerGuiElement(int id, EntityPlayer player, World world, int x, int y, int z) {
        if (id == UV_ERASER) {
            moe.qingu.youran.pcb.TileUvEraser t = uvEraser(world, x, y, z);
            return t == null || !t.usableBy(player) ? null : new moe.qingu.youran.pcb.ContainerUvEraser(player.inventory, t);
        }
        TileLithoMaster m = master(world, x, y, z);
        if (m == null || !m.usableBy(player)) {
            return null;
        }
        if (id == MAIN) {
            return new ContainerLitho(player.inventory, m);
        }
        if (id == MICROSCOPE) {
            return new ContainerMicroscope(player, m);
        }
        return null;
    }

    @Nullable
    @Override
    public Object getClientGuiElement(int id, EntityPlayer player, World world, int x, int y, int z) {
        if (id == UV_ERASER) {
            moe.qingu.youran.pcb.TileUvEraser t = uvEraser(world, x, y, z);
            return t == null ? null : new moe.qingu.youran.client.pcb.GuiUvEraser(new moe.qingu.youran.pcb.ContainerUvEraser(player.inventory, t));
        }
        TileLithoMaster m = master(world, x, y, z);
        if (m == null) {
            return null;
        }
        if (id == MAIN) {
            return new moe.qingu.youran.client.litho.GuiLitho(new ContainerLitho(player.inventory, m));
        }
        if (id == MICROSCOPE) {
            return new moe.qingu.youran.client.litho.GuiMicroscope(new ContainerMicroscope(player, m));
        }
        return null;
    }
}
