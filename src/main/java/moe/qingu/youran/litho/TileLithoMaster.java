package moe.qingu.youran.litho;

import moe.qingu.youran.fluid.FluidVariants;
import moe.qingu.youran.item.ChipNbt;
import moe.qingu.youran.item.ItemModelled;
import moe.qingu.youran.item.ItemPhotomask;
import moe.qingu.youran.item.WaferNbt;
import moe.qingu.youran.logic.litho.Alignment;
import moe.qingu.youran.logic.litho.ChipModel;
import moe.qingu.youran.logic.litho.ChipPackage;
import moe.qingu.youran.logic.litho.ChipQuality;
import moe.qingu.youran.logic.litho.ExcimerSpec;
import moe.qingu.youran.logic.litho.LightSpec;
import moe.qingu.youran.logic.litho.LithoNumbers;
import moe.qingu.youran.logic.litho.LithoStep;
import moe.qingu.youran.logic.litho.LithoStructure;
import moe.qingu.youran.logic.litho.LithoStructure.Part;
import moe.qingu.youran.logic.litho.LithoStructure.Port;
import moe.qingu.youran.logic.litho.ResistType;
import moe.qingu.youran.logic.litho.Solvent;
import moe.qingu.youran.registry.ModItems;
import net.minecraft.block.state.IBlockState;
import net.minecraft.entity.player.EntityPlayer;
import net.minecraft.inventory.InventoryHelper;
import net.minecraft.item.ItemStack;
import net.minecraft.nbt.NBTTagCompound;
import net.minecraft.nbt.NBTTagList;
import net.minecraft.tileentity.TileEntity;
import net.minecraft.util.EnumFacing;
import net.minecraft.util.ITickable;
import net.minecraft.util.math.BlockPos;
import net.minecraftforge.energy.IEnergyStorage;
import net.minecraftforge.fluids.FluidActionResult;
import net.minecraftforge.fluids.FluidStack;
import net.minecraftforge.fluids.FluidUtil;
import net.minecraftforge.fluids.capability.CapabilityFluidHandler;
import net.minecraftforge.fluids.capability.IFluidHandler;
import net.minecraftforge.fluids.capability.IFluidTankProperties;
import net.minecraftforge.items.CapabilityItemHandler;
import net.minecraftforge.items.IItemHandler;
import net.minecraftforge.items.ItemHandlerHelper;
import net.minecraftforge.items.ItemStackHandler;

import javax.annotation.Nullable;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * 光刻机的主方块（成形后的对准台）：罐、电、物品格、工序状态机、对准状态都在这里。
 *
 * <p>工序（{@link LithoStep}）：每一步开始时扣这一步的原料（不够就等着），然后按时间走、每 tick 扣电；晶圆到出芯片时才扣
 * （拆开时原样掉回）。前五步每层一遍，层数看掩模；掺杂、金属化的 0.25 硼锭、0.25 铝粉按四分之一记余量。
 *
 * <p>拆开（{@link #disassemble}）：照沉浸工程，敲掉任意一格整台还原；物品格掉出来，罐里的液体、电、对准、正在跑的工序都丢掉；
 * 光源原地不动、只清“已接入”。
 */
public class TileLithoMaster extends TileLithoPart implements ITickable {
    static final String STEAM = "ic2steam";
    static final String SUPERHEATED = "ic2superheated_steam";
    static final String DISTILLED = "ic2distilled_water";
    static final String ACID = "h_cl_fluid";

    public static final int TANK_STEAM = 0;
    public static final int TANK_RESIST = 1;
    public static final int TANK_SOLVENT = 2;
    public static final int TANK_ETCHANT = 3;
    public static final int TANK_OUTPUT = 4;

    final LithoTank[] tanks = {
            new LithoTank(LithoNumbers.INPUT_TANK, f -> is(f, STEAM) || is(f, SUPERHEATED)),
            new LithoTank(LithoNumbers.INPUT_TANK, f -> FluidVariants.resist(f) != null),
            new LithoTank(LithoNumbers.INPUT_TANK, f -> Solvent.byFluid(f.getFluid().getName()) != null && !FluidVariants.isAzideArene(f)),
            new LithoTank(LithoNumbers.INPUT_TANK, f -> is(f, ACID)),
            new LithoTank(LithoNumbers.OUTPUT_TANK, f -> is(f, STEAM) || is(f, DISTILLED)),
    };
    final LithoEnergy energy = new LithoEnergy();
    final ItemStackHandler inv = new ItemStackHandler(LithoSlots.COUNT) {
        @Override
        public int getSlotLimit(int slot) {
            return slot == LithoSlots.MASK || LithoSlots.isContainer(slot) || LithoSlots.isWafer(slot) || LithoSlots.isOutput(slot) ? 1 : 64;
        }

        @Override
        public boolean isItemValid(int slot, ItemStack stack) {
            if (slot == LithoSlots.MASK) {
                return LithoItems.isMask(stack);
            }
            if (LithoSlots.isContainer(slot)) {
                return stack.hasCapability(CapabilityFluidHandler.FLUID_HANDLER_ITEM_CAPABILITY, null);
            }
            if (LithoSlots.isOutput(slot)) {
                return false;
            }
            int want = LithoItems.slotFor(stack);
            return want == slot || (want == LithoSlots.WAFER_FIRST && LithoSlots.isWafer(slot));
        }

        @Override
        protected void onContentsChanged(int slot) {
            markDirty();
        }
    };

    // ---- 工序 ----
    LithoStep step = LithoStep.IDLE;
    int layer;
    int progress;
    int required;
    boolean stepStarted;
    int activeWafer = -1;
    @Nullable
    ChipModel activeModel;
    // 这片晶圆一路记下的条件（报废与指标用）
    int worstSolvent = 1000;
    int worstAlign = 1000;
    int worstLamp = 1000;
    double lampResolution;
    @Nullable
    ResistType resist;
    /** 这片晶圆有哪一层涂了变质（已曝光）的光刻胶：做出来一定报废 */
    boolean exposedResist;
    /** 这片晶圆有哪一层涂好胶以后被对准用的 DUV 激光照了（激光源格放了 ArF 激光腔等）：做出来一定报废 */
    boolean alignExposed;
    @Nullable
    ChipPackage pack;
    int boronQuarters;
    int aluminumQuarters;
    LithoStatus status = LithoStatus.IDLE;

    // ---- 对准 ----
    final Alignment alignment = new Alignment();
    boolean aligned;
    /** 激光对准（服主 11-16）：对准台界面的主板、激光源两格与收敛 / 锁定状态 */
    final LaserAligner laser = new LaserAligner(this);
    boolean rsInverted;
    /** 开着显微镜的玩家，先开的是操作者（不存档） */
    final List<UUID> microscopeViewers = new ArrayList<>();

    private int tickCount;

    static boolean is(FluidStack f, String name) {
        return f != null && f.getFluid() != null && name.equals(f.getFluid().getName());
    }

    // ---- 位置 ----

    /** 结构里 (h, l, w) 这一格在世界里的位置 */
    public BlockPos posOf(int h, int l, int w) {
        return pos.offset(facing, l - LithoStructure.MASTER_L).offset(wDir(facing, mirrored), w - LithoStructure.MASTER_W)
                .up(h - LithoStructure.MASTER_H);
    }

    public BlockPos lightPos() {
        return pos.up();
    }

    @Nullable
    public TileLightSource light() {
        return world == null ? null : BlockLightSource.at(world, lightPos());
    }

    // ---- 成形后的初始化 ----

    void onFormed() {
        alignment.randomize(world.rand);
        aligned = false;
        laser.release();
        TileLightSource light = light();
        if (light != null) {
            light.link(pos);
        }
        markDirty();
    }

    // ---- 能力（接口格转过来的）----

    IFluidHandler fluidPort(Port port) {
        return new PortFluidHandler(tanks[port.tank], port.input);
    }

    IEnergyStorage energy() {
        return energy;
    }

    IItemHandler itemInput() {
        return new InputHandler();
    }

    IItemHandler itemOutput() {
        return new OutputHandler();
    }

    public IItemHandler inventory() {
        return inv;
    }

    public LithoTank tank(int i) {
        return tanks[i];
    }

    final class PortFluidHandler implements IFluidHandler {
        private final LithoTank tank;
        private final boolean input;

        PortFluidHandler(LithoTank tank, boolean input) {
            this.tank = tank;
            this.input = input;
        }

        @Override
        public IFluidTankProperties[] getTankProperties() {
            return tank.getTankProperties();
        }

        @Override
        public int fill(FluidStack resource, boolean doFill) {
            if (!input || resource == null) {
                return 0;
            }
            int n = tank.fill(resource, doFill);
            if (n > 0 && doFill) {
                markDirty();
            }
            return n;
        }

        @Nullable
        @Override
        public FluidStack drain(FluidStack resource, boolean doDrain) {
            if (input) {
                return null;
            }
            FluidStack out = tank.drain(resource, doDrain);
            if (out != null && doDrain) {
                markDirty();
            }
            return out;
        }

        @Nullable
        @Override
        public FluidStack drain(int maxDrain, boolean doDrain) {
            if (input) {
                return null;
            }
            FluidStack out = tank.drain(maxDrain, doDrain);
            if (out != null && doDrain) {
                markDirty();
            }
            return out;
        }
    }

    /** 物品入口：按种类分进晶圆、辅料、外壳格；不能往外抽；碰不到掩模槽 */
    final class InputHandler implements IItemHandler {
        @Override
        public int getSlots() {
            return 1;
        }

        @Override
        public ItemStack getStackInSlot(int slot) {
            return ItemStack.EMPTY;
        }

        @Override
        public ItemStack insertItem(int slot, ItemStack stack, boolean simulate) {
            int target = LithoItems.slotFor(stack);
            if (target < 0) {
                return stack;
            }
            if (target == LithoSlots.WAFER_FIRST) {
                ItemStack left = stack;
                for (int i = 0; i < LithoSlots.WAFERS && !left.isEmpty(); i++) {
                    left = inv.insertItem(LithoSlots.WAFER_FIRST + i, left, simulate);
                }
                return left;
            }
            return inv.insertItem(target, stack, simulate);
        }

        @Override
        public ItemStack extractItem(int slot, int amount, boolean simulate) {
            return ItemStack.EMPTY;
        }

        @Override
        public int getSlotLimit(int slot) {
            return 64;
        }
    }

    /** 物品出口：只能抽芯片 */
    final class OutputHandler implements IItemHandler {
        @Override
        public int getSlots() {
            return LithoSlots.OUTPUTS;
        }

        @Override
        public ItemStack getStackInSlot(int slot) {
            return inv.getStackInSlot(LithoSlots.OUTPUT_FIRST + slot);
        }

        @Override
        public ItemStack insertItem(int slot, ItemStack stack, boolean simulate) {
            return stack;
        }

        @Override
        public ItemStack extractItem(int slot, int amount, boolean simulate) {
            if (slot < 0 || slot >= LithoSlots.OUTPUTS) {
                return ItemStack.EMPTY;
            }
            return inv.extractItem(LithoSlots.OUTPUT_FIRST + slot, amount, simulate);
        }

        @Override
        public int getSlotLimit(int slot) {
            return 1;
        }
    }

    // ---- 红石 ----

    public boolean redstoneStopped() {
        BlockPos r = posOf(Port.REDSTONE.h, Port.REDSTONE.l, Port.REDSTONE.w);
        if (!world.isBlockLoaded(r)) {
            return false;
        }
        boolean powered = world.isBlockPowered(r) || world.getRedstonePowerFromNeighbors(r) > 0;
        return powered != rsInverted;
    }

    public void toggleRedstone() {
        rsInverted = !rsInverted;
        markDirty();
    }

    // ---- 对准 ----

    @Nullable
    public ChipModel maskModel() {
        ItemStack mask = inv.getStackInSlot(LithoSlots.MASK);
        return LithoItems.isMask(mask) ? ItemModelled.model(mask) : null;
    }

    /** 对准公差（μm）：看掩模；没放掩模时按 Z80 */
    public double tolerance() {
        ChipModel m = maskModel();
        return (m == null ? ChipModel.Z80 : m).toleranceUm();
    }

    public int alignRate() {
        return alignment.ratePermille(tolerance());
    }

    public Alignment alignment() {
        return alignment;
    }

    public boolean aligned() {
        return aligned;
    }

    /** 操作者拧一格旋钮：拧过以后要重新按“完成对准”（激光对准也松开，人接手） */
    public void turnKnob(Alignment.Axis axis, int dir, boolean fine) {
        laser.release();
        alignment.turn(axis, dir, fine);
        aligned = false;
        markDirty();
    }

    /** 操作者按“完成对准”：对准率到了报废线以上才算对准 */
    public boolean confirmAlignment() {
        aligned = alignRate() >= LithoNumbers.ALIGN_MIN_PERMILLE;
        markDirty();
        return aligned;
    }

    /** 操作者按“激光对准”：主板、程序、激光都对且偏差在捕获范围里才开始 */
    public boolean pressLaserAlign() {
        return laser.press(world.rand);
    }

    public LaserAligner laserAligner() {
        return laser;
    }

    /** 晶圆上现在有没曝完 / 没显影的胶（涂胶开始后到显影开始前）：对准用的 DUV 激光照到就报废 */
    boolean resistOnWafer() {
        return activeWafer >= 0 && ((step == LithoStep.COAT && stepStarted) || step == LithoStep.EXPOSE
                || (step == LithoStep.DEVELOP && !stepStarted));
    }

    @Nullable
    public UUID operator() {
        return microscopeViewers.isEmpty() ? null : microscopeViewers.get(0);
    }

    void openMicroscope(EntityPlayer player) {
        if (!microscopeViewers.contains(player.getUniqueID())) {
            microscopeViewers.add(player.getUniqueID());
        }
    }

    void closeMicroscope(EntityPlayer player) {
        microscopeViewers.remove(player.getUniqueID());
    }

    // ---- 每 tick ----

    @Override
    public void update() {
        if (world.isRemote || !formed) {
            return;
        }
        tickCount++;
        laser.tick();
        drainContainers();
        pushFluid();
        if (tickCount % LithoNumbers.PUSH_INTERVAL == 0) {
            pushChips();
        }
        TileLightSource light = light();
        boolean exposing = false;
        if (redstoneStopped()) {
            status = LithoStatus.REDSTONE;
        } else {
            exposing = work(light);
        }
        if (light != null) {
            light.setLit(exposing);
        }
    }

    /** 走一 tick 工序；返回这一 tick 是不是在曝光（光源发光） */
    private boolean work(@Nullable TileLightSource light) {
        if (step != LithoStep.IDLE && (activeWafer < 0 || !LithoItems.isWafer(inv.getStackInSlot(activeWafer)))) {
            resetProcess();
            status = LithoStatus.WAFER_REMOVED;
            markDirty();
            return false;
        }
        if (step == LithoStep.IDLE && !tryStart(light)) {
            return false;
        }
        if (!stepStarted) {
            LithoStatus lack = startStep(light);
            if (lack != null) {
                status = lack;
                return false;
            }
            stepStarted = true;
            progress = 0;
            markDirty();
        }
        if (progress < required) {
            int fe = step == LithoStep.EXPOSE && light != null ? light.spec().exposeFePerTick() : step.fePerTick;
            if (!energy.consume(fe)) {
                status = LithoStatus.NO_POWER;
                return false;
            }
            progress++;
            status = LithoStatus.RUNNING;
            markDirty();
        }
        boolean exposing = step == LithoStep.EXPOSE;
        if (progress >= required) {
            finishStep();
        }
        return exposing;
    }

    private boolean tryStart(@Nullable TileLightSource light) {
        int wafer = -1;
        for (int i = 0; i < LithoSlots.WAFERS; i++) {
            if (LithoItems.isWafer(inv.getStackInSlot(LithoSlots.WAFER_FIRST + i))) {
                wafer = LithoSlots.WAFER_FIRST + i;
                break;
            }
        }
        if (wafer < 0) {
            status = status == LithoStatus.WAFER_REMOVED ? status : LithoStatus.NO_WAFER;
            return false;
        }
        LithoStatus blocked = exposeBlocked(light, null);
        if (blocked != null) {
            status = blocked;
            return false;
        }
        resetProcess();
        activeWafer = wafer;
        activeModel = maskModel();
        step = LithoStep.OXIDIZE;
        layer = 0;
        markDirty();
        return true;
    }

    /** 能不能曝光：掩模、光源、对准；{@code model} 不为 null 时掩模还要是这一型 */
    @Nullable
    private LithoStatus exposeBlocked(@Nullable TileLightSource light, @Nullable ChipModel model) {
        ItemStack mask = inv.getStackInSlot(LithoSlots.MASK);
        ChipModel m = maskModel();
        if (m == null) {
            return LithoStatus.NO_MASK;
        }
        if (model != null && m != model) {
            return LithoStatus.WRONG_MASK;
        }
        if (ItemPhotomask.wornOut(mask)) {
            return LithoStatus.MASK_WORN;
        }
        if (light == null) {
            return LithoStatus.NO_LIGHT;
        }
        LightSpec spec = light.spec();
        boolean excimer = spec instanceof ExcimerSpec;
        if (spec.burntOut()) {
            return excimer ? LithoStatus.LASER_OUT : LithoStatus.LAMP_OUT;
        }
        if (spec.noFuel()) {
            return excimer ? LithoStatus.NO_LASER_GAS : LithoStatus.NO_MERCURY;
        }
        if (laser.converging()) {
            return LithoStatus.LASER_ALIGNING;
        }
        if (!aligned || alignRate() < LithoNumbers.ALIGN_MIN_PERMILLE) {
            return LithoStatus.NOT_ALIGNED;
        }
        return null;
    }

    private void resetProcess() {
        step = LithoStep.IDLE;
        layer = 0;
        progress = 0;
        required = 0;
        stepStarted = false;
        activeWafer = -1;
        activeModel = null;
        worstSolvent = 1000;
        worstAlign = 1000;
        worstLamp = 1000;
        lampResolution = 0;
        resist = null;
        exposedResist = false;
        alignExposed = false;
        pack = null;
    }

    /** 扣这一步的原料、定时间；缺东西时返回缺什么（不扣） */
    @Nullable
    private LithoStatus startStep(@Nullable TileLightSource light) {
        required = step.ticks;
        switch (step) {
            case OXIDIZE: {
                FluidStack steam = tanks[TANK_STEAM].getFluid();
                if (steam == null || steam.amount < LithoNumbers.OXIDIZE_STEAM) {
                    return LithoStatus.NEED_STEAM;
                }
                FluidStack product = is(steam, SUPERHEATED)
                        ? net.minecraftforge.fluids.FluidRegistry.getFluidStack(STEAM, LithoNumbers.SUPERHEATED_TO_STEAM)
                        : net.minecraftforge.fluids.FluidRegistry.getFluidStack(DISTILLED, LithoNumbers.STEAM_TO_DISTILLED);
                if (product != null && tanks[TANK_OUTPUT].fillInternal(product, false) < product.amount) {
                    return LithoStatus.OUTPUT_TANK_FULL;
                }
                tanks[TANK_STEAM].drainInternal(LithoNumbers.OXIDIZE_STEAM, true);
                if (product != null) {
                    tanks[TANK_OUTPUT].fillInternal(product, true);
                }
                return null;
            }
            case COAT: {
                FluidStack r = tanks[TANK_RESIST].getFluid();
                ResistType type = FluidVariants.resist(r);
                if (type == null || r.amount < LithoNumbers.COAT_RESIST) {
                    return LithoStatus.NEED_RESIST;
                }
                tanks[TANK_RESIST].drainInternal(LithoNumbers.COAT_RESIST, true);
                resist = ResistType.worse(resist, type);
                exposedResist |= FluidVariants.isExposedResist(r);
                return null;
            }
            case EXPOSE: {
                LithoStatus blocked = exposeBlocked(light, activeModel);
                if (blocked != null) {
                    return blocked;
                }
                LightSpec spec = light.spec();
                required = spec.exposeTicks();
                worstLamp = Math.min(worstLamp, (int) Math.round(spec.brightness() * 1000));
                lampResolution = Math.max(lampResolution, spec.resolutionUm());
                worstAlign = Math.min(worstAlign, alignRate());
                light.addUse();
                return null;
            }
            case DEVELOP: {
                FluidStack s = tanks[TANK_SOLVENT].getFluid();
                Solvent solvent = s == null || FluidVariants.isAzideArene(s) ? null : Solvent.byFluid(s.getFluid().getName());
                if (solvent == null || s.amount < solvent.perLayer) {
                    return LithoStatus.NEED_SOLVENT;
                }
                tanks[TANK_SOLVENT].drainInternal(solvent.perLayer, true);
                worstSolvent = Math.min(worstSolvent, solvent.qualityPermille);
                return null;
            }
            case ETCH: {
                FluidStack a = tanks[TANK_ETCHANT].getFluid();
                if (a == null || a.amount < LithoNumbers.ETCH_ACID) {
                    return LithoStatus.NEED_ETCHANT;
                }
                tanks[TANK_ETCHANT].drainInternal(LithoNumbers.ETCH_ACID, true);
                return null;
            }
            case DOPE:
                return dope() ? null : LithoStatus.NEED_DOPANT;
            case METALLIZE: {
                if (aluminumQuarters < LithoNumbers.METAL_ALUMINUM_QUARTERS) {
                    if (inv.getStackInSlot(LithoSlots.ALUMINUM).isEmpty()) {
                        return LithoStatus.NEED_ALUMINUM;
                    }
                    inv.extractItem(LithoSlots.ALUMINUM, 1, false);
                    aluminumQuarters += 4;
                }
                aluminumQuarters -= LithoNumbers.METAL_ALUMINUM_QUARTERS;
                return null;
            }
            case PACKAGE: {
                if (freeOutput() < 0) {
                    return LithoStatus.OUTPUT_FULL;
                }
                ChipPackage p = LithoItems.casing(inv.getStackInSlot(LithoSlots.CASING));
                if (p == null) {
                    return LithoStatus.NEED_CASING;
                }
                inv.extractItem(LithoSlots.CASING, 1, false);
                pack = p;
                return null;
            }
            default:
                return null;
        }
    }

    /** 掺杂：0.25 硼锭 + 4 萤石粉，或 4 硼元素 + 4 磷元素（炼金化学） */
    private boolean dope() {
        ItemStack glow = inv.getStackInSlot(LithoSlots.GLOWSTONE);
        boolean boronReady = boronQuarters >= LithoNumbers.DOPE_BORON_QUARTERS || !inv.getStackInSlot(LithoSlots.BORON_INGOT).isEmpty();
        if (boronReady && glow.getCount() >= LithoNumbers.DOPE_GLOWSTONE) {
            if (boronQuarters < LithoNumbers.DOPE_BORON_QUARTERS) {
                inv.extractItem(LithoSlots.BORON_INGOT, 1, false);
                boronQuarters += 4;
            }
            boronQuarters -= LithoNumbers.DOPE_BORON_QUARTERS;
            inv.extractItem(LithoSlots.GLOWSTONE, LithoNumbers.DOPE_GLOWSTONE, false);
            return true;
        }
        if (inv.getStackInSlot(LithoSlots.BORON_ELEMENT).getCount() >= LithoNumbers.DOPE_BORON_ELEMENT
                && inv.getStackInSlot(LithoSlots.PHOSPHORUS).getCount() >= LithoNumbers.DOPE_PHOSPHORUS_ELEMENT) {
            inv.extractItem(LithoSlots.BORON_ELEMENT, LithoNumbers.DOPE_BORON_ELEMENT, false);
            inv.extractItem(LithoSlots.PHOSPHORUS, LithoNumbers.DOPE_PHOSPHORUS_ELEMENT, false);
            return true;
        }
        return false;
    }

    private int freeOutput() {
        for (int i = 0; i < LithoSlots.OUTPUTS; i++) {
            if (inv.getStackInSlot(LithoSlots.OUTPUT_FIRST + i).isEmpty()) {
                return LithoSlots.OUTPUT_FIRST + i;
            }
        }
        return -1;
    }

    private void finishStep() {
        int layers = activeModel == null ? 1 : activeModel.layers;
        if (step == LithoStep.PACKAGE) {
            produceChip();
        }
        LithoStep next = step.next(layer, layers);
        layer = step.nextLayer(layer, layers);
        step = next;
        stepStarted = false;
        progress = 0;
        required = 0;
        if (step == LithoStep.IDLE) {
            resetProcess();
        }
        markDirty();
    }

    private void produceChip() {
        ItemStack wafer = inv.getStackInSlot(activeWafer);
        ItemStack mask = inv.getStackInSlot(LithoSlots.MASK);
        ChipModel model = activeModel == null ? ChipModel.Z80 : activeModel;
        ChipQuality.Inputs in = new ChipQuality.Inputs();
        in.model = model;
        in.waferNines = WaferNbt.nines(wafer.getTagCompound());
        in.mold = WaferNbt.mold(wafer.getTagCompound());
        in.resist = resist == null ? ResistType.NEGATIVE : resist;
        in.exposedResist = exposedResist;
        in.alignExposed = alignExposed;
        in.solvent = worstSolvent / 1000.0;
        in.alignPermille = worstAlign;
        in.lampBrightness = worstLamp / 1000.0;
        in.lampResolutionUm = lampResolution;
        in.maskWear = ItemPhotomask.wear(mask) / (double) LithoNumbers.MASK_LIFE;
        in.pack = pack == null ? ChipPackage.IRON : pack;
        ChipQuality.Result result = ChipQuality.evaluate(in, world.rand);

        NBTTagCompound made = new NBTTagCompound();
        made.setInteger("Nines", in.waferNines);
        if (in.mold != null) {
            made.setString("Mold", in.mold.id);
        }
        made.setString("Resist", in.resist.id);
        if (exposedResist) {
            made.setBoolean("ExposedResist", true);
        }
        if (alignExposed) {
            made.setBoolean("AlignExposed", true);
        }
        made.setInteger("Solvent", worstSolvent);
        made.setInteger("Align", worstAlign);
        made.setInteger("Lamp", worstLamp);
        made.setString("Package", in.pack.id);
        made.setInteger("MaskWear", ItemPhotomask.wear(mask));
        ItemStack chip = ModItems.chip.make(model);
        chip.setTagCompound(ChipNbt.tag(model, ChipNbt.serial(model, world.rand.nextLong()), result, made));
        inv.setStackInSlot(freeOutput(), chip);
        inv.setStackInSlot(activeWafer, ItemStack.EMPTY);
        if (LithoItems.isMask(mask)) {
            ItemPhotomask.addWear(mask);
        }
        alignment.drift(world.rand);
        if (alignRate() < LithoNumbers.ALIGN_MIN_PERMILLE) {
            aligned = false;
        }
        laser.afterDrift(world.rand);
    }

    // ---- 容器格、外推 ----

    private void drainContainers() {
        for (int i = 0; i < LithoSlots.CONTAINERS; i++) {
            int slot = LithoSlots.CONTAINER_FIRST + i;
            ItemStack stack = inv.getStackInSlot(slot);
            if (stack.isEmpty() || stack.getCount() != 1 || tanks[i].getFluidAmount() <= 0) {
                continue;
            }
            FluidActionResult r = FluidUtil.tryFillContainer(stack, tanks[i], Integer.MAX_VALUE, null, true);
            if (r.isSuccess()) {
                inv.setStackInSlot(slot, r.getResult());
            }
        }
    }

    private void pushFluid() {
        if (tanks[TANK_OUTPUT].getFluidAmount() <= 0) {
            return;
        }
        BlockPos p = posOf(Port.FLUID_OUT.h, Port.FLUID_OUT.l, Port.FLUID_OUT.w);
        BlockPos target = p.offset(facing);
        if (!world.isBlockLoaded(target)) {
            return;
        }
        TileEntity te = world.getTileEntity(target);
        if (te == null || !te.hasCapability(CapabilityFluidHandler.FLUID_HANDLER_CAPABILITY, facing.getOpposite())) {
            return;
        }
        IFluidHandler h = te.getCapability(CapabilityFluidHandler.FLUID_HANDLER_CAPABILITY, facing.getOpposite());
        if (h == null) {
            return;
        }
        FluidStack offer = tanks[TANK_OUTPUT].drainInternal(LithoNumbers.FLUID_PUSH, false);
        if (offer == null) {
            return;
        }
        int n = h.fill(offer, true);
        if (n > 0) {
            tanks[TANK_OUTPUT].drainInternal(n, true);
            markDirty();
        }
    }

    private void pushChips() {
        EnumFacing out = wDir(facing, mirrored);
        BlockPos p = posOf(Port.ITEM_OUT.h, Port.ITEM_OUT.l, Port.ITEM_OUT.w).offset(out);
        if (!world.isBlockLoaded(p)) {
            return;
        }
        TileEntity te = world.getTileEntity(p);
        if (te == null || !te.hasCapability(CapabilityItemHandler.ITEM_HANDLER_CAPABILITY, out.getOpposite())) {
            return;
        }
        IItemHandler h = te.getCapability(CapabilityItemHandler.ITEM_HANDLER_CAPABILITY, out.getOpposite());
        if (h == null) {
            return;
        }
        for (int i = 0; i < LithoSlots.OUTPUTS; i++) {
            int slot = LithoSlots.OUTPUT_FIRST + i;
            ItemStack s = inv.getStackInSlot(slot);
            if (s.isEmpty()) {
                continue;
            }
            ItemStack left = ItemHandlerHelper.insertItem(h, s.copy(), false);
            if (left.getCount() != s.getCount()) {
                inv.setStackInSlot(slot, left);
            }
        }
    }

    // ---- 拆开 ----

    /** 正在拆的时候，被替换掉的格子的 breakBlock 不要再拆一遍 */
    static boolean disassembling;

    /**
     * 整台拆开（照沉浸工程）：除 {@code broken}（正在被敲掉的那格，它自己掉物品）外，每一格放回原来的方块；
     * 物品格掉出来；光源原地不动，只清“已接入”。
     */
    public void disassemble(@Nullable BlockPos broken) {
        if (world.isRemote || !formed || disassembling) {
            return;
        }
        disassembling = true;
        try {
            List<BlockPos> parts = new ArrayList<>();
            List<IBlockState> originals = new ArrayList<>();
            for (int h = 0; h < LithoStructure.HEIGHT; h++) {
                for (int l = 0; l < LithoStructure.LENGTH; l++) {
                    for (int w = 0; w < LithoStructure.WIDTH; w++) {
                        Part part = LithoParts.partAt(h, l, w);
                        if (part == null) {
                            continue;
                        }
                        BlockPos p = posOf(h, l, w);
                        if (!world.isBlockLoaded(p)) {
                            continue;
                        }
                        TileEntity te = world.getTileEntity(p);
                        if (!(te instanceof TileLithoPart) || !((TileLithoPart) te).formed || !((TileLithoPart) te).masterPos().equals(pos)) {
                            continue;
                        }
                        ((TileLithoPart) te).formed = false;
                        parts.add(p);
                        originals.add(LithoParts.original(part));
                    }
                }
            }
            formed = false;
            TileLightSource light = light();
            if (light != null && pos.equals(light.master())) {
                light.link(null);
            }
            BlockPos dropAt = broken == null ? pos : broken;
            for (ItemStackHandler h : new ItemStackHandler[]{inv, laser.inv}) {
                for (int i = 0; i < h.getSlots(); i++) {
                    ItemStack s = h.getStackInSlot(i);
                    if (!s.isEmpty()) {
                        InventoryHelper.spawnItemStack(world, dropAt.getX() + .5, dropAt.getY() + .5, dropAt.getZ() + .5, s.copy());
                        h.setStackInSlot(i, ItemStack.EMPTY);
                    }
                }
            }
            laser.release();
            for (int i = 0; i < parts.size(); i++) {
                BlockPos p = parts.get(i);
                IBlockState s = originals.get(i);
                if (p.equals(broken) || s == null) {
                    continue;
                }
                world.setBlockState(p, s, 3);
            }
        } finally {
            disassembling = false;
        }
    }

    // ---- 界面数据 ----

    /** 主界面要的数据（发给开着界面的玩家） */
    public NBTTagCompound guiData() {
        NBTTagCompound tag = new NBTTagCompound();
        NBTTagList t = new NBTTagList();
        for (LithoTank tank : tanks) {
            NBTTagCompound one = new NBTTagCompound();
            if (tank.getFluid() != null) {
                tank.getFluid().writeToNBT(one);
            }
            one.setInteger("Cap", tank.getCapacity());
            t.appendTag(one);
        }
        tag.setTag("Tanks", t);
        tag.setInteger("Energy", energy.getEnergyStored());
        tag.setInteger("EnergyCap", energy.getMaxEnergyStored());
        tag.setInteger("Step", step.ordinal());
        tag.setInteger("Layer", layer);
        tag.setInteger("Layers", activeModel == null ? 0 : activeModel.layers);
        tag.setInteger("Progress", progress);
        tag.setInteger("Required", required);
        tag.setInteger("Status", status.ordinal());
        tag.setInteger("Rate", alignRate());
        tag.setBoolean("Aligned", aligned);
        tag.setBoolean("RsInverted", rsInverted);
        TileLightSource light = light();
        if (light != null) {
            tag.setTag("Lamp", light.lampData());
            tag.setBoolean("Excimer", light.excimer());
        }
        tag.setTag("Laser", laser.guiData());
        tag.setInteger("BoronQ", boronQuarters);
        tag.setInteger("AlQ", aluminumQuarters);
        return tag;
    }

    /** 显微镜要的数据 */
    public NBTTagCompound microscopeData(EntityPlayer viewer) {
        NBTTagCompound tag = new NBTTagCompound();
        double[] l = alignment.left();
        double[] r = alignment.right();
        tag.setDouble("LX", l[0]);
        tag.setDouble("LY", l[1]);
        tag.setDouble("RX", r[0]);
        tag.setDouble("RY", r[1]);
        tag.setInteger("Rate", alignRate());
        tag.setDouble("Tol", tolerance());
        tag.setBoolean("Aligned", aligned);
        UUID op = operator();
        tag.setBoolean("Operator", viewer.getUniqueID().equals(op));
        EntityPlayer opPlayer = op == null ? null : world.getPlayerEntityByUUID(op);
        tag.setString("OperatorName", opPlayer == null ? "" : opPlayer.getName());
        tag.setTag("Laser", laser.guiData());
        return tag;
    }

    // ---- 存档 ----

    @Override
    public void readFromNBT(NBTTagCompound nbt) {
        super.readFromNBT(nbt);
        NBTTagList t = nbt.getTagList("Tanks", 10);
        for (int i = 0; i < tanks.length && i < t.tagCount(); i++) {
            tanks[i].readFromNBT(t.getCompoundTagAt(i));
        }
        energy.set(nbt.getInteger("Energy"));
        inv.deserializeNBT(nbt.getCompoundTag("Inv"));
        step = LithoStep.byOrdinal(nbt.getInteger("Step"));
        layer = nbt.getInteger("Layer");
        progress = nbt.getInteger("Progress");
        required = nbt.getInteger("Required");
        stepStarted = nbt.getBoolean("StepStarted");
        activeWafer = nbt.getInteger("ActiveWafer");
        activeModel = ChipModel.byId(nbt.getString("ActiveModel"));
        worstSolvent = nbt.hasKey("WorstSolvent") ? nbt.getInteger("WorstSolvent") : 1000;
        worstAlign = nbt.hasKey("WorstAlign") ? nbt.getInteger("WorstAlign") : 1000;
        worstLamp = nbt.hasKey("WorstLamp") ? nbt.getInteger("WorstLamp") : 1000;
        lampResolution = nbt.getDouble("LampRes");
        resist = ResistType.byId(nbt.getString("Resist"));
        exposedResist = nbt.getBoolean("ExposedResist");
        alignExposed = nbt.getBoolean("AlignExposed");
        laser.readFromNBT(nbt);
        pack = ChipPackage.byId(nbt.getString("Package"));
        boronQuarters = nbt.getInteger("BoronQ");
        aluminumQuarters = nbt.getInteger("AlQ");
        status = LithoStatus.byOrdinal(nbt.getInteger("Status"));
        NBTTagCompound a = nbt.getCompoundTag("Align");
        alignment.restore(a.getDouble("X"), a.getDouble("Y"), a.getDouble("T"), a.getIntArray("Dir"),
                new double[]{a.getDouble("S0"), a.getDouble("S1"), a.getDouble("S2")});
        aligned = nbt.getBoolean("Aligned");
        rsInverted = nbt.getBoolean("RsInverted");
    }

    @Override
    public NBTTagCompound writeToNBT(NBTTagCompound nbt) {
        super.writeToNBT(nbt);
        NBTTagList t = new NBTTagList();
        for (LithoTank tank : tanks) {
            t.appendTag(tank.writeToNBT(new NBTTagCompound()));
        }
        nbt.setTag("Tanks", t);
        nbt.setInteger("Energy", energy.getEnergyStored());
        nbt.setTag("Inv", inv.serializeNBT());
        nbt.setInteger("Step", step.ordinal());
        nbt.setInteger("Layer", layer);
        nbt.setInteger("Progress", progress);
        nbt.setInteger("Required", required);
        nbt.setBoolean("StepStarted", stepStarted);
        nbt.setInteger("ActiveWafer", activeWafer);
        if (activeModel != null) {
            nbt.setString("ActiveModel", activeModel.id);
        }
        nbt.setInteger("WorstSolvent", worstSolvent);
        nbt.setInteger("WorstAlign", worstAlign);
        nbt.setInteger("WorstLamp", worstLamp);
        nbt.setDouble("LampRes", lampResolution);
        if (resist != null) {
            nbt.setString("Resist", resist.id);
        }
        if (exposedResist) {
            nbt.setBoolean("ExposedResist", true);
        }
        if (alignExposed) {
            nbt.setBoolean("AlignExposed", true);
        }
        laser.writeToNBT(nbt);
        if (pack != null) {
            nbt.setString("Package", pack.id);
        }
        nbt.setInteger("BoronQ", boronQuarters);
        nbt.setInteger("AlQ", aluminumQuarters);
        nbt.setInteger("Status", status.ordinal());
        NBTTagCompound a = new NBTTagCompound();
        a.setDouble("X", alignment.x());
        a.setDouble("Y", alignment.y());
        a.setDouble("T", alignment.theta());
        a.setIntArray("Dir", new int[]{alignment.lastDir(Alignment.Axis.X), alignment.lastDir(Alignment.Axis.Y),
                alignment.lastDir(Alignment.Axis.THETA)});
        a.setDouble("S0", alignment.slack(Alignment.Axis.X));
        a.setDouble("S1", alignment.slack(Alignment.Axis.Y));
        a.setDouble("S2", alignment.slack(Alignment.Axis.THETA));
        nbt.setTag("Align", a);
        nbt.setBoolean("Aligned", aligned);
        nbt.setBoolean("RsInverted", rsInverted);
        return nbt;
    }

    /** 给界面用：这一格是不是还在（玩家离开太远、整台拆了就关界面） */
    public boolean usableBy(EntityPlayer player) {
        return formed && !isInvalid() && world.getTileEntity(pos) == this && player.getDistanceSq(pos) <= 64;
    }
}
