package moe.qingu.youran.registry;

import moe.qingu.youran.item.ItemDopedWaferMold;
import moe.qingu.youran.item.ItemDrink;
import moe.qingu.youran.item.ItemFloppyDisk;
import moe.qingu.youran.item.ItemHome;
import moe.qingu.youran.item.ItemIceArmor;
import moe.qingu.youran.item.ItemPrimogemSword;
import moe.qingu.youran.item.ItemPeripheral;
import moe.qingu.youran.item.ItemPrimogemTools;
import moe.qingu.youran.item.ItemRedBloodGem;
import moe.qingu.youran.item.ItemSiliconWafer;
import moe.qingu.youran.item.ItemWaterBottle;
import moe.qingu.youran.item.ItemWithTooltip;
import moe.qingu.youran.item.ItemYouranLogo;
import moe.qingu.youran.api.peripheral.Peripherals;
import moe.qingu.youran.logic.purity.WaferMold;
import net.minecraft.block.Block;
import net.minecraft.inventory.EntityEquipmentSlot;
import net.minecraft.item.Item;
import net.minecraft.item.ItemBlock;
import net.minecraftforge.registries.IForgeRegistry;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * 物品（含方块与流体方块的物品形式）。注册名是存档、CraftTweaker 脚本、TAN 配置里用的，一个都不能改。
 */
public final class ModItems {
    public static Item iceHelmet;
    public static Item iceBody;
    public static Item iceLegs;
    public static Item iceBoots;
    public static Item youranLogo;
    public static Item home;
    public static Item oneYuan;
    public static Item oneYuanCoin;
    public static Item fiveYuan;
    public static Item tenYuan;
    public static Item redBloodGem;
    public static Item primogem;
    public static Item primogemPickaxe;
    public static Item primogemAxe;
    public static Item primogemSword;
    public static Item primogemShovel;
    public static Item mineralWater;
    public static Item mineralWaterBottle;
    public static Item drinkingWater;
    /** 磁粉（四氧化三铁粉，Fe3O4），2.0.0 起；矿物词典 dustMagnetite */
    public static Item magnetiteDust;
    /** 小撮磁粉，9 个合 1 个磁粉（同 IC2 的小撮铁粉）；矿物词典 dustTinyMagnetite */
    public static Item smallMagnetiteDust;
    /** 钴改性磁粉（掺钴提高矫顽力），软盘磁性涂层的原料 */
    public static Item cobaltMagneticDust;
    /** 软盘的加工链（服主 11-14）：盘片三道工序、塑料团与片、软盘 */
    public static Item roughPlatter;
    public static Item refinedPlatter;
    public static Item polishedPlatter;
    public static Item pvcLump;
    public static Item pvcSheet;
    public static Item polyethyleneLump;
    public static Item floppyDisk;
    /** 硅粉（芯片线“硅”，服主 11-15）：炼金化学硅锭打成的粉，做三氯氢硅的原料 */
    public static Item siliconDust;
    /** 硼硅酸盐渣（服主 11-15，硼的来源）：热能离心机分出电气石里的金属后剩下的渣 */
    public static Item borosilicateSlag;
    /** 掺杂硅晶圆铸模（服主 11-15，芯片线第 7 步）：金、粘土各一种 */
    public static Item dopedWaferMoldGold;
    public static Item dopedWaferMoldClay;
    /** 硅晶圆、高精硅晶圆（一个一组，NBT 记纯度与铸模材质）、报废硅晶圆（芯片线第 8、9 步） */
    public static Item siliconWafer;
    public static Item precisionSiliconWafer;
    public static Item scrapSiliconWafer;
    /** 定位模块（位置外设，服主 11-15）：装进电脑的卡槽，网站经操作者同意后能拿到签了名的位置 */
    public static Item locatorModule;
    /** 外设（服主 11-15；接口见 api.peripheral）：5.25 英寸软驱（个人电脑出厂带一个）、汉卡（普通电脑出厂带一块） */
    public static Item floppyDrive;
    public static Item hanziCard;
    /** 光刻（服主 11-15 / 11-16）：负胶链的环化橡胶、叠氮化钠粉；汞灯的玻璃管两档、钨棒、掺钍阴极棒、汞灯（配方见 recipe.LithoRecipes）；掩模、芯片设计图、芯片 */
    public static Item cyclizedRubber;
    public static Item sodiumAzideDust;
    public static moe.qingu.youran.item.ItemTube glassTube;
    public static moe.qingu.youran.item.ItemTube quartzTube;
    public static Item tungstenRod;
    public static Item thoriatedCathodeRod;
    public static Item mercuryLamp;
    public static moe.qingu.youran.item.ItemPhotomask photomask;
    public static moe.qingu.youran.item.ItemChipDesign chipDesign;
    public static moe.qingu.youran.item.ItemChip chip;
    public static Item alignmentTable;
    /** 钼（服主 11-16）：铜矿的副产物，小撮钼粉做汞灯，9 小撮 = 1 钼粉，钼粉烧成炼金化学的钼锭 */
    public static Item smallMolybdenumDust;
    public static Item molybdenumDust;
    public static Item mercuryLightSource;
    /** DUV 一档（服主 11-16）：ArF 激光腔、准分子激光光源（方块的物品）、氦氖激光管 */
    public static moe.qingu.youran.item.ItemLaserCavity arfLaserCavity;
    public static Item excimerLightSource;
    public static moe.qingu.youran.item.ItemHeNeTube heneLaserTube;
    /** 主板线（服主 11-16）：酚醛基板、三氯化铁粉、线路板（各道工序按元数据）、线路底片、冲孔模具、EPROM、紫外擦除器 */
    public static Item phenolicSubstrate;
    public static Item ferricChlorideDust;
    public static moe.qingu.youran.item.ItemPcb pcb;
    public static moe.qingu.youran.item.ItemPcbFilm pcbFilm;
    public static Item punchMold;
    public static moe.qingu.youran.item.ItemEprom eprom;
    public static Item uvEraser;
    /** 主板（服主 11-16）：装了芯片的普通电脑 / 个人电脑主板（元数据 = 种类） */
    public static moe.qingu.youran.item.ItemMotherboard motherboard;
    /** EPROM 烧录器（外设，服主 11-16） */
    public static Item epromProgrammer;
    /** 软盘套（服主 11-16，取代原来的“6 聚氯乙烯片”）：未冲孔 0、已冲孔 1、冲坏 2 */
    public static Item floppyJacket;

    /** 全部物品，按注册顺序（客户端登记模型用） */
    private static final List<Item> ALL = new ArrayList<>();

    private ModItems() {
    }

    /** 这种材质的掺杂硅晶圆铸模 */
    public static Item dopedWaferMold(WaferMold mold) {
        return mold == WaferMold.GOLD ? dopedWaferMoldGold : dopedWaferMoldClay;
    }

    public static List<Item> all() {
        return Collections.unmodifiableList(ALL);
    }

    static void register(IForgeRegistry<Item> registry) {
        // 顺序同 1.3.4（只影响新世界里的数字 ID 与创造模式物品栏里的排列）
        iceHelmet = add(registry, ItemIceArmor.piece(EntityEquipmentSlot.HEAD, "icekuijiahelmet"));
        iceBody = add(registry, ItemIceArmor.piece(EntityEquipmentSlot.CHEST, "icekuijiabody"));
        iceLegs = add(registry, ItemIceArmor.piece(EntityEquipmentSlot.LEGS, "icekuijialegs"));
        iceBoots = add(registry, ItemIceArmor.piece(EntityEquipmentSlot.FEET, "icekuijiaboots"));
        youranLogo = add(registry, new ItemYouranLogo());
        add(registry, itemBlock(ModBlocks.youranLogo));
        add(registry, itemBlock(ModBlocks.mainCityPortal));
        home = add(registry, new ItemHome());
        add(registry, itemBlock(ModBlocks.hardIce));
        oneYuan = add(registry, new ItemWithTooltip("oneyuan", 50, "一元悠然币"));
        oneYuanCoin = add(registry, new ItemWithTooltip("oneyuancoin", 50, "一元悠然币（硬币）"));
        fiveYuan = add(registry, new ItemWithTooltip("fiveyuan", 50, "五元悠然币"));
        tenYuan = add(registry, new ItemWithTooltip("tenyuan", 50, "十元悠然币"));
        redBloodGem = add(registry, new ItemRedBloodGem());
        add(registry, itemBlock(ModBlocks.primogemOre));
        add(registry, itemBlock(ModBlocks.sphaleriteOre));
        primogem = add(registry, new ItemWithTooltip("primogem", 64, "据说这是很久以前由流星带来的东西，经过漫长的地质演变而深埋地底"));
        primogemPickaxe = add(registry, ItemPrimogemTools.pickaxe());
        primogemAxe = add(registry, ItemPrimogemTools.axe());
        primogemSword = add(registry, new ItemPrimogemSword());
        primogemShovel = add(registry, ItemPrimogemTools.shovel());
        mineralWater = add(registry, new ItemDrink("mineral_water", s -> s.mineralWaterHunger, s -> s.mineralWaterSaturation, false));
        mineralWaterBottle = add(registry, new ItemWaterBottle());
        drinkingWater = add(registry, new ItemDrink("drinkingwater", s -> s.drinkingWaterHunger, s -> s.drinkingWaterSaturation, true));
        add(registry, itemBlock(ModBlocks.mineralWater));
        add(registry, itemBlock(ModBlocks.hydrargyrum));
        add(registry, itemBlock(ModBlocks.gallium));
        add(registry, itemBlock(ModBlocks.chlorine));
        // 2.0.0 新加的放在最后，不改已有物品在新世界里的数字 ID
        magnetiteDust = add(registry, new ItemWithTooltip("magnetite_dust", 64, "四氧化三铁（Fe3O4），有磁性"));
        smallMagnetiteDust = add(registry, new ItemWithTooltip("small_magnetite_dust", 64, "四氧化三铁（Fe3O4），有磁性"));
        cobaltMagneticDust = add(registry, new ItemWithTooltip("cobalt_magnetic_dust", 64, "掺了钴的磁粉，矫顽力更高，用来做软盘的磁性涂层"));
        roughPlatter = add(registry, new ItemWithTooltip("rough_platter", 64, "涂了磁性涂层的片基，还要烘干、压光"));
        refinedPlatter = add(registry, new ItemWithTooltip("refined_platter", 64, "烘过的盘片，还要压光"));
        polishedPlatter = add(registry, new ItemWithTooltip("polished_platter", 64, "压光的盘片，装进软套就是软盘"));
        pvcLump = add(registry, new ItemWithTooltip("pvc_lump", 64, "聚氯乙烯（PVC）"));
        pvcSheet = add(registry, new ItemWithTooltip("pvc_sheet", 64, "聚氯乙烯（PVC）薄片，可以做外设的塑料外壳"));
        polyethyleneLump = add(registry, new ItemWithTooltip("polyethylene_lump", 64, "聚乙烯（PE）"));
        floppyDisk = add(registry, new ItemFloppyDisk());
        siliconDust = add(registry, new ItemWithTooltip("silicon_dust", 64, "粗硅（Si）打成的粉，加盐酸做成三氯氢硅"));
        borosilicateSlag = add(registry, new ItemWithTooltip("borosilicate_slag", 64, "电气石分出金属后剩下的渣，含硼和硅"));
        dopedWaferMoldGold = add(registry, new ItemDopedWaferMold(WaferMold.GOLD));
        dopedWaferMoldClay = add(registry, new ItemDopedWaferMold(WaferMold.CLAY));
        siliconWafer = add(registry, new ItemSiliconWafer("silicon_wafer", "浇注出来的硅晶圆，要在洗矿机里洗过才能用"));
        precisionSiliconWafer = add(registry, new ItemSiliconWafer("precision_silicon_wafer", "洗干净的硅晶圆，可以拿去做芯片"));
        scrapSiliconWafer = add(registry, new ItemWithTooltip("scrap_silicon_wafer", 64, "洗坏了的硅晶圆，放进熔炉或电弧炉烧回硅锭，从头再来"));
        // 定位模块也是外设（服主 11-15）：没有 I/O 端口，装上就有“位置”能力
        locatorModule = add(registry, new ItemPeripheral(moe.qingu.youran.api.network.location.LocatorModule.NAME,
                Peripherals.KIND_LOCATOR, Peripherals.CAP_LOCATION).withCasings());
        floppyDrive = add(registry, new ItemPeripheral("floppy_drive", Peripherals.KIND_FLOPPY).withCasings());
        hanziCard = add(registry, new moe.qingu.youran.item.ItemHanziCard("hanzi_card", Peripherals.KIND_HANZI));
        Peripherals.register(floppyDrive);
        Peripherals.register(hanziCard);
        Peripherals.register(locatorModule);
        cyclizedRubber = add(registry, new moe.qingu.youran.item.ItemLangTooltip("cyclized_rubber", 64));
        sodiumAzideDust = add(registry, new moe.qingu.youran.item.ItemLangTooltip("sodium_azide_dust", 64));
        glassTube = (moe.qingu.youran.item.ItemTube) add(registry, new moe.qingu.youran.item.ItemTube("glass_tube",
                moe.qingu.youran.logic.litho.LampGlass.GLASS));
        quartzTube = (moe.qingu.youran.item.ItemTube) add(registry, new moe.qingu.youran.item.ItemTube("quartz_tube",
                moe.qingu.youran.logic.litho.LampGlass.QUARTZ));
        tungstenRod = add(registry, new moe.qingu.youran.item.ItemLangTooltip("tungsten_rod", 64));
        thoriatedCathodeRod = add(registry, new moe.qingu.youran.item.ItemLangTooltip("thoriated_cathode_rod", 64));
        mercuryLamp = add(registry, new moe.qingu.youran.item.ItemLampPart("mercury_lamp", 1, true));
        photomask = (moe.qingu.youran.item.ItemPhotomask) add(registry, new moe.qingu.youran.item.ItemPhotomask());
        chipDesign = (moe.qingu.youran.item.ItemChipDesign) add(registry, new moe.qingu.youran.item.ItemChipDesign());
        chip = (moe.qingu.youran.item.ItemChip) add(registry, new moe.qingu.youran.item.ItemChip());
        smallMolybdenumDust = add(registry, new moe.qingu.youran.item.ItemLangTooltip("small_molybdenum_dust", 64));
        molybdenumDust = add(registry, new moe.qingu.youran.item.ItemLangTooltip("molybdenum_dust", 64));
        // 物品注册时就登记矿物词典：炼金化学在它的后初始化里按 dustMolybdenum 自动生成溶解机配方（1 钼粉 → 16 钼）
        net.minecraftforge.oredict.OreDictionary.registerOre("dustTinyMolybdenum", smallMolybdenumDust);
        net.minecraftforge.oredict.OreDictionary.registerOre("dustMolybdenum", molybdenumDust);
        alignmentTable = add(registry, itemBlock(ModBlocks.alignmentTable));
        mercuryLightSource = add(registry, new moe.qingu.youran.litho.ItemBlockLightSource(ModBlocks.mercuryLightSource));
        arfLaserCavity = (moe.qingu.youran.item.ItemLaserCavity) add(registry, new moe.qingu.youran.item.ItemLaserCavity("arf_laser_cavity"));
        excimerLightSource = add(registry, new moe.qingu.youran.litho.ItemBlockLightSource(ModBlocks.excimerLightSource));
        heneLaserTube = (moe.qingu.youran.item.ItemHeNeTube) add(registry, new moe.qingu.youran.item.ItemHeNeTube("hene_laser_tube"));
        phenolicSubstrate = add(registry, new moe.qingu.youran.item.ItemLangTooltip("phenolic_substrate", 64));
        ferricChlorideDust = add(registry, new moe.qingu.youran.item.ItemLangTooltip("ferric_chloride_dust", 64));
        net.minecraftforge.oredict.OreDictionary.registerOre("dustFerricChloride", ferricChlorideDust);
        pcb = (moe.qingu.youran.item.ItemPcb) add(registry, new moe.qingu.youran.item.ItemPcb());
        pcbFilm = (moe.qingu.youran.item.ItemPcbFilm) add(registry, new moe.qingu.youran.item.ItemPcbFilm());
        punchMold = add(registry, new moe.qingu.youran.item.ItemLangTooltip("punch_mold", 1));
        eprom = (moe.qingu.youran.item.ItemEprom) add(registry, new moe.qingu.youran.item.ItemEprom());
        uvEraser = add(registry, itemBlock(ModBlocks.uvEraser));
        motherboard = (moe.qingu.youran.item.ItemMotherboard) add(registry, new moe.qingu.youran.item.ItemMotherboard());
        epromProgrammer = add(registry, new ItemPeripheral("eprom_programmer", Peripherals.KIND_PROGRAMMER).withCasings());
        Peripherals.register(epromProgrammer);
        floppyJacket = add(registry, new moe.qingu.youran.item.ItemFloppyJacket());
    }

    private static Item itemBlock(Block block) {
        return new ItemBlock(block).setRegistryName(block.getRegistryName());
    }

    private static Item add(IForgeRegistry<Item> registry, Item item) {
        registry.register(item);
        ALL.add(item);
        return item;
    }
}
