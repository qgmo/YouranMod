package moe.qingu.youran.item;

import moe.qingu.youran.api.pcb.BoardNbt;
import moe.qingu.youran.logic.litho.ChipModel;
import moe.qingu.youran.logic.litho.ChipPackage;
import moe.qingu.youran.logic.litho.ChipQuality;
import moe.qingu.youran.logic.litho.LampGas;
import moe.qingu.youran.logic.litho.LampGlass;
import moe.qingu.youran.logic.litho.LampSpec;
import moe.qingu.youran.logic.litho.ResistType;
import moe.qingu.youran.logic.pcb.BoardType;
import moe.qingu.youran.logic.purity.Purity;
import moe.qingu.youran.logic.purity.WaferMold;
import net.minecraft.nbt.NBTTagCompound;

import java.util.Random;

/**
 * 创造物品栏（JEI 也照它列）里的“标准、能直接用”的变种的 NBT（服主 11-16：创造模式直接拿到能用的，好调试）。
 * 只碰 NBT，单元测试也能用。没有 NBT 的物品语义不变（汞灯没装好、晶圆最差档……），这些变种只出现在创造物品栏，
 * 不改任何规则；激光腔、氦氖管不在这里：创造物品栏给的是空的（不公开配比）。
 */
public final class CreativeStandards {
    /** 创造芯片的序列号后缀（一眼看出不是光刻机做的） */
    public static final long SERIAL = 0;

    private CreativeStandards() {
    }

    /** 标准汞灯：普通玻璃管（金铸模）+ 氩，已注满汞，没用过 */
    public static NBTTagCompound lamp() {
        return LampNbt.tag(LampSpec.tube(LampGlass.GLASS, WaferMold.GOLD, Purity.DEFAULT_NINES).assembled(LampGas.ARGON).refilled());
    }

    /** 标准晶圆：9 个 9（够做芯片的最低档），金铸模 */
    public static NBTTagCompound wafer() {
        return WaferNbt.tag(Purity.WAFER_GRADE_NINES, WaferMold.GOLD);
    }

    /** 标准芯片：9 个 9 金铸模晶圆、溶剂与对准都正常、汞灯 / 掩模无损、铁外壳，不报废（固定取“没抽到缺陷、指标无浮动”，所以每个型号的标准芯片都一样） */
    public static NBTTagCompound chip(ChipModel model) {
        ChipQuality.Inputs in = new ChipQuality.Inputs();
        in.model = model;
        in.waferNines = Purity.WAFER_GRADE_NINES;
        in.mold = WaferMold.GOLD;
        in.resist = model.nodeUm < ResistType.POSITIVE.resolutionUm ? ResistType.IMPROVED_POSITIVE : ResistType.NEGATIVE;
        in.lampResolutionUm = model.nodeUm < ResistType.POSITIVE.resolutionUm ? ResistType.IMPROVED_POSITIVE.resolutionUm
                : in.lampResolutionUm;
        in.pack = ChipPackage.IRON;
        ChipQuality.Result result = ChipQuality.evaluate(in, new Random() {
            @Override
            public double nextDouble() {
                return 0;
            }

            @Override
            public double nextGaussian() {
                return 0;
            }
        });
        NBTTagCompound made = new NBTTagCompound();
        made.setInteger("Nines", in.waferNines);
        made.setString("Mold", in.mold.id);
        made.setString("Resist", in.resist.id);
        made.setInteger("Solvent", 1000);
        made.setInteger("Align", 1000);
        made.setInteger("Lamp", 1000);
        made.setString("Package", in.pack.id);
        made.setInteger("MaskWear", 0);
        return ChipNbt.tag(model, ChipNbt.serial(model, SERIAL), result, made);
    }

    /** 装齐了标准芯片的主板：CPU、正版掩模 ROM、内存 */
    public static NBTTagCompound board(BoardType type) {
        NBTTagCompound b = BoardNbt.empty(type);
        java.util.List<BoardNbt.Part> parts = new java.util.ArrayList<>();
        parts.add(new BoardNbt.Part(BoardNbt.cpu(type), chip(BoardNbt.cpu(type))));
        parts.add(new BoardNbt.Part(BoardNbt.romMask(type), chip(BoardNbt.romMask(type))));
        for (int i = 0; i < BoardNbt.ramCount(type); i++) {
            parts.add(new BoardNbt.Part(ChipModel.DRAM_4164, chip(ChipModel.DRAM_4164)));
        }
        NBTTagCompound done = BoardNbt.install(b, parts);
        return done == null ? b : done;
    }

    /** 标准空白 EPROM 的 NBT：标准 2764 芯片 + 石英小窗（3 个 9），没烧过 */
    public static NBTTagCompound eprom() {
        NBTTagCompound t = new NBTTagCompound();
        t.setTag(ItemEprom.TAG_CHIP, chip(ChipModel.EPROM_2764));
        t.setTag(ItemEprom.TAG_WINDOW, LampNbt.tag(LampSpec.tube(LampGlass.QUARTZ, null, 3)));
        return t;
    }
}
