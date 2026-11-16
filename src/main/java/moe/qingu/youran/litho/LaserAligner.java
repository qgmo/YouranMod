package moe.qingu.youran.litho;

import moe.qingu.youran.api.pcb.BoardNbt;
import moe.qingu.youran.api.pcb.LaserAlignProgram;
import moe.qingu.youran.item.ChipNbt;
import moe.qingu.youran.item.ItemHeNeTube;
import moe.qingu.youran.item.ItemLaserCavity;
import moe.qingu.youran.item.ItemMotherboard;
import moe.qingu.youran.item.LaserNbt;
import moe.qingu.youran.logic.litho.ChipModel;
import moe.qingu.youran.logic.litho.DuvNumbers;
import moe.qingu.youran.logic.litho.LaserAlign;
import moe.qingu.youran.logic.litho.LithoNumbers;
import moe.qingu.youran.logic.pcb.BoardType;
import moe.qingu.youran.registry.ModItems;
import net.minecraft.item.ItemStack;
import net.minecraft.nbt.NBTTagCompound;
import net.minecraftforge.items.ItemStackHandler;

import javax.annotation.Nullable;
import java.util.Random;

/**
 * 对准台上的激光对准（服主 11-16，半自动；不做新方块，原对准台界面加两个物品框）：
 * <ol>
 *     <li>{@link #SLOT_BOARD}：一块个人电脑主板，ROM 里是激光对准控制程序（掩模 ROM，或和正版镜像字节完全一样的 EPROM，
 *     {@link LaserAlignProgram}）；主板 CPU 越快收敛越快；</li>
 *     <li>{@link #SLOT_LASER}：激光源。该放氦氖激光管（633 nm，胶不感光）；放 ArF 激光腔、准分子激光光源也不拦，照样能对准，
 *     但 193 nm 会把涂好的胶照了，那一片报废（{@code ChipQuality.Reason.ALIGN_EXPOSED}，同变质胶的做法）。</li>
 * </ol>
 * 两样都对、激光出光、人已经把偏差拧进捕获范围时，“激光对准”按得下去（{@link #press}）；机器几秒内把残差收到约 0.01 μm（耗电），
 * 之后锁定跟踪，每片的漂移在捕获范围里就自动补（{@link #afterDrift}）。漂出范围、拧旋钮、拆结构、拿走主板或激光都会松开。
 * 两个格只能在界面里手动放取（同掩模槽，漏斗、管道碰不到）。
 */
public class LaserAligner {
    public static final int SLOT_BOARD = 0;
    public static final int SLOT_LASER = 1;
    public static final int SLOTS = 2;

    /** 按不了“激光对准”的原因（语言键 {@code youranmod.laser_align.problem.<小写名>}） */
    public enum Problem {
        NO_BOARD, NOT_PC_BOARD, WRONG_PROGRAM, INCOMPLETE_BOARD, CPU_BROKEN, NO_LASER, LASER_DARK, OUT_OF_CAPTURE;

        public String key() {
            return "youranmod.laser_align.problem." + name().toLowerCase(java.util.Locale.ROOT);
        }

        @Nullable
        public static Problem byOrdinal(int i) {
            return i >= 0 && i < values().length ? values()[i] : null;
        }
    }

    private final TileLithoMaster master;
    final LaserAlign logic = new LaserAlign();
    private int checkTimer;

    final ItemStackHandler inv = new ItemStackHandler(SLOTS) {
        @Override
        public int getSlotLimit(int slot) {
            return 1;
        }

        @Override
        public boolean isItemValid(int slot, ItemStack stack) {
            return slot == SLOT_BOARD ? stack.getItem() instanceof ItemMotherboard : isLaserSource(stack);
        }

        @Override
        protected void onContentsChanged(int slot) {
            master.markDirty();
        }
    };

    LaserAligner(TileLithoMaster master) {
        this.master = master;
    }

    /** 激光源格收的东西：氦氖激光管；ArF 激光腔、准分子激光光源也收（不拦，会把胶照了） */
    public static boolean isLaserSource(ItemStack s) {
        return !s.isEmpty() && (s.getItem() instanceof ItemHeNeTube || isDuv(s));
    }

    /** 是不是 DUV（193 nm）的激光：ArF 激光腔或准分子激光光源 */
    public static boolean isDuv(ItemStack s) {
        return !s.isEmpty() && (s.getItem() instanceof ItemLaserCavity || s.getItem() == ModItems.excimerLightSource);
    }

    /** 激光源现在的强度（0–1） */
    public static double strength(ItemStack s, long now) {
        if (s.isEmpty()) {
            return 0;
        }
        if (s.getItem() instanceof ItemHeNeTube) {
            return ItemHeNeTube.spec(s).output(now);
        }
        if (isDuv(s)) {
            return LaserNbt.excimer(s.getTagCompound()).strength();
        }
        return 0;
    }

    /** 主板上 CPU 跑多快（kHz）：芯片的最大频率，不超过这型 CPU 的可调上限；报废的、没有的为 0 */
    public static int cpuKHz(NBTTagCompound board) {
        if (!BoardNbt.hasCpu(board)) {
            return 0;
        }
        NBTTagCompound cpu = board.getCompoundTag(BoardNbt.CPU);
        if (cpu.getBoolean(ChipNbt.SCRAPPED)) {
            return 0;
        }
        ChipModel m = ChipModel.byId(cpu.getString(ChipModel.TAG));
        int max = cpu.getInteger(ChipNbt.MAX_FREQ);
        return m != null && m.maxAdjustKHz > 0 ? Math.min(max, m.maxAdjustKHz) : max;
    }

    /** 主板这一格的问题（不看激光、捕获范围）；没问题为 null */
    @Nullable
    public static Problem boardProblem(ItemStack s) {
        if (s.isEmpty() || !(s.getItem() instanceof ItemMotherboard)) {
            return Problem.NO_BOARD;
        }
        if (ItemMotherboard.type(s) != BoardType.PC) {
            return Problem.NOT_PC_BOARD;
        }
        NBTTagCompound board = ItemMotherboard.board(s);
        if (!LaserAlignProgram.genuine(BoardNbt.romContent(board))) {
            return Problem.WRONG_PROGRAM;
        }
        if (!BoardNbt.complete(board)) {
            return Problem.INCOMPLETE_BOARD;
        }
        if (cpuKHz(board) <= 0) {
            return Problem.CPU_BROKEN;
        }
        return null;
    }

    long now() {
        return master.getWorld() == null ? -1 : master.getWorld().getTotalWorldTime();
    }

    ItemStack board() {
        return inv.getStackInSlot(SLOT_BOARD);
    }

    ItemStack laser() {
        return inv.getStackInSlot(SLOT_LASER);
    }

    /** 主板、程序、激光都对（不看捕获范围）时为 null */
    @Nullable
    Problem readiness() {
        Problem p = boardProblem(board());
        if (p != null) {
            return p;
        }
        if (!isLaserSource(laser())) {
            return Problem.NO_LASER;
        }
        if (strength(laser(), now()) < DuvNumbers.LASER_MIN_STRENGTH) {
            return Problem.LASER_DARK;
        }
        return null;
    }

    /** 现在按不了“激光对准”的原因；能按为 null */
    @Nullable
    public Problem problem() {
        Problem p = readiness();
        if (p != null) {
            return p;
        }
        return LaserAlign.inCapture(master.alignment) ? null : Problem.OUT_OF_CAPTURE;
    }

    int cpu() {
        return cpuKHz(ItemMotherboard.board(board()));
    }

    /** 操作者按“激光对准”：能按时开始收敛，返回 true */
    public boolean press(Random random) {
        if (problem() != null) {
            return false;
        }
        if (!logic.engage(master.alignment, cpu(), strength(laser(), now()), random)) {
            return false;
        }
        master.aligned = false;
        checkTimer = 0;
        master.markDirty();
        return true;
    }

    /** 每 tick：收敛（耗电）、锁定后定期检查主板与激光还在不在；DUV 激光照到涂好的胶就记下 */
    void tick() {
        if (!logic.on()) {
            return;
        }
        if (++checkTimer >= DuvNumbers.LASER_CHECK_INTERVAL) {
            checkTimer = 0;
            if (readiness() != null) {
                release();
                return;
            }
        }
        if (isDuv(laser()) && master.resistOnWafer() && !master.alignExposed) {
            master.alignExposed = true;
            master.markDirty();
        }
        if (logic.state() == LaserAlign.State.CONVERGING) {
            if (!master.energy.consume(DuvNumbers.LASER_FE_PER_TICK)) {
                return;
            }
            logic.step(master.alignment);
            if (logic.state() == LaserAlign.State.LOCKED) {
                master.aligned = master.alignRate() >= LithoNumbers.ALIGN_MIN_PERMILLE;
            }
            master.markDirty();
        }
    }

    /** 出一片、台子漂了以后（主方块已经漂过）：锁着的话，还在捕获范围里就重新收敛；漂出去就松开 */
    void afterDrift(Random random) {
        if (!logic.on()) {
            return;
        }
        if (readiness() != null) {
            release();
            return;
        }
        if (logic.afterDrift(master.alignment, cpu(), strength(laser(), now()), random)) {
            master.aligned = false;
        }
        master.markDirty();
    }

    /** 松开（拧旋钮、拆结构、主板或激光不对了）：对准的状态照原样留着，按人工对准的规矩算 */
    public void release() {
        if (logic.on()) {
            logic.release();
            master.markDirty();
        }
    }

    public boolean converging() {
        return logic.state() == LaserAlign.State.CONVERGING;
    }

    public boolean locked() {
        return logic.state() == LaserAlign.State.LOCKED;
    }

    /** 界面要的：状态、按不了的原因、收敛进度、激光强度、CPU 频率 */
    NBTTagCompound guiData() {
        NBTTagCompound t = new NBTTagCompound();
        t.setInteger("State", logic.state().ordinal());
        Problem p = problem();
        t.setInteger("Problem", p == null ? -1 : p.ordinal());
        t.setInteger("Progress", (int) Math.round(logic.progress() * 1000));
        t.setInteger("Strength", (int) Math.round(strength(laser(), now()) * 1000));
        t.setInteger("Cpu", boardProblem(board()) == null ? cpu() : 0);
        t.setBoolean("Duv", isDuv(laser()));
        return t;
    }

    void writeToNBT(NBTTagCompound nbt) {
        nbt.setTag("LaserInv", inv.serializeNBT());
        double[] d = logic.save();
        NBTTagCompound l = new NBTTagCompound();
        for (int i = 0; i < d.length; i++) {
            l.setDouble("D" + i, d[i]);
        }
        nbt.setTag("Laser", l);
    }

    void readFromNBT(NBTTagCompound nbt) {
        if (nbt.hasKey("LaserInv", 10)) {
            inv.deserializeNBT(nbt.getCompoundTag("LaserInv"));
        }
        NBTTagCompound l = nbt.getCompoundTag("Laser");
        double[] d = new double[9];
        for (int i = 0; i < d.length; i++) {
            d[i] = l.getDouble("D" + i);
        }
        logic.restore(l.isEmpty() ? null : d);
    }
}
