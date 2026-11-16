package com.mrcrayfish.furniture.web.minebay;

import net.minecraft.item.ItemStack;

/** 编译用替身（见 package-info）：一件商品与它的 Builder（只有 YouranMod 用到的方法） */
public final class MineBayProduct {
    private MineBayProduct() {
    }

    public static Builder builder(String id) {
        throw new UnsupportedOperationException("stub");
    }

    public static final class Builder {
        private Builder() {
        }

        public Builder category(String category) {
            throw new UnsupportedOperationException("stub");
        }

        public Builder name(String name) {
            throw new UnsupportedOperationException("stub");
        }

        public Builder nameKey(String key) {
            throw new UnsupportedOperationException("stub");
        }

        public Builder icon(ItemStack icon) {
            throw new UnsupportedOperationException("stub");
        }

        public Builder price(ItemStack currency, int amount) {
            throw new UnsupportedOperationException("stub");
        }

        public Builder item(ItemStack item) {
            throw new UnsupportedOperationException("stub");
        }

        public Builder delivery(MineBayDelivery delivery) {
            throw new UnsupportedOperationException("stub");
        }

        public Builder maxPerOrder(int n) {
            throw new UnsupportedOperationException("stub");
        }

        public Builder description(String line) {
            throw new UnsupportedOperationException("stub");
        }

        public Builder descriptionKey(String key) {
            throw new UnsupportedOperationException("stub");
        }

        public Builder stock(int perMonth, Stock.Rule rule) {
            throw new UnsupportedOperationException("stub");
        }

        public Builder keywords(String... words) {
            throw new UnsupportedOperationException("stub");
        }

        public MineBayProduct build() {
            throw new UnsupportedOperationException("stub");
        }
    }
}
