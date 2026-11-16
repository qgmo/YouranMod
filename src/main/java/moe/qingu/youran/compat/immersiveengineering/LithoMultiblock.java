package moe.qingu.youran.compat.immersiveengineering;

import blusunrize.immersiveengineering.api.MultiblockHandler;
import blusunrize.immersiveengineering.api.crafting.IngredientStack;
import moe.qingu.youran.YouranMod;
import moe.qingu.youran.litho.LithoFormation;
import moe.qingu.youran.litho.LithoParts;
import moe.qingu.youran.logic.litho.LithoStructure;
import moe.qingu.youran.registry.ModBlocks;
import net.minecraft.block.state.IBlockState;
import net.minecraft.entity.player.EntityPlayer;
import net.minecraft.item.ItemStack;
import net.minecraft.util.EnumFacing;
import net.minecraft.util.math.BlockPos;
import net.minecraft.world.World;
import net.minecraftforge.fml.relauncher.Side;
import net.minecraftforge.fml.relauncher.SideOnly;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 光刻机在沉浸工程多方块系统里的登记（设计稿四.1）：工程师锤敲对准台成形；手册、沉浸原油的投影仪从这里取结构。
 * 结构检查与成形本身在 {@link LithoFormation}（不引用沉浸工程的类）。只有装了沉浸工程时才加载。
 */
public final class LithoMultiblock implements MultiblockHandler.IMultiblock {
    public static final LithoMultiblock INSTANCE = new LithoMultiblock();
    public static final String NAME = "youranmod:lithography";
    private ItemStack[][][] manual;
    private IngredientStack[] materials;

    private LithoMultiblock() {
    }

    public static void register() {
        MultiblockHandler.registerMultiblock(INSTANCE);
        YouranMod.LOG.info("已在沉浸工程里登记多方块：光刻机（{}，{} 个方块）", NAME, LithoStructure.blockCount());
    }

    @Override
    public String getUniqueName() {
        return NAME;
    }

    @Override
    public boolean isBlockTrigger(IBlockState state) {
        return state.getBlock() == ModBlocks.alignmentTable;
    }

    @Override
    public boolean createStructure(World world, BlockPos pos, EnumFacing side, EntityPlayer player) {
        return LithoFormation.tryForm(world, pos, side, player, () -> {
            ItemStack hammer = player.getHeldItemMainhand().getItem().getToolClasses(player.getHeldItemMainhand()).contains("IE_HAMMER")
                    ? player.getHeldItemMainhand() : player.getHeldItemOffhand();
            return !MultiblockHandler.fireMultiblockFormationEventPost(player, this, pos, hammer).isCanceled();
        });
    }

    @Override
    public ItemStack[][][] getStructureManual() {
        if (manual == null) {
            ItemStack[][][] s = new ItemStack[LithoStructure.HEIGHT][LithoStructure.LENGTH][LithoStructure.WIDTH];
            for (int h = 0; h < LithoStructure.HEIGHT; h++) {
                for (int l = 0; l < LithoStructure.LENGTH; l++) {
                    for (int w = 0; w < LithoStructure.WIDTH; w++) {
                        char c = LithoStructure.at(h, l, w);
                        if (c == LithoStructure.LIGHT_SOURCE) {
                            s[h][l][w] = new ItemStack(ModBlocks.mercuryLightSource);
                        } else if (c == LithoStructure.EMPTY) {
                            s[h][l][w] = ItemStack.EMPTY;
                        } else {
                            s[h][l][w] = LithoParts.item(LithoStructure.Part.bySymbol(c));
                        }
                    }
                }
            }
            manual = s;
        }
        return manual;
    }

    @Override
    public IngredientStack[] getTotalMaterials() {
        if (materials == null) {
            Map<String, ItemStack> counts = new LinkedHashMap<>();
            for (ItemStack[][] layer : getStructureManual()) {
                for (ItemStack[] row : layer) {
                    for (ItemStack s : row) {
                        if (s.isEmpty()) {
                            continue;
                        }
                        String key = s.getItem().getRegistryName() + ":" + s.getMetadata();
                        ItemStack have = counts.get(key);
                        if (have == null) {
                            counts.put(key, s.copy());
                        } else {
                            have.grow(1);
                        }
                    }
                }
            }
            materials = counts.values().stream().map(IngredientStack::new).toArray(IngredientStack[]::new);
        }
        return materials;
    }

    @Override
    @SideOnly(Side.CLIENT)
    public boolean overwriteBlockRender(ItemStack stack, int iterator) {
        return false;
    }

    @Override
    public float getManualScale() {
        return 11;
    }

    @Override
    @SideOnly(Side.CLIENT)
    public boolean canRenderFormedStructure() {
        return false;
    }

    @Override
    @SideOnly(Side.CLIENT)
    public void renderFormedStructure() {
    }
}
