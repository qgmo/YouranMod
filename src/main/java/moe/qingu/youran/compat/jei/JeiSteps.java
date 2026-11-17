package moe.qingu.youran.compat.jei;

import moe.qingu.youran.fluid.FluidVariants;
import moe.qingu.youran.item.ItemEprom;
import moe.qingu.youran.item.LampNbt;
import moe.qingu.youran.logic.litho.DuvNumbers;
import moe.qingu.youran.logic.litho.ExcimerSpec;
import moe.qingu.youran.logic.litho.LampGas;
import moe.qingu.youran.logic.litho.LampGlass;
import moe.qingu.youran.logic.litho.LampSpec;
import moe.qingu.youran.logic.litho.LithoNumbers;
import moe.qingu.youran.logic.litho.ResistType;
import moe.qingu.youran.logic.pcb.BoardType;
import moe.qingu.youran.logic.pcb.PcbStage;
import moe.qingu.youran.logic.purity.Purity;
import moe.qingu.youran.logic.purity.WaferMold;
import moe.qingu.youran.registry.ModFluids;
import moe.qingu.youran.registry.ModItems;
import net.minecraft.client.resources.I18n;
import net.minecraft.item.Item;
import net.minecraft.item.ItemStack;
import net.minecraft.util.ResourceLocation;
import net.minecraftforge.fluids.FluidStack;
import net.minecraftforge.fluids.FluidUtil;
import net.minecraftforge.fluids.capability.IFluidHandlerItem;
import net.minecraftforge.fml.common.registry.ForgeRegistries;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;

/**
 * 只展示用的加工步骤（{@link StepRecipe}、{@link FluidItemRecipe}）：能做、但靠物品的流体能力或 Mixin 实现、没有登记成配方，
 * JEI 里原来查不到的步骤。数量只写规则（每次灌多少），不写配比、概率这类要自己摸索的东西。
 */
final class JeiSteps {
    private JeiSteps() {
    }

    /**
     * 灌入容器：汞灯补汞、激光腔灌氩。IC2 装罐机的“装罐”模式、沉浸工程灌装机都按物品的流体能力灌，没有登记成配方；
     * 列在装罐机“装罐”模式的页里（{@code FluidItemRecipe}，罐子格放容器、输入罐放流体）。匠魂浇铸台补汞另见匠魂的页。
     */
    static List<FluidItemRecipe> containerFills() {
        List<FluidItemRecipe> list = new ArrayList<>();
        if (ModFluids.hydrargyrum != null) {
            LampSpec tube = LampSpec.tube(LampGlass.GLASS, WaferMold.GOLD, Purity.DEFAULT_NINES).assembled(LampGas.ARGON);
            ItemStack empty = new ItemStack(ModItems.mercuryLamp);
            empty.setTagCompound(LampNbt.tag(tube));
            ItemStack full = new ItemStack(ModItems.mercuryLamp);
            full.setTagCompound(LampNbt.tag(tube.refilled()));
            list.add(new FluidItemRecipe(new FluidStack(ModFluids.hydrargyrum, LithoNumbers.LAMP_MERCURY), Collections.singletonList(empty), null,
                    Collections.singletonList(full), Arrays.asList(I18n.format("youranmod.jei.container_fill.lamp"))));
        }
        if (ModFluids.argon != null) {
            ItemStack empty = ModItems.arfLaserCavity.make(ExcimerSpec.empty(Purity.DEFAULT_NINES));
            ItemStack filled = ModItems.arfLaserCavity.make(ExcimerSpec.empty(Purity.DEFAULT_NINES).withArgon(DuvNumbers.ARGON_PER_FILL));
            list.add(new FluidItemRecipe(new FluidStack(ModFluids.argon, DuvNumbers.ARGON_PER_FILL), Collections.singletonList(empty), null,
                    Collections.singletonList(filled), Arrays.asList(I18n.format("youranmod.jei.container_fill.cavity"))));
        }
        return list;
    }

    /** 紫外擦除器：涂胶主板 + 线路底片 → 曝光后的主板；烧过的 EPROM → 擦干净；都要放一盏汞灯 */
    static List<StepRecipe> uvEraser() {
        List<StepRecipe> list = new ArrayList<>();
        ItemStack lamp = new ItemStack(ModItems.mercuryLamp);
        lamp.setTagCompound(moe.qingu.youran.item.CreativeStandards.lamp());
        String note = I18n.format("youranmod.jei.uv_eraser.note");
        for (BoardType t : BoardType.circuits()) {  // 有自己线路的几种；家具电脑主板没有底片、光板
            List<ItemStack> films = Collections.singletonList(new ItemStack(ModItems.pcbFilm, 1, t.id));
            List<ItemStack> coated = Arrays.asList(new ItemStack(ModItems.pcb, 1, PcbStage.COATED), new ItemStack(ModItems.pcb, 1, PcbStage.COATED_SPOILED));
            List<ItemStack> exposed = Arrays.asList(new ItemStack(ModItems.pcb, 1, PcbStage.afterExposure(PcbStage.COATED, t)),
                    new ItemStack(ModItems.pcb, 1, PcbStage.afterExposure(PcbStage.COATED_SPOILED, t)));
            list.add(new StepRecipe(StepRecipe.list(StepRecipe.one(lamp), coated, films), null, StepRecipe.list(exposed),
                    Arrays.asList(note, I18n.format("youranmod.jei.uv_eraser.film"))));
        }
        ItemStack burnt = new ItemStack(ModItems.eprom);
        burnt.setTagCompound(moe.qingu.youran.item.CreativeStandards.eprom());
        ItemEprom.burn(burnt, new byte[]{1, 2, 3, 4});
        list.add(new StepRecipe(StepRecipe.list(StepRecipe.one(lamp), StepRecipe.one(burnt)), null,
                StepRecipe.list(StepRecipe.one(ItemEprom.erased(burnt))), Arrays.asList(note, I18n.format("youranmod.jei.uv_eraser.eprom"))));
        return list;
    }

    /**
     * 改良正胶（IC2 装罐机富集模式，加进“装罐机反应”页）：输入罐正胶，输入格放一个装着 UU 物质的容器；容器留在格子里，
     * 里面的 UU 物质少一点。没有 IC2 的流体单元（或灌不进 UU）时不列。
     */
    static FluidItemRecipe improvedResist() {
        FluidStack uu = net.minecraftforge.fluids.FluidRegistry.getFluidStack("ic2uu_matter", 1000);
        Item cell = ForgeRegistries.ITEMS.getValue(new ResourceLocation("ic2", "fluid_cell"));
        if (uu == null || cell == null || ModFluids.photoresist == null) {
            return null;
        }
        ItemStack filled = new ItemStack(cell);
        IFluidHandlerItem handler = FluidUtil.getFluidHandler(filled);
        if (handler == null || handler.fill(uu, true) <= 0) {
            return null;
        }
        return new FluidItemRecipe(FluidVariants.photoresist(ResistType.POSITIVE, DuvNumbers.IMPROVED_RESIST_MB),
                Collections.singletonList(handler.getContainer()),
                FluidVariants.photoresist(ResistType.IMPROVED_POSITIVE, DuvNumbers.IMPROVED_RESIST_MB), Collections.<ItemStack>emptyList(),
                Arrays.asList(I18n.format("youranmod.jei.improved_resist.note")));
    }

    /** 说明页（ingredient info）：没有地方写配方的几样 */
    static List<ItemStack> etchedBoards() {
        List<ItemStack> l = new ArrayList<>();
        for (BoardType t : BoardType.circuits()) {  // 有自己线路的几种；家具电脑主板没有底片、光板
            l.add(new ItemStack(ModItems.pcb, 1, PcbStage.etched(t)));
        }
        return l;
    }

    static ItemStack lampExample() {
        ItemStack lamp = new ItemStack(ModItems.mercuryLamp);
        lamp.setTagCompound(moe.qingu.youran.item.CreativeStandards.lamp());
        return lamp;
    }
}
