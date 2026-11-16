package com.mrcrayfish.furniture.web.minebay;

/** 编译用替身（见 package-info）：家具的 MineBay 上架接口 */
public final class MineBayApi {
    private MineBayApi() {
    }

    public static void registerCategory(String id, String name) {
        throw new UnsupportedOperationException("stub");
    }

    public static synchronized void registerProduct(MineBayProduct product) {
        throw new UnsupportedOperationException("stub");
    }

    public static synchronized boolean unregisterProduct(String id) {
        throw new UnsupportedOperationException("stub");
    }
}
