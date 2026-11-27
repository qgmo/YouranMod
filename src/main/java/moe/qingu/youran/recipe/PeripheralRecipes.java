package moe.qingu.youran.recipe;

import moe.qingu.youran.YouranMod;
import moe.qingu.youran.item.ItemPeripheral;
import moe.qingu.youran.registry.ModItems;
import net.minecraft.init.Blocks;
import net.minecraft.init.Items;
import net.minecraft.item.Item;
import net.minecraft.item.ItemStack;
import net.minecraft.item.crafting.IRecipe;
import net.minecraft.item.crafting.Ingredient;
import net.minecraft.nbt.NBTTagCompound;
import net.minecraft.util.ResourceLocation;
import net.minecraftforge.fml.common.registry.ForgeRegistries;
import net.minecraftforge.oredict.ShapedOreRecipe;
import net.minecraftforge.registries.IForgeRegistry;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/**
 * 外设的工作台配方（服主 11-16 定），都在这一个类里。四角（C / P）钢板或聚氯乙烯片，四个角要一样：钢板做出金属壳（元数据 0），
 * 聚氯乙烯片做出塑料壳（元数据 1），只是样子不同。电路板两种都认（IC2 电路板或沉浸工程电路板）。
 * <pre>
 * 软驱        C 钴改性磁粉 C / IC2 电动机 铁活板门 IC2 电动机 / C 电路板 C
 * EPROM 烧录器 C 拉杆或沉浸工程断路刀闸 C / 电路板 IC2 低压变压器或沉浸工程变压器 电路板 / C IC2 绝缘铜质导线 C
 * 定位模块    P 指南针 P / 电路板 EPROM（空白的就行） 电路板 / P IC2 绝缘铜质导线 P
 * 身份模块    P 命名牌 P / 电路板 EPROM（空白的就行） 电路板 / P IC2 绝缘铜质导线 P（服主 11-27 临时配方：同定位模块，只把指南针换成命名牌，免得在工作台上撞）
 * </pre>
 * 没装 IC2 或沉浸工程时，缺了必需材料的配方不加。
 */
public final class PeripheralRecipes {
    /** IC2 的合成材料（{@code ic2:crafting}）：电路板 1、电动机 6 */
    static final int IC2_ELECTRIC_MOTOR = 6;
    /** IC2 的方块实体物品（{@code ic2:te}）：低压变压器 77 */
    static final int IC2_LV_TRANSFORMER = 77;
    /** 沉浸工程的接线器（{@code immersiveengineering:connector}）：变压器 7、断路刀闸 9 */
    static final int IE_TRANSFORMER = 7;
    static final int IE_BREAKER_SWITCH = 9;

    private PeripheralRecipes() {
    }

    public static void register(IForgeRegistry<IRecipe> registry) {
        Ingredient circuit = PcbRecipes.circuitBoards();
        Ingredient copperCable = ic2InsulatedCopperCable();
        Item ic2Crafting = item("ic2", "crafting");
        Item ic2Te = item("ic2", "te");
        Item ieConnector = item("immersiveengineering", "connector");
        // 软驱
        if (ic2Crafting != null) {
            cased(registry, "floppy_drive", ModItems.floppyDrive, new String[]{"CMC", "ETE", "CBC"},
                    'M', new ItemStack(ModItems.cobaltMagneticDust), 'E', new ItemStack(ic2Crafting, 1, IC2_ELECTRIC_MOTOR),
                    'T', new ItemStack(Blocks.IRON_TRAPDOOR), 'B', circuit);
        }
        // EPROM 烧录器
        List<ItemStack> levers = new ArrayList<>(Arrays.asList(new ItemStack(Blocks.LEVER)));
        List<ItemStack> transformers = new ArrayList<>();
        if (ieConnector != null) {
            levers.add(new ItemStack(ieConnector, 1, IE_BREAKER_SWITCH));
            transformers.add(new ItemStack(ieConnector, 1, IE_TRANSFORMER));
        }
        if (ic2Te != null) {
            transformers.add(new ItemStack(ic2Te, 1, IC2_LV_TRANSFORMER));
        }
        if (!transformers.isEmpty() && copperCable != null) {
            cased(registry, "eprom_programmer", ModItems.epromProgrammer, new String[]{"CLC", "BXB", "CWC"},
                    'L', Ingredient.fromStacks(levers.toArray(new ItemStack[0])), 'B', circuit,
                    'X', Ingredient.fromStacks(transformers.toArray(new ItemStack[0])), 'W', copperCable);
        }
        // 定位模块（取代原来临时的配方）
        if (copperCable != null) {
            cased(registry, "locator_module", ModItems.locatorModule, new String[]{"CKC", "BEB", "CWC"},
                    'K', new ItemStack(Items.COMPASS), 'B', circuit, 'E', new ItemStack(ModItems.eprom), 'W', copperCable);
            // 身份模块：同定位模块，指南针换成命名牌（以后进电脑制造流水线）
            cased(registry, "identity_module", ModItems.identityModule, new String[]{"CKC", "BEB", "CWC"},
                    'K', new ItemStack(Items.NAME_TAG), 'B', circuit, 'E', new ItemStack(ModItems.eprom), 'W', copperCable);
        }
    }

    /** 同一个形状登记两条：四角 C = 钢板 → 金属壳，C = 聚氯乙烯片 → 塑料壳 */
    static void cased(IForgeRegistry<IRecipe> registry, String name, Item out, String[] shape, Object... keys) {
        for (int casing : new int[]{ItemPeripheral.CASING_STEEL, ItemPeripheral.CASING_PVC}) {
            List<Object> args = new ArrayList<>(Arrays.asList((Object[]) shape));
            args.add('C');
            args.add(casing == ItemPeripheral.CASING_STEEL ? "plateSteel" : new ItemStack(ModItems.pvcSheet));
            args.addAll(Arrays.asList(keys));
            ResourceLocation id = new ResourceLocation(YouranMod.MODID, name + (casing == ItemPeripheral.CASING_PVC ? "_pvc" : ""));
            registry.register(new ShapedOreRecipe(id, new ItemStack(out, 1, casing), args.toArray()).setRegistryName(id));
        }
    }

    static Item item(String mod, String name) {
        return ForgeRegistries.ITEMS.getValue(new ResourceLocation(mod, name));
    }

    /**
     * IC2 一层绝缘铜质导线：IC2 exp 2.8 的导线都是 {@code ic2:cable} 元数据 0，种类与绝缘层数在 NBT：{@code {type:0b, insulation:1b}}
     * （{@code CableType.copper} 的序号是 0）。按 NBT 比；没装 IC2 时为 null
     */
    static Ingredient ic2InsulatedCopperCable() {
        Item cable = item("ic2", "cable");
        if (cable == null) {
            return null;
        }
        ItemStack s = new ItemStack(cable, 1, 0);
        NBTTagCompound tag = new NBTTagCompound();
        tag.setByte("type", (byte) 0);
        tag.setByte("insulation", (byte) 1);
        s.setTagCompound(tag);
        return new net.minecraftforge.common.crafting.IngredientNBT(s) {
        };
    }
}
