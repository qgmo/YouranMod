package moe.qingu.youran.recipe;

import moe.qingu.youran.YouranMod;
import moe.qingu.youran.registry.ModItems;
import net.minecraft.inventory.InventoryCrafting;
import net.minecraft.item.Item;
import net.minecraft.item.ItemStack;
import net.minecraft.item.crafting.IRecipe;
import net.minecraft.item.crafting.Ingredient;
import net.minecraft.nbt.NBTTagCompound;
import net.minecraft.util.ResourceLocation;
import net.minecraft.world.World;
import net.minecraftforge.common.crafting.CraftingHelper;
import net.minecraftforge.fml.common.registry.ForgeRegistries;
import net.minecraftforge.oredict.ShapedOreRecipe;
import net.minecraftforge.registries.IForgeRegistry;

import javax.annotation.Nullable;
import java.util.Arrays;
import java.util.List;

/**
 * 沉浸工程“光源”蓝图的合成（服主 11-16，取代 Claude 先定的“钨棒 / 玻璃管 / 钨棒、蓝色染料 ×3、纸 ×3”）。工作台有序（123 / 456 / 789，不镜像）：
 * <pre>
 * 钨阴极棒   任意玻璃板（paneGlass）   钨棒
 * 青金石粉   青金石粉                 青金石粉       （dustLapis）
 * 三本书随便摆：工程师手册、RF 工具指南、炼金化学的书（各一本，合成时消耗）
 * </pre>
 * 炼金化学的书是 Patchouli 的书 {@code patchouli:guide_book}，要按 NBT {@code {"patchouli:book":"alchemistry:alchemistry_book"}} 认（别的 Patchouli 书不行；
 * 服主 11-16 由“悠然服务器手册”改成炼金化学的书）。
 * 三本书里缺任何一种物品（没装 RFTools 或 Patchouli）就不注册这条配方（日志里记一行），蓝图也就做不出来——生产整合包都装着。
 */
public final class LightSourceBlueprintRecipe extends ShapedOreRecipe {
    static final ResourceLocation IE_TOOL = new ResourceLocation("immersiveengineering", "tool");
    /** 工程师手册：{@code immersiveengineering:tool} 元数据 3 */
    static final int IE_MANUAL_META = 3;
    static final ResourceLocation RFTOOLS_MANUAL = new ResourceLocation("rftools", "rftools_manual");
    static final ResourceLocation PATCHOULI_BOOK = new ResourceLocation("patchouli", "guide_book");
    static final String PATCHOULI_TAG = "patchouli:book";
    public static final String GUIDE_BOOK = "alchemistry:alchemistry_book";

    private final List<Ingredient> books;

    private LightSourceBlueprintRecipe(ResourceLocation id, ItemStack result, List<Ingredient> books, Object... recipe) {
        super(id, result, CraftingHelper.parseShaped(recipe));
        this.books = books;
        setMirrored(false);
    }

    public static void register(IForgeRegistry<IRecipe> registry) {
        Item blueprint = ForgeRegistries.ITEMS.getValue(new ResourceLocation("immersiveengineering", "blueprint"));
        Item ieTool = ForgeRegistries.ITEMS.getValue(IE_TOOL);
        Item rft = ForgeRegistries.ITEMS.getValue(RFTOOLS_MANUAL);
        Item patchouli = ForgeRegistries.ITEMS.getValue(PATCHOULI_BOOK);
        if (blueprint == null || ieTool == null) {
            return;
        }
        if (rft == null || patchouli == null) {
            YouranMod.LOG.warn("没有 {}，不加“光源”蓝图的合成（三本书缺一本做不了）", rft == null ? RFTOOLS_MANUAL : PATCHOULI_BOOK);
            return;
        }
        ItemStack bp = new ItemStack(blueprint);
        NBTTagCompound tag = new NBTTagCompound();
        tag.setString("blueprint", LithoRecipes.LAMP_BLUEPRINT);
        bp.setTagCompound(tag);
        ItemStack guide = new ItemStack(patchouli);
        NBTTagCompound g = new NBTTagCompound();
        g.setString(PATCHOULI_TAG, GUIDE_BOOK);
        guide.setTagCompound(g);
        List<Ingredient> books = Arrays.asList(Ingredient.fromStacks(new ItemStack(ieTool, 1, IE_MANUAL_META)), Ingredient.fromStacks(new ItemStack(rft)),
                new GuideBookIngredient(guide));
        Ingredient anyBook = new net.minecraftforge.common.crafting.CompoundIngredient(books) {
        };
        ResourceLocation id = new ResourceLocation(YouranMod.MODID, "blueprint_light_source");
        registry.register(new LightSourceBlueprintRecipe(id, bp, books, "CGR", "LLL", "BBB",
                'C', new ItemStack(ModItems.thoriatedCathodeRod), 'G', "paneGlass", 'R', new ItemStack(ModItems.tungstenRod),
                'L', "dustLapis", 'B', anyBook).setRegistryName(id));
    }

    /** 下排三格正好三本不同的书（各一本，顺序随便） */
    @Override
    public boolean matches(InventoryCrafting inv, World world) {
        if (!super.matches(inv, world) || inv.getWidth() != 3 || inv.getHeight() != 3) {
            return false;
        }
        return distinctBooks(inv.getStackInSlot(6), inv.getStackInSlot(7), inv.getStackInSlot(8));
    }

    boolean distinctBooks(ItemStack... bottom) {
        boolean[] used = new boolean[books.size()];
        for (ItemStack s : bottom) {
            int which = -1;
            for (int i = 0; i < books.size(); i++) {
                if (!used[i] && books.get(i).apply(s)) {
                    which = i;
                    break;
                }
            }
            if (which < 0) {
                return false;
            }
            used[which] = true;
        }
        return true;
    }

    /** Patchouli 书：物品对、NBT 里 {@code patchouli:book} 是炼金化学的书 */
    static final class GuideBookIngredient extends Ingredient {
        private final ItemStack book;

        GuideBookIngredient(ItemStack book) {
            super(book);
            this.book = book;
        }

        @Override
        public boolean apply(@Nullable ItemStack s) {
            return s != null && !s.isEmpty() && s.getItem() == book.getItem() && s.hasTagCompound()
                    && GUIDE_BOOK.equals(s.getTagCompound().getString(PATCHOULI_TAG));
        }

        @Override
        public boolean isSimple() {
            return false;
        }
    }
}
