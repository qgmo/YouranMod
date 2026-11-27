package moe.qingu.youran.logic.source;

import moe.qingu.youran.config.MineBayConfig;
import moe.qingu.youran.item.ToolDisk;
import moe.qingu.youran.logic.litho.ChipModel;
import moe.qingu.youran.logic.pcb.BoardType;
import org.junit.jupiter.api.Test;

import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.regex.Pattern;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** MineBay 上架清单（服主 11-16）：掩模每月 1 套按月重置、三档价格；设计图、底片、ROM 镜像盘不限量；工具盘照服主 11-15 */
class MineBayOffersTest {
    /** MineBay 商品编号的写法（家具 ShopParams.isProductId：模组ID:名字，小写字母、数字、_ . - /） */
    private static final Pattern PRODUCT_ID = Pattern.compile("[a-z0-9_.-]{1,32}:[a-z0-9_./-]{1,64}");
    private static final Pattern LANG_KEY = Pattern.compile("[A-Za-z0-9._-]{1,128}");

    private static List<MineBayOffers.Offer> defaults() {
        return MineBayOffers.plan(MineBayConfig.prices());
    }

    @Test
    void tiers() {
        for (ChipModel m : new ChipModel[]{ChipModel.Z80, ChipModel.DRAM_4164, ChipModel.ROM_MONITOR, ChipModel.ROM_BIOS, ChipModel.EPROM_2764,
                ChipModel.FONT_ROM_1, ChipModel.FONT_ROM_2, ChipModel.FONT_ROM_3, ChipModel.FONT_ROM_4, ChipModel.ROM_LASER_ALIGN}) {
            assertEquals(MineBayOffers.Tier.LOW, MineBayOffers.Tier.of(m), m.id);
        }
        assertEquals(MineBayOffers.Tier.MID, MineBayOffers.Tier.of(ChipModel.I8088));
        assertEquals(MineBayOffers.Tier.MID, MineBayOffers.Tier.of(ChipModel.NIC_CTRL), "网卡控制芯片按中档（服主 11-27：同 8088）");
        assertEquals(MineBayOffers.Tier.MID, MineBayOffers.Tier.of(ChipModel.TLS_COPROC), "加密协处理器按中档（服主 11-27）");
        assertEquals(MineBayOffers.Tier.HIGH, MineBayOffers.Tier.of(ChipModel.YR2000));
    }

    /** 掩模：每种型号一件，每月 1 套，按月整批重置；价格三档、从便宜到贵 */
    @Test
    void masksMonthlyOneEach() {
        Set<ChipModel> seen = new HashSet<>();
        for (MineBayOffers.Offer o : defaults()) {
            if (o.kind != MineBayOffers.Kind.MASK) {
                continue;
            }
            assertNotNull(o.model);
            assertTrue(seen.add(o.model));
            assertEquals(1, o.perMonth, o.id);
            assertTrue(o.limited() && o.monthlyReset, o.id + " 按月整批重置");
            assertEquals("youranmod:photomask/" + o.model.id, o.id);
            assertNull(o.nameKey, "名字用物品自己的（各语言）");
            int expect = o.model == ChipModel.YR2000 ? MineBayConfig.maskPriceHigh
                    : o.model == ChipModel.I8088 || o.model == ChipModel.NIC_CTRL || o.model == ChipModel.TLS_COPROC ? MineBayConfig.maskPriceMid
                    : MineBayConfig.maskPriceLow;
            assertEquals(expect, o.price, o.id);
        }
        assertEquals(ChipModel.values().length, seen.size(), "全部型号都有掩模卖");
        assertTrue(MineBayConfig.maskPriceLow < MineBayConfig.maskPriceMid && MineBayConfig.maskPriceMid < MineBayConfig.maskPriceHigh);
    }

    /** 设计图便宜、不限量；底片四种不限量；ROM 镜像盘三种不限量；工具盘四张照服主 11-15、第五张网络工具盘照服主 11-27（每天补） */
    @Test
    void othersUnlimitedExceptToolDisks() {
        int designs = 0, films = 0, roms = 0, tools = 0;
        for (MineBayOffers.Offer o : defaults()) {
            switch (o.kind) {
                case DESIGN:
                    designs++;
                    assertFalse(o.limited(), o.id);
                    assertEquals(MineBayConfig.designPrice, o.price);
                    assertTrue(o.price < MineBayConfig.maskPriceLow, "设计图比掩模便宜");
                    break;
                case FILM:
                    films++;
                    assertFalse(o.limited(), o.id);
                    assertNotNull(o.board);
                    assertEquals(MineBayConfig.filmPrice, o.price);
                    break;
                case ROM_DISK:
                    roms++;
                    assertFalse(o.limited(), o.id);
                    assertTrue(o.disk.rom);
                    assertEquals(MineBayConfig.romDiskPrice, o.price);
                    assertNotNull(o.nameKey);
                    break;
                case TOOL_DISK:
                    tools++;
                    assertFalse(o.disk.rom);
                    assertTrue(o.limited() && !o.monthlyReset, o.id + " 按月上限、每天补");
                    assertNotNull(o.nameKey);
                    break;
                default:
                    break;
            }
        }
        assertEquals(ChipModel.values().length, designs);
        assertEquals(4, films, "普通电脑、个人电脑、汉卡、ISA 扩展卡四种底片（家具电脑主板没有底片）");
        assertEquals(3, roms);
        assertEquals(5, tools);
        int[] prices = new int[5], perMonth = new int[5];
        int i = 0;
        for (MineBayOffers.Offer o : defaults()) {
            if (o.kind == MineBayOffers.Kind.TOOL_DISK) {
                prices[i] = o.price;
                perMonth[i++] = o.perMonth;
            }
        }
        assertArrayEquals(new int[]{16, 24, 48, 12, 16}, prices, "服主 11-15 定的价格；网络工具盘 16（服主 11-27）");
        assertArrayEquals(new int[]{14, 7, 7, 7, 14}, perMonth, "服主 11-15 定的每月张数；网络工具盘 14（服主 11-27，同 BASIC 盘）");
    }

    /** 编号合 MineBay 的写法且不重复；翻译键合浏览器 data-yr-lang 的写法；软盘的镜像名就是 079 服务的镜像 */
    @Test
    void idsAndKeys() {
        Set<String> ids = new HashSet<>();
        for (MineBayOffers.Offer o : defaults()) {
            assertTrue(PRODUCT_ID.matcher(o.id).matches(), o.id);
            assertTrue(ids.add(o.id), "重复的编号 " + o.id);
            assertTrue(LANG_KEY.matcher(o.descKey).matches(), o.descKey);
            if (o.nameKey != null) {
                assertTrue(LANG_KEY.matcher(o.nameKey).matches(), o.nameKey);
            }
            assertTrue(o.price >= 1 && o.price <= MineBayOffers.MAX_PRICE);
            assertTrue(o.category.equals(MineBayOffers.CAT_CHIPMAKING) || o.category.equals(MineBayOffers.CAT_SOFTWARE));
        }
        assertEquals(2 * ChipModel.values().length + MineBayOffers.FILM_BOARDS.size() + ToolDisk.values().length, ids.size());
        assertTrue(ids.contains("youranmod:pcb_film/computer") && ids.contains("youranmod:pcb_film/pc") && ids.contains("youranmod:pcb_film/hanzi"));
        assertTrue(ids.contains("youranmod:rom_disk/monitor"));
        assertTrue(ids.contains("youranmod:rom_disk/bios"));
        assertTrue(ids.contains("youranmod:rom_disk/align"));
        assertTrue(ids.contains("youranmod:tool_disk/basic"));
        assertTrue(ids.contains("youranmod:tool_disk/network"), "网络工具盘（079 服务的镜像 network）");
        assertTrue(MineBayOffers.CAT_CHIPMAKING_NAME.length() <= 8 && MineBayOffers.CAT_SOFTWARE_NAME.length() <= 8, "MineBay 分类名 1–8 个字");
    }

    /** 配置读取：默认值、改过的值、不合格的收进范围、数组短了用默认 */
    @Test
    void pricesFromConfig() {
        MineBayOffers.Prices p = MineBayConfig.prices();
        assertEquals("minecraft:emerald", MineBayConfig.currency);
        assertTrue(MineBayConfig.enabled);
        assertEquals(64, p.maskLow);
        assertEquals(128, p.maskMid);
        assertEquals(256, p.maskHigh);
        assertEquals(1, p.maskPerMonth);
        assertEquals(2, p.design);
        assertEquals(8, p.film);
        assertEquals(16, p.romDisk);
        MineBayOffers.Prices q = new MineBayOffers.Prices(0, 2_000_000, 300, 0, -5, 9, 20, new int[]{30}, new int[]{0, 200_000});
        assertEquals(1, q.maskLow);
        assertEquals(MineBayOffers.MAX_PRICE, q.maskMid);
        assertEquals(300, q.maskHigh);
        assertEquals(300, q.mask(ChipModel.YR2000));
        assertEquals(1, q.maskPerMonth);
        assertEquals(1, q.design);
        assertEquals(9, q.film);
        assertEquals(20, q.romDisk);
        assertArrayEquals(new int[]{30, 24, 48, 12, 16}, q.toolPrices);
        assertArrayEquals(new int[]{1, MineBayOffers.MAX_PER_MONTH, 7, 7, 14}, q.toolPerMonth);
        MineBayOffers.Prices old = new MineBayOffers.Prices(1, 2, 3, 2, 1, 1, 1, new int[]{16, 24, 48, 12}, new int[]{14, 7, 7, 7});
        assertEquals(16, old.toolPrices[4], "旧配置文件只有四张盘的数：网络工具盘用默认价格");
        assertEquals(14, old.toolPerMonth[4], "旧配置文件只有四张盘的数：网络工具盘用默认张数");
        MineBayOffers.Prices r = new MineBayOffers.Prices(1, 2, 3, 2, 1, 1, 1, null, null);
        assertArrayEquals(MineBayOffers.DEFAULT_TOOL_PRICES, r.toolPrices);
        assertEquals(2, MineBayOffers.plan(r).get(0).perMonth, "改了每月套数照改");
    }
}
