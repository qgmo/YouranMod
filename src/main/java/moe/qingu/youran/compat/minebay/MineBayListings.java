package moe.qingu.youran.compat.minebay;

import com.mrcrayfish.furniture.web.minebay.MineBayApi;
import com.mrcrayfish.furniture.web.minebay.MineBayDelivery;
import com.mrcrayfish.furniture.web.minebay.MineBayProduct;
import com.mrcrayfish.furniture.web.minebay.Stock;
import moe.qingu.youran.YouranMod;
import moe.qingu.youran.config.MineBayConfig;
import moe.qingu.youran.item.ItemFloppyDisk;
import moe.qingu.youran.item.ToolDisk;
import moe.qingu.youran.logic.source.MineBayOffers;
import moe.qingu.youran.registry.ModItems;
import net.minecraft.entity.player.EntityPlayerMP;
import net.minecraft.item.Item;
import net.minecraft.item.ItemStack;
import net.minecraft.util.ResourceLocation;
import net.minecraftforge.fml.common.registry.ForgeRegistries;

import java.util.ArrayList;
import java.util.List;

/**
 * 在家具模组的 MineBay 网站上架（服主 11-16）：掩模、芯片设计图、线路底片、正版 ROM 镜像软盘、工具盘。清单与价格见
 * {@link MineBayOffers}（数值在配置 {@link MineBayConfig}）。
 * <p>
 * 只有这个类引用家具的类（{@code com.mrcrayfish.furniture.web.minebay}），只在装了家具模组时由 {@code YouranMod.postInit} 调用；
 * 编译用的是 {@code src/minebayApi} 里的替身（家具依赖 YouranMod，不能反过来依赖它的 jar）。
 * 软盘是发货时现做的（{@link ItemFloppyDisk#unnumberedToolDisk}，到了玩家手里才分配盘号），其余是固定物品。
 */
public final class MineBayListings {
    /** 家具模组的模组 ID */
    public static final String CFM = "cfm";

    private static boolean registered;

    private MineBayListings() {
    }

    /** 上架（一个进程只做一次）；接口对不上（家具版本不配套）时记一条错误，不影响别的 */
    public static synchronized void register() {
        if (registered || !MineBayConfig.enabled) {
            return;
        }
        registered = true;
        try {
            ItemStack currency = currency(MineBayConfig.currency);
            if (currency.isEmpty()) {
                YouranMod.LOG.error("MineBay：货币 {} 不是物品，YouranMod 的东西都不上架", MineBayConfig.currency);
                return;
            }
            MineBayApi.registerCategory(MineBayOffers.CAT_CHIPMAKING, MineBayOffers.CAT_CHIPMAKING_NAME);
            MineBayApi.registerCategory(MineBayOffers.CAT_SOFTWARE, MineBayOffers.CAT_SOFTWARE_NAME);
            int n = 0;
            for (MineBayOffers.Offer o : MineBayOffers.plan(MineBayConfig.prices())) {
                try {
                    MineBayApi.registerProduct(product(o, currency));
                    n++;
                } catch (RuntimeException e) {
                    YouranMod.LOG.error("MineBay：{} 没能上架：{}", o.id, e.toString());
                }
            }
            YouranMod.LOG.info("MineBay：上架了 {} 件（掩模、芯片设计图、线路底片、ROM 镜像盘、工具盘）", n);
        } catch (RuntimeException | LinkageError e) {
            YouranMod.LOG.error("MineBay：上架接口对不上（家具模组版本不配套？），YouranMod 的东西都不上架", e);
        }
    }

    static MineBayProduct product(MineBayOffers.Offer o, ItemStack currency) {
        MineBayProduct.Builder b = MineBayProduct.builder(o.id).category(o.category).price(currency, o.price).descriptionKey(o.descKey);
        if (o.nameKey != null) {
            b.nameKey(o.nameKey);
        }
        switch (o.kind) {
            case MASK:
                b.item(ModItems.photomask.make(o.model));
                break;
            case DESIGN:
                b.item(ModItems.chipDesign.make(o.model));
                break;
            case FILM:
                b.item(new ItemStack(ModItems.pcbFilm, 1, o.board.id));
                break;
            default:
                b.icon(new ItemStack(ModItems.floppyDisk)).delivery(new Floppies(o.disk));
                break;
        }
        if (o.limited()) {
            b.stock(o.perMonth, o.monthlyReset ? Stock.Rule.MONTHLY : Stock.Rule.DAILY);
        }
        return b.build();
    }

    /** 货币：注册名，可带 {@code @数据值}；认不出时为空 */
    static ItemStack currency(String spec) {
        String s = spec == null ? "" : spec.trim();
        int meta = 0;
        int at = s.indexOf('@');
        if (at >= 0) {
            try {
                meta = Integer.parseInt(s.substring(at + 1).trim());
            } catch (NumberFormatException e) {
                return ItemStack.EMPTY;
            }
            s = s.substring(0, at).trim();
        }
        if (s.isEmpty()) {
            return ItemStack.EMPTY;
        }
        Item item = ForgeRegistries.ITEMS.getValue(new ResourceLocation(s));
        return item == null ? ItemStack.EMPTY : new ItemStack(item, 1, meta);
    }

    /** 软盘：每份一张，镜像与标签写好、盘号到了玩家手里再分配 */
    static final class Floppies implements MineBayDelivery {
        private final ToolDisk disk;

        Floppies(ToolDisk disk) {
            this.disk = disk;
        }

        @Override
        public List<ItemStack> prepare(EntityPlayerMP buyer, int quantity) {
            List<ItemStack> out = new ArrayList<>();
            for (int i = 0; i < quantity; i++) {
                out.add(ItemFloppyDisk.unnumberedToolDisk(disk));
            }
            return out;
        }
    }
}
