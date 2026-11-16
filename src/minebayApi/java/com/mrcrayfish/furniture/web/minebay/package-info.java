/**
 * 家具 fork（qgmo 的 MrCrayfish 家具魔改）里 MineBay 上架接口的<b>编译用替身</b>：只有 YouranMod 用到的那几个类与方法的签名，没有实现。
 * 家具依赖 YouranMod，YouranMod 不能反过来依赖家具的 jar；所以 {@code compat.minebay} 照这些签名编译，运行时用家具里的真类
 * （只有装了家具模组时才加载 {@code compat.minebay}）。这个源码集不打包、不上运行时（见 build.gradle 的 minebayApi）。
 * 签名必须与家具的 {@code com.mrcrayfish.furniture.web.minebay} 一致；家具改了接口要同步改这里。
 */
package com.mrcrayfish.furniture.web.minebay;
