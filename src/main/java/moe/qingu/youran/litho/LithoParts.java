package moe.qingu.youran.litho;

import moe.qingu.youran.logic.litho.LithoStructure;
import moe.qingu.youran.logic.litho.LithoStructure.Part;
import moe.qingu.youran.registry.ModBlocks;
import net.minecraft.block.Block;
import net.minecraft.block.BlockStainedGlass;
import net.minecraft.block.state.IBlockState;
import net.minecraft.init.Blocks;
import net.minecraft.item.EnumDyeColor;
import net.minecraft.item.Item;
import net.minecraft.item.ItemStack;
import net.minecraft.util.ResourceLocation;
import net.minecraftforge.fml.common.registry.ForgeRegistries;
import net.minecraftforge.oredict.OreDictionary;

import javax.annotation.Nullable;

/**
 * 结构里每种字母对应的方块：结构检查怎么认、拆开时放回什么、手册里画什么（设计稿 2.3 图例、四.11）。
 * 沉浸工程的方块按注册名取，不引用它的类（没装沉浸工程时这些方块不存在，结构也就成不了形）。
 */
public final class LithoParts {
    static final String IE = "immersiveengineering";

    private LithoParts() {
    }

    @Nullable
    static Block ieBlock(String name) {
        Block b = ForgeRegistries.BLOCKS.getValue(new ResourceLocation(IE, name));
        return b == Blocks.AIR ? null : b;
    }

    /** 这种格子原来的方块（拆开时放回去的、手册里画的）；沉浸工程没装时为 null */
    @Nullable
    @SuppressWarnings("deprecation")
    public static IBlockState original(Part part) {
        switch (part) {
            case STEEL_SHEET:
                return ieState("sheetmetal", 8);
            case IRON_SHEET:
                return ieState("sheetmetal", 9);
            case SCAFFOLDING:
                return ieState("metal_decoration1", 1);
            case FENCE:
                return ieState("metal_decoration1", 0);
            case HEAVY:
                return ieState("metal_decoration0", 5);
            case LIGHT:
                return ieState("metal_decoration0", 4);
            case REDSTONE:
                return ieState("metal_decoration0", 3);
            case RADIATOR:
                return ieState("metal_decoration0", 7);
            case BLAST_BRICK:
                return ieState("stone_decoration", 2);
            case PIPE:
            case PIPE_OUT:
                return ieState("metal_device1", 6);
            case YELLOW_GLASS:
                return Blocks.STAINED_GLASS.getDefaultState().withProperty(BlockStainedGlass.COLOR, EnumDyeColor.YELLOW);
            case GLASS:
                return Blocks.GLASS.getDefaultState();
            case HOPPER:
                return Blocks.HOPPER.getDefaultState();
            case DROPPER:
                return Blocks.DROPPER.getDefaultState();
            case ALIGNER:
                return ModBlocks.alignmentTable.getDefaultState();
            default:
                return null;
        }
    }

    @Nullable
    @SuppressWarnings("deprecation")
    private static IBlockState ieState(String name, int meta) {
        Block b = ieBlock(name);
        return b == null ? null : b.getStateFromMeta(meta);
    }

    /** 这种格子的物品（手册、拆开后掉落用）；没有时为空物品 */
    public static ItemStack item(Part part) {
        IBlockState s = original(part);
        if (s == null) {
            return ItemStack.EMPTY;
        }
        Item item = Item.getItemFromBlock(s.getBlock());
        if (item == net.minecraft.init.Items.AIR) {
            return ItemStack.EMPTY;
        }
        int meta = s.getBlock().damageDropped(s);
        if (part == Part.PIPE || part == Part.PIPE_OUT) {
            meta = 6;
        }
        return new ItemStack(item, 1, meta);
    }

    /** 世界里这个方块认不认作这种格子 */
    public static boolean matches(Part part, IBlockState state) {
        Block b = state.getBlock();
        switch (part) {
            case STEEL_SHEET:
                return hasOre(state, "blockSheetmetalSteel");
            case IRON_SHEET:
                return hasOre(state, "blockSheetmetalIron");
            case SCAFFOLDING:
                return hasOre(state, "scaffoldingSteel");
            case FENCE:
                return hasOre(state, "fenceSteel");
            case YELLOW_GLASS:
                return b == Blocks.STAINED_GLASS && state.getValue(BlockStainedGlass.COLOR) == EnumDyeColor.YELLOW;
            case GLASS:
                return b == Blocks.GLASS;
            case HOPPER:
                return b == Blocks.HOPPER;
            case DROPPER:
                return b == Blocks.DROPPER;
            case ALIGNER:
                return b == ModBlocks.alignmentTable;
            default: {
                IBlockState want = original(part);
                return want != null && b == want.getBlock() && b.getMetaFromState(state) == want.getBlock().getMetaFromState(want);
            }
        }
    }

    /** 光源格认任何一种光源方块（以后的准分子激光光源也是 {@link BlockLightSource}） */
    public static boolean isLightSource(IBlockState state) {
        return state.getBlock() instanceof BlockLightSource;
    }

    private static boolean hasOre(IBlockState state, String ore) {
        Item item = Item.getItemFromBlock(state.getBlock());
        if (item == net.minecraft.init.Items.AIR || !OreDictionary.doesOreNameExist(ore)) {
            return false;
        }
        ItemStack stack = new ItemStack(item, 1, state.getBlock().damageDropped(state));
        int id = OreDictionary.getOreID(ore);
        for (int i : OreDictionary.getOreIDs(stack)) {
            if (i == id) {
                return true;
            }
        }
        return false;
    }

    /** 结构格子里的方块种类（输出口的输液管单独一种，好画橙点） */
    @Nullable
    public static Part partAt(int h, int l, int w) {
        Part p = LithoStructure.part(h, l, w);
        if (p == Part.PIPE && LithoStructure.Port.at(h, l, w) == LithoStructure.Port.FLUID_OUT) {
            return Part.PIPE_OUT;
        }
        return p;
    }
}
