package moe.qingu.youran.recipe;

import moe.qingu.youran.YouranMod;
import net.minecraft.block.BlockButton;
import net.minecraft.init.Blocks;
import net.minecraft.item.Item;
import net.minecraft.item.ItemBlock;
import net.minecraft.item.ItemStack;
import net.minecraft.item.crafting.IRecipe;
import net.minecraft.item.crafting.Ingredient;
import net.minecraft.util.ResourceLocation;
import net.minecraftforge.fml.common.registry.ForgeRegistries;
import net.minecraftforge.oredict.ShapedOreRecipe;
import net.minecraftforge.registries.IForgeRegistry;

import javax.annotation.Nullable;
import java.util.ArrayList;
import java.util.List;

/**
 * 家具模组电视遥控器的配方（服主 11-17：原来整合包 CraftTweaker 脚本里三条，删掉改成模组加，统一成一条）：
 * <pre>
 * C D C      C IC2 铁质外壳（ic2:casing 元数据 3）
 * C B C      D 阳光传感器
 * C W C      B 任意按钮（原版石质、木质与别的模组的按钮方块都算）
 *            W 沉浸工程低压接线器，或 IC2 一层绝缘铜质导线
 * </pre>
 * 家具模组自己的遥控器配方（tv_remote.json）在家具 fork 里删掉了。没装家具或 IC2 时不加。
 */
public final class TvRemoteRecipes {
    /** IC2 外壳（{@code ic2:casing}）：铁 3 */
    static final int IC2_IRON_CASING = 3;
    /** 沉浸工程接线器（{@code immersiveengineering:connector}）：低压接线器 0 */
    static final int IE_LV_CONNECTOR = 0;

    private TvRemoteRecipes() {
    }

    public static void register(IForgeRegistry<IRecipe> registry) {
        Item remote = PeripheralRecipes.item("cfm", "tv_remote");
        Item casing = PeripheralRecipes.item("ic2", "casing");
        if (remote == null || casing == null) {
            return;
        }
        Ingredient wire = lvWire();
        if (wire == null) {
            return;
        }
        ResourceLocation id = new ResourceLocation(YouranMod.MODID, "tv_remote");
        registry.register(new ShapedOreRecipe(id, new ItemStack(remote), "CDC", "CBC", "CWC",
                'C', new ItemStack(casing, 1, IC2_IRON_CASING),
                'D', new ItemStack(Blocks.DAYLIGHT_DETECTOR),
                'B', anyButton(),
                'W', wire).setRegistryName(id));
    }

    /** 任意按钮：方块是按钮（{@link BlockButton}）的物品，含别的模组的 */
    static Ingredient anyButton() {
        List<ItemStack> buttons = new ArrayList<>();
        for (Item item : ForgeRegistries.ITEMS) {
            if (item instanceof ItemBlock && ((ItemBlock) item).getBlock() instanceof BlockButton) {
                buttons.add(new ItemStack(item));
            }
        }
        if (buttons.isEmpty()) {
            buttons.add(new ItemStack(Blocks.STONE_BUTTON));
        }
        return Ingredient.fromStacks(buttons.toArray(new ItemStack[0]));
    }

    /** 沉浸工程低压接线器或 IC2 一层绝缘铜质导线（导线按 NBT 比）；两样都没有时为 null */
    @Nullable
    static Ingredient lvWire() {
        Item connector = PeripheralRecipes.item("immersiveengineering", "connector");
        Ingredient cable = PeripheralRecipes.ic2InsulatedCopperCable();
        Ingredient lv = connector == null ? null : Ingredient.fromStacks(new ItemStack(connector, 1, IE_LV_CONNECTOR));
        if (lv == null || cable == null) {
            return lv != null ? lv : cable;
        }
        List<ItemStack> shown = new ArrayList<>();
        for (ItemStack s : lv.getMatchingStacks()) {
            shown.add(s);
        }
        for (ItemStack s : cable.getMatchingStacks()) {
            shown.add(s);
        }
        final Ingredient a = lv;
        final Ingredient b = cable;
        return new Ingredient(shown.toArray(new ItemStack[0])) {
            @Override
            public boolean apply(@Nullable ItemStack stack) {
                return a.apply(stack) || b.apply(stack);
            }

            @Override
            public boolean isSimple() {
                return false;
            }
        };
    }
}
