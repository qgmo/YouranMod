package moe.qingu.youran;

import moe.qingu.youran.net.NicChannel;
import moe.qingu.youran.net.YouranNetwork;
import moe.qingu.youran.recipe.CompatRecipes;
import moe.qingu.youran.config.ServerConfig;
import moe.qingu.youran.registry.ModBlocks;
import moe.qingu.youran.registry.ModFluids;
import moe.qingu.youran.registry.ModItems;
import moe.qingu.youran.registry.YouranTab;
import moe.qingu.youran.server.ServerSide;
import moe.qingu.youran.world.MainCity;
import moe.qingu.youran.worldgen.MineralWaterLakeGenerator;
import moe.qingu.youran.worldgen.OreGenerator;
import net.minecraft.item.ItemStack;
import net.minecraftforge.fluids.FluidRegistry;
import net.minecraftforge.fml.common.Mod;
import net.minecraftforge.fml.common.event.FMLInitializationEvent;
import net.minecraftforge.fml.common.event.FMLPostInitializationEvent;
import net.minecraftforge.fml.common.event.FMLPreInitializationEvent;
import net.minecraftforge.fml.common.event.FMLServerAboutToStartEvent;
import net.minecraftforge.fml.common.event.FMLServerStartingEvent;
import net.minecraftforge.fml.common.event.FMLServerStoppedEvent;
import net.minecraftforge.fml.common.event.FMLServerStoppingEvent;
import net.minecraftforge.fml.common.registry.GameRegistry;
import net.minecraftforge.oredict.OreDictionary;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

/**
 * 悠然服务器模组。服务器与客户端整合包都装。
 * <p>
 * 两端都要的：方块、物品、流体、主城维度（客户端也要知道维度类型）、创造模式物品栏、模型。
 * 只在服务端（独立服务端或单人游戏的内置服务端）的：配置、指令、tick 里的处理，见 {@code server.ServerSide}。
 * 世界生成器两端都登记，但只有服务端生成区块时才会被调用。
 */
@Mod(modid = YouranMod.MODID, useMetadata = true)
public final class YouranMod {
    public static final String MODID = "youranmod";
    public static final Logger LOG = LogManager.getLogger("YouranMod");

    @Mod.Instance(MODID)
    public static YouranMod instance;

    static {
        // 流体的桶用 Forge 的通用桶（同 1.3.4）；必须在预初始化之前打开
        FluidRegistry.enableUniversalBucket();
    }

    @Mod.EventHandler
    public void preInit(FMLPreInitializationEvent event) {
        // 服务端配置只在独立服务端读（见 ServerConfig）
        if (event.getSide().isServer()) {
            ServerConfig.loadOnDedicatedServer();
        }
        // 顺序同 1.3.4：流体与创造模式物品栏在方块、物品的注册事件之前
        // 两端都登记网络频道（同步配置；游戏内网络的网卡）
        YouranNetwork.register();
        NicChannel.register();
        ModFluids.register();
        YouranTab.create();
        MainCity.register();
        // 1.3.4 把矿石与矿泉水湖放在同一个生成器里；拆开以后用重生成模组只补矿时不会连湖一起补
        GameRegistry.registerWorldGenerator(new OreGenerator(), 5);
        GameRegistry.registerWorldGenerator(new MineralWaterLakeGenerator(), 5);
        // 失落的城市建筑箱子里刷掩模、芯片设计图（往战利品表里加池；只有服务端读战利品表）
        net.minecraftforge.common.MinecraftForge.EVENT_BUS.register(moe.qingu.youran.server.loot.LostCityLoot.class);
    }

    @Mod.EventHandler
    public void init(FMLInitializationEvent event) {
        GameRegistry.addSmelting(new ItemStack(ModBlocks.primogemOre), new ItemStack(ModItems.primogem), 1F);
        OreDictionary.registerOre("primogem", new ItemStack(ModItems.primogem));
        OreDictionary.registerOre("primogem_ore", new ItemStack(ModBlocks.primogemOre));
        OreDictionary.registerOre("dustMagnetite", new ItemStack(ModItems.magnetiteDust));
        OreDictionary.registerOre("dustTinyMagnetite", new ItemStack(ModItems.smallMagnetiteDust));
        // 光刻机：界面；装了沉浸工程时登记多方块（锤子敲对准台成形，沉浸原油的投影仪也会列出它）
        net.minecraftforge.fml.common.network.NetworkRegistry.INSTANCE.registerGuiHandler(this, new moe.qingu.youran.litho.LithoGuiHandler());
        if (net.minecraftforge.fml.common.Loader.isModLoaded("immersiveengineering")) {
            moe.qingu.youran.compat.immersiveengineering.LithoMultiblock.register();
        }
        // Carry On：光刻机的方块不能搬（用它的 IMC 接口，不写整合包配置；服主 11-16）
        moe.qingu.youran.compat.carryon.CarryOnCompat.sendBlacklist();
        if (event.getSide().isClient()) {
            moe.qingu.youran.client.network.ClientNetwork.init();
        }
    }

    /** 后初始化：加进别的模组机器的配方（软依赖，见 {@code recipe.CompatRecipes}） */
    @Mod.EventHandler
    public void postInit(FMLPostInitializationEvent event) {
        CompatRecipes.postInit();
    }

    /** 全部模组载入完成：沉浸工程铜矿石粉碎配方加副产物（它的矿石配方要到后初始化才全）；工程师手册（沉浸工程在它的后初始化里才建手册）里加光刻机一页（只在客户端） */
    @Mod.EventHandler
    public void loadComplete(net.minecraftforge.fml.common.event.FMLLoadCompleteEvent event) {
        moe.qingu.youran.recipe.MolybdenumRecipes.loadComplete(net.minecraftforge.fml.common.Loader.isModLoaded("immersiveengineering"));
        moe.qingu.youran.recipe.FluoriteRecipes.loadComplete(net.minecraftforge.fml.common.Loader.isModLoaded(moe.qingu.youran.compat.alchemistry.AlchemistryCompat.MODID));
        if (event.getSide().isClient() && net.minecraftforge.fml.common.Loader.isModLoaded("immersiveengineering")) {
            moe.qingu.youran.compat.immersiveengineering.LithoManual.register();
        }
    }

    /** 服务端（独立服务端或进单人世界时的内置服务端）启动前：读配置、登记 tick 处理 */
    @Mod.EventHandler
    public void serverAboutToStart(FMLServerAboutToStartEvent event) {
        ServerSide.start();
    }

    @Mod.EventHandler
    public void serverStarting(FMLServerStartingEvent event) {
        ServerSide.registerCommands(event);
    }

    /** 关服开始时（玩家还连着）：停游戏内网络 */
    @Mod.EventHandler
    public void serverStopping(FMLServerStoppingEvent event) {
        ServerSide.stopping();
    }

    @Mod.EventHandler
    public void serverStopped(FMLServerStoppedEvent event) {
        ServerSide.stop();
    }
}
