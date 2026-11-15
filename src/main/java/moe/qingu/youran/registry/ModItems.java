package moe.qingu.youran.registry;

import moe.qingu.youran.item.ItemDrink;
import moe.qingu.youran.item.ItemFloppyDisk;
import moe.qingu.youran.item.ItemHome;
import moe.qingu.youran.item.ItemIceArmor;
import moe.qingu.youran.item.ItemPrimogemSword;
import moe.qingu.youran.item.ItemPrimogemTools;
import moe.qingu.youran.item.ItemRedBloodGem;
import moe.qingu.youran.item.ItemWaterBottle;
import moe.qingu.youran.item.ItemWithTooltip;
import moe.qingu.youran.item.ItemYouranLogo;
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

    /** 全部物品，按注册顺序（客户端登记模型用） */
    private static final List<Item> ALL = new ArrayList<>();

    private ModItems() {
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
        pvcSheet = add(registry, new ItemWithTooltip("pvc_sheet", 64, "聚氯乙烯（PVC）薄片，可以做软盘的软套"));
        polyethyleneLump = add(registry, new ItemWithTooltip("polyethylene_lump", 64, "聚乙烯（PE）"));
        floppyDisk = add(registry, new ItemFloppyDisk());
        siliconDust = add(registry, new ItemWithTooltip("silicon_dust", 64, "粗硅（Si）打成的粉，加盐酸做成三氯氢硅"));
        borosilicateSlag = add(registry, new ItemWithTooltip("borosilicate_slag", 64, "电气石分出金属后剩下的渣，含硼和硅"));
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
