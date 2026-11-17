package moe.qingu.youran.compat.twilightforest;

/**
 * 拆解台写死的名单（服主 11-17 让查整合包里会出问题的拆解；审查记录在服主的文档里）。格式同 {@code config.UncraftConfig}。
 * 能按机制挡住的（NBT 对不上、原样还回的工具、拆出它自己、候选材料挑贵的）不进名单，见 {@code logic.uncraft.UncraftRules}。
 */
public final class UncraftBuiltins {
    private UncraftBuiltins() {
    }

    /** 本服的数据与障碍物品：不让拆，也不让别的配方把它们拆出来 */
    static final String[] OWN_ITEMS = {
            // 芯片线：芯片（品质在 NBT）、EPROM（带芯片数据）、主板与汉卡（Board 数据）、光板（打孔有概率打坏）、底片、掩模（只能买或找）
            "youranmod:chip", "youranmod:eprom", "youranmod:motherboard", "youranmod:hanzi_card", "youranmod:pcb", "youranmod:pcb_film",
            "youranmod:photomask",
            // 晶圆（纯度、铸模在 NBT）与掺杂铸模
            "youranmod:silicon_wafer", "youranmod:precision_silicon_wafer", "youranmod:scrap_silicon_wafer",
            "youranmod:doped_wafer_mold_gold", "youranmod:doped_wafer_mold_clay",
            // 光源：玻璃管（铸模、纯度）、汞灯（汞量、用时）、ArF 激光腔与氦氖激光管（充气），以及装好的光源方块
            "youranmod:glass_tube", "youranmod:quartz_tube", "youranmod:mercury_lamp", "youranmod:arf_laser_cavity",
            "youranmod:hene_laser_tube", "youranmod:mercury_light_source", "youranmod:excimer_light_source",
            // 软盘（盘号）与软盘套（手工冲孔有概率冲坏）
            "youranmod:floppy_disk", "youranmod:floppy_jacket",
            // 悠然币
            "youranmod:oneyuan", "youranmod:oneyuancoin", "youranmod:fiveyuan", "youranmod:tenyuan",
    };

    /** 另外不让拆的物品（别的模组的；本服的见 {@link #OWN_ITEMS}） */
    static final String[] BLOCKED_INPUTS = {
            // 电脑（主板数据、芯片品质）：拆了拿回一块没有数据的主板
            "scp:computer", "scp:personal_computer", "cfm:computer",
            // 本服的外设：定位模块里装着 EPROM（也由“不让拆出来”挡住）
            "youranmod:locator_module",
            // IC2 升级：弹出、抽入、流体弹出、流体抽入、红石反相有“4 锡板 + 1 活塞（拉杆、树脂提取器与电动机）→ 1 个”和
            // “4 致密锡板 + 1 活塞 → 9 个”两种做法，按 9 个做、一个个拆，白得 8 个活塞；超频器三条（1 万 / 3 万 / 6 万冷却单元 → 2 / 6 / 12 个）
            // 导线、电路与冷却单元的比例不同，同样能按非配方比例换
            "ic2:upgrade@0", "ic2:upgrade@3", "ic2:upgrade@4", "ic2:upgrade@6", "ic2:upgrade@8", "ic2:upgrade@9",
            // IC2 遥控引爆器：用频率发射器做、按 3 TNT + 2 荧石粉那条拆
            "ic2:remote",
            // IC2 蒸汽再加压器：用铁储罐做、按旧储罐（ic2:te@81，没有合成配方）那条拆，凭空拆出两个旧储罐
            "ic2:te@87",
            // RFTools 能量单元：直接做的配方比“简易能量单元升级”少一个简易能量单元，按直接做、按升级拆，白得 2 钻石 2 石英 4 红石
            "rftools:powercell",
            // 整合包脚本加了更贵的新配方、原配方还在：按原配方做、按新配方拆
            // 铁轨：6 铁 → 16 个；脚本 2 铁 + 木轨 + 4 木棍 → 2 个。16 个拆回 16 铁
            "minecraft:rail",
            // 铁梯子：Quark 7 铁 → 16 个、暮色 4 粒 + 2 铁栏杆 → 3 个；脚本 6 铁（或 2 铁）+ 石梯子
            "quark:iron_ladder", "twilightforest:iron_ladder",
            // 匠魂石梯子：7 石棍 → 3 个；脚本 2 石棍 + 木梯子 → 1 个，拆出白来的木梯子
            "tconstruct:stone_ladder",
            // 坚韧冰史莱姆：TAN 8 冰块 + 1 粘液球 → 3 个；脚本 1 粘液块 + 冰 → 4 个，拆回 9 粘液球
            "toughasnails:jelled_slime",
            // 家具：电视遥控器（原配方用红石块，脚本用 1 万 EU 的充电电池或泰勒克尔电池）、电网（脚本三种电池随便）、
            // 沙发（原配方 6 羊毛，脚本 1 羊毛 + SCP 扶手椅：拆出没有合成配方的扶手椅）、马桶（拆回多 1 个石英块）
            "cfm:tv_remote", "cfm:electric_fence", "cfm:couch", "cfm:toilet",
            // 别的模组加的第二条配方：安山岩（Quark 玄武岩 + 下界石英 → 1 个，拆出白来的下界石英）、
            // 岩浆块（TAN 岩浆碎片 → 拆成 4 岩浆膏）、骨粉（SCP 4 灰烬 → 1 骨粉，拆出灰烬）
            "minecraft:stone@5", "minecraft:magma", "minecraft:dye@15",
            // 铁锭：脚本 SCP 强化铁 → 17 铁锭，拆 17 铁锭得到没有合成配方的强化铁；IC2 拆 8 铁锭得到机器外壳
            "minecraft:iron_ingot",
            // 刷怪蛋（D 级人员）：皮甲做的和铁甲做的是同一种蛋，拆出铁甲
            "minecraft:spawn_egg",
            // 床：白床 + 1 份染料 → 彩色床，按“3 羊毛 + 3 木板”拆回 3 块彩色羊毛（每块本要 1 份染料）
            "minecraft:bed",
            // 鞘翅：Quark 染色（拆出白来的染料）与复制（拆出白来的末影龙鳞）
            "minecraft:elytra",
            // 工业线缆线圈：长度在 NBT 里，拆一卷任意长的线圈都按满格拆
            "industrialwires:ic2_wire_coil",
            // IC2 植物球：8 个任意植物（草、种子、树叶、树苗、小麦、甘蔗、仙人掌）各一条配方，用 8 株草做、拆出 8 个小麦 / 甘蔗 / 树苗
            "ic2:crafting@20",
    };

    /** 另外不让拆出来的物品（别的模组的；本服的见 {@link #OWN_ITEMS}） */
    static final String[] NEVER_YIELD = {
            // 原版“地图扩大”配方的产物写成空地图，按它拆空地图会拆出 0 号已绘制地图
            "minecraft:filled_map",
    };

    /**
     * 一格有几种候选时优先给的“便宜的替代品”（其余情况给第一种候选，见 {@code UncraftRules.chooseVariant}）：
     * 原版钻石装备、钻石块等的配方收工业钻石，第一种候选却是钻石；带蓝、黑、白染料的配方收 Quark 的根染料，第一种却是青金石、墨囊、骨粉。
     * 不这样的话，用工业钻石做、拆出真钻石，用根染料做、拆出青金石。
     */
    static final String[] CHEAPER_SUBSTITUTES = {"ic2:crafting@19", "quark:root_dye@0", "quark:root_dye@1", "quark:root_dye@2"};

    /** 带了就不让拆的 NBT 键：主板与芯片数据（Board，主板、汉卡、电脑）、EPROM 里的芯片（Chip）、软盘（Floppy） */
    static final String[] DATA_KEYS = {"Board", "Chip", "Floppy"};
}
