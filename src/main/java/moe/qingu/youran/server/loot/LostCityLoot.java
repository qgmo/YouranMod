package moe.qingu.youran.server.loot;

import com.google.gson.JsonObject;
import com.google.gson.JsonSerializationContext;
import moe.qingu.youran.YouranMod;
import moe.qingu.youran.config.LostCityLootConfig;
import moe.qingu.youran.logic.source.ChipLoot;
import moe.qingu.youran.registry.ModItems;
import net.minecraft.item.ItemStack;
import net.minecraft.world.World;
import net.minecraft.world.storage.loot.LootContext;
import net.minecraft.world.storage.loot.LootEntry;
import net.minecraft.world.storage.loot.LootPool;
import net.minecraft.world.storage.loot.RandomValueRange;
import net.minecraft.world.storage.loot.conditions.LootCondition;
import net.minecraftforge.event.LootTableLoadEvent;
import net.minecraftforge.fml.common.eventhandler.SubscribeEvent;

import java.util.Collection;
import java.util.Random;

/**
 * 失落的城市建筑箱子里刷掩模、芯片设计图（服主 11-16）。Lost Cities 1.12 没有箱子的事件：建筑里的箱子放下时挂一张战利品表
 * （{@code citydata/conditions.json} 的 {@code chestloot}），第一次打开才生成。所以在 Forge 读战利品表时（{@link LootTableLoadEvent}）
 * 往这几张表里加一个池：
 * <ul>
 *     <li>建筑专用的 {@code lostcities:chests/lostcitychest}（4 楼以上和地下室）：直接加；</li>
 *     <li>少量建筑箱子借用的原版表（下界要塞、废弃矿井、村庄铁匠、沙漠神殿、丛林神庙、地牢）：加，但带“箱子在失落的城市的维度里”的条件，
 *     主世界的原版结构不刷；</li>
 *     <li>铁路地牢的 {@code raildungeonchest} 不加（不在建筑里）。</li>
 * </ul>
 * 池里一项，抽法见 {@link ChipLoot#roll}（概率读配置 {@link LostCityLootConfig}）。只对装了本版以后才生成的区块里的箱子有效：SpongeForge 上箱子在区块生成时就填好了战利品，老区块的箱子不会补刷。
 * 不改 Lost Cities 的配置。
 */
public final class LostCityLoot {
    /** 池的名字（同一张表里不能重名） */
    public static final String POOL = "youranmod_chip_sources";
    private static final java.util.Set<String> LOGGED = java.util.Collections.newSetFromMap(new java.util.concurrent.ConcurrentHashMap<>());

    private LostCityLoot() {
    }

    @SubscribeEvent
    public static void onLootTableLoad(LootTableLoadEvent event) {
        if (!LostCityLootConfig.enabled || event.getTable() == null) {
            return;
        }
        ChipLoot.Target target = ChipLoot.target(event.getName().toString(), LostCityLootConfig.buildingTables, LostCityLootConfig.vanillaTables);
        if (target == ChipLoot.Target.NONE) {
            return;
        }
        LootCondition[] conditions = target == ChipLoot.Target.LOST_CITY_ONLY
                ? new LootCondition[]{new InDimensions(LostCityLootConfig.dimensions.clone())} : new LootCondition[0];
        try {
            event.getTable().addPool(new LootPool(new LootEntry[]{new ChipEntry()}, conditions, new RandomValueRange(1), new RandomValueRange(0), POOL));
            // 每个世界各读一遍战利品表（Sponge 下每个维度一份），日志每张表只记第一次
            if (LOGGED.add(event.getName().toString())) {
                YouranMod.LOG.info("失落的城市战利品：{} 加了掩模、芯片设计图（{}）", event.getName(), target == ChipLoot.Target.LOST_CITY_ONLY ? "只在维度 "
                        + java.util.Arrays.toString(LostCityLootConfig.dimensions) + " 里" : "哪个维度都刷");
            }
        } catch (RuntimeException e) {
            // 同名的池已经有了（别的模组或数据包加过同名的）、表已冻结：不加，记一条
            YouranMod.LOG.warn("失落的城市战利品：没能往 {} 里加池：{}", event.getName(), e.toString());
        }
    }

    /** 池里唯一的一项：按 {@link ChipLoot#roll} 出 0 或 1 件掩模 / 设计图 */
    static final class ChipEntry extends LootEntry {
        ChipEntry() {
            super(1, 0, new LootCondition[0], POOL);
        }

        @Override
        public void addLoot(Collection<ItemStack> stacks, Random rand, LootContext context) {
            ChipLoot.Drop d = ChipLoot.roll(rand, LostCityLootConfig.chance, LostCityLootConfig.maskShare);
            if (d != null) {
                stacks.add(d.kind == ChipLoot.Kind.MASK ? ModItems.photomask.make(d.model) : ModItems.chipDesign.make(d.model));
            }
        }

        @Override
        protected void serialize(JsonObject json, JsonSerializationContext context) {
            // 只在代码里加，不写进 JSON
        }
    }

    /** 条件：箱子所在的世界是这些维度之一（LootContext 的世界就是箱子所在的世界） */
    static final class InDimensions implements LootCondition {
        private final int[] dims;

        InDimensions(int[] dims) {
            this.dims = dims;
        }

        @Override
        public boolean testCondition(Random rand, LootContext context) {
            World w = context.getWorld();
            return w != null && ChipLoot.inDimensions(w.provider.getDimension(), dims);
        }
    }
}
