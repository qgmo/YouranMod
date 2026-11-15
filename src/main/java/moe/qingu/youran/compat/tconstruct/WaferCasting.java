package moe.qingu.youran.compat.tconstruct;

import moe.qingu.youran.YouranMod;
import moe.qingu.youran.fluid.FluidPurity;
import moe.qingu.youran.item.ItemDopedWaferMold;
import moe.qingu.youran.item.ItemSiliconWafer;
import moe.qingu.youran.logic.purity.Purity;
import moe.qingu.youran.logic.purity.WaferMold;
import moe.qingu.youran.logic.purity.WaferRules;
import moe.qingu.youran.registry.ModItems;
import net.minecraft.item.ItemStack;
import net.minecraftforge.common.MinecraftForge;
import net.minecraftforge.fluids.FluidStack;
import net.minecraftforge.fml.common.eventhandler.SubscribeEvent;
import slimeknights.mantle.util.RecipeMatch;
import slimeknights.tconstruct.library.TinkerRegistry;
import slimeknights.tconstruct.library.smeltery.CastingRecipe;
import slimeknights.tconstruct.smeltery.events.TinkerCastingEvent;

/**
 * 匠魂浇铸台浇注硅晶圆（服主 11-15，芯片线第 8 步）：{@value WaferRules#MIXTURE_PER_WAFER} mB 混合气 + 掺杂硅晶圆铸模（消耗）→ 1 硅晶圆。
 *
 * <p>浇铸台按流体种类（不看 NBT）找配方，配方的产物也是固定的，所以纯度在浇好的那一刻改：匠魂浇好时发
 * {@code TinkerCastingEvent.OnCasted}（这时浇铸台罐里的混合气、铸模格里的铸模都还在），产物换成带上罐里混合气纯度、
 * 铸模材质的硅晶圆。不用 Mixin。没装匠魂时不加载这个类。
 */
public final class WaferCasting {
    private WaferCasting() {
    }

    /** 加浇铸台配方（两种铸模各一条）并登记事件。后初始化时调用 */
    public static void register() {
        if (mixtureFluid() == null) {
            return;
        }
        for (WaferMold mold : WaferMold.values()) {
            ItemStack shown = ItemSiliconWafer.make(ModItems.siliconWafer, Purity.WAFER_GRADE_NINES, mold);
            // 配方里的混合气带 9 个 9（只给 JEI 看；浇铸台找配方只看流体种类）
            FluidStack fluid = FluidPurity.mixture(WaferRules.MIXTURE_PER_WAFER, Purity.WAFER_GRADE_NINES);
            TinkerRegistry.registerTableCasting(new CastingRecipe(shown, RecipeMatch.of(ModItems.dopedWaferMold(mold)), fluid,
                    WaferRules.CASTING_TIME, true, false));
        }
        MinecraftForge.EVENT_BUS.register(new WaferCasting());
        YouranMod.LOG.info("已加匠魂浇铸台配方：{} mB 混合气 + 掺杂硅晶圆铸模（金 / 粘土）→ 硅晶圆", WaferRules.MIXTURE_PER_WAFER);
    }

    private static net.minecraftforge.fluids.Fluid mixtureFluid() {
        return moe.qingu.youran.registry.ModFluids.trichlorosilaneHydrogen;
    }

    @SubscribeEvent
    public void onCasted(TinkerCastingEvent.OnCasted event) {
        if (event.output.isEmpty() || event.output.getItem() != ModItems.siliconWafer) {
            return;
        }
        FluidStack fluid = event.tile.tank.getFluid();
        if (!FluidPurity.isMixture(fluid)) {
            return;
        }
        ItemStack cast = event.tile.getStackInSlot(0);
        WaferMold mold = cast.getItem() instanceof ItemDopedWaferMold ? ((ItemDopedWaferMold) cast.getItem()).mold : null;
        event.output = ItemSiliconWafer.make(ModItems.siliconWafer, WaferRules.waferNines(FluidPurity.nines(fluid)), mold);
    }
}
