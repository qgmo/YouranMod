package moe.qingu.youran.server.net.nav;

import java.util.EnumMap;
import java.util.Map;

/** 广告位的数量、价格与排队规则（开服时按配置定，测试里直接给） */
final class AdRules {
    /** 一天多少毫秒（服主 11-16：广告按现实时间计，关服时也照走） */
    static final long DAY_MS = 86_400_000L;

    final Map<AdSlot, Integer> positions = new EnumMap<>(AdSlot.class);
    /** 每位每天多少绿宝石 */
    final Map<AdSlot, Integer> emeraldsPerDay = new EnumMap<>(AdSlot.class);
    /** 每个位置正在播的后面最多排几条 */
    final int queue;
    /** 一条最多几天 */
    final int maxDays;

    AdRules(int banner, int square, int text, int button, int priceBanner, int priceSquare, int priceText, int priceButton, int queue, int maxDays) {
        positions.put(AdSlot.BANNER, banner);
        positions.put(AdSlot.SQUARE, square);
        positions.put(AdSlot.TEXT, text);
        positions.put(AdSlot.BUTTON, button);
        emeraldsPerDay.put(AdSlot.BANNER, priceBanner);
        emeraldsPerDay.put(AdSlot.SQUARE, priceSquare);
        emeraldsPerDay.put(AdSlot.TEXT, priceText);
        emeraldsPerDay.put(AdSlot.BUTTON, priceButton);
        this.queue = queue;
        this.maxDays = maxDays;
    }

    static AdRules fromConfig() {
        return new AdRules(1, NavConfig.squareSlots, NavConfig.textSlots, NavConfig.buttonSlots,
                NavConfig.priceBanner, NavConfig.priceSquare, NavConfig.priceText, NavConfig.priceButton, NavConfig.queuePerSlot, NavConfig.maxDays);
    }

    int positions(AdSlot s) {
        return positions.get(s);
    }

    /** 一共多少绿宝石 */
    long emeralds(AdSlot s, int days) {
        return (long) emeraldsPerDay.get(s) * days;
    }
}
