# YouranMod

悠然服务器自己的 Minecraft 1.12.2 Forge 模组（modid `youranmod`）：矿泉水、汞镓氯气、货币、主城维度与 `/youran`、`/hub` 指令、游戏内 IPv6 网络（服务器内网，接口在 `moe.qingu.youran.api.network`；设备接进网络用网卡（`NetworkInterface`，电脑模组在方块加载时插、卸载时拔；协议栈在玩家客户端上的用 `ClientRelayNic`），玩家客户端是一个协议栈、多个网口（`ClientHost`），定位模块、身份模块的证明由持有网卡的模组替操作者签（`api.network.location`、`api.network.identity`）；DNS 按真实 DNS 分层委派、运行中能注册，顶级域登记处、注册局与默认 DNS 服务在 `api.network.dns`，`.yr`、`.com` 的注册信息在世界存档的 `youranmod/dns/`；HTTPS 只信任 QinguMoe CA；网站自己向游戏内的证书签发服务 ca.yr 申请证书，接口在 `api.network.ca`，服务器配了中间 CA 才开）、聚合付款服务 pay.yr（给模组的接口在 `moe.qingu.youran.api.pay`，账本在世界存档的 `youranmod/pay/`）、浏览器主页 180.yr 与广告平台 platform.180.yr（网站用 `YouranNetworkApi.registerNavigation` 登记导航信息，广告在世界存档的 `youranmod/180/`）等服务器专属内容。用 `./gradlew build` 构建（需要 JDK 25 运行 Gradle；先把整合包里的 `immersivepetroleum-1.12.2-1.1.10.jar` 、`twilightforest-1.12.2-3.11.1021-universal.jar` 与 `twilighttweaks-1.12.2-v1.0.2.jar`（整合包里的文件名是 `….jar.jar`，复制时去掉一个 `.jar`）放到 `libs/`，只编译用，沉浸原油没有许可证，这几个 jar 都不进仓库），出两个 jar：`YouranServer-<版本>.jar` 是服务器用的完整包，`YouranServer-<版本>-client.jar` 是给玩家的客户端包（不含服务端的网络实现与服务端翻译的实现）。

IC2 流体/固体装罐机的富集模式加了“流体 + 物品 → 流体 + 物品”的反应配方（`moe.qingu.youran.recipe.CannerReactions`，CraftTweaker 里是 `mods.youranmod.CannerReaction`），用 Mixin 接进装罐机：服务端用 SpongeForge 自带的 Mixin，客户端与单人游戏用 MixinBooter；两边都没有时其余内容照常、只是这些配方不生效。

服务端翻译（`moe.qingu.youran.api.lang.ServerLang`）：服务端读语言目录（`config/youranmod.cfg` 的 `lang.dir`，默认与 YouranAPI 共用 `config/youranapi/lang`）与各模组 jar 里所有语言的语言文件（语言目录里直接放的 .lang 由文件名定语言，如 zh_cn.lang、zh_tw.lang、zh_cn-覆盖.lang），给其他模组查只在服务端用的中文名（家具的 MineBay、YouranSCP 的 SCP-294 在用）。

芯片品质接进电脑（`moe.qingu.youran.api.chip`）：CPU 温度与最近 1 分钟温度积分、调频的档与实际频率、运行错误率、寿命与过热的后果，YouranSCP 的普通电脑、个人电脑与家具电脑共用；数在 `config/youranmod.cfg` 的 `chip_runtime`。

电脑外设（`moe.qingu.youran.api.peripheral`）：外设物品的接口与基类、给电脑模组用的查询（某台电脑装了哪些外设、有没有某项能力），其他模组的电脑也能接入；本模组注册 5.25 英寸软驱、汉卡两种外设（先不加合成配方）。软盘（360 KB）堆叠数 1，NBT 只存盘号与标签名，内容存在 SCP 电脑那边的服务里。

芯片线“硅”：电弧炉出硅锭、打粉机与粉碎机出硅粉、装罐机与搅拌机出三氯氢硅（纯度记在流体 NBT 里），IC2 太阳能蒸馏机与沉浸原油精馏塔提纯，都用 Mixin 接进各机器；有 JEI 时显示这些配方。

暮色森林拆解台（`moe.qingu.youran.compat.twilightforest.UncraftGuard`，Mixin 在 `mixin.twilightforest`，没装暮色森林时不注册）：会刷物、洗数据的拆解不显示材料，同放进没有配方的东西；规则在 `logic.uncraft.UncraftRules`，写死的名单在 `UncraftBuiltins`，另加的名单在 `config/youranmod.cfg` 的 `uncrafting` 节。装了 Twilight Tweaks 时，JEI 的“拆解”页按代码里的规则藏起拆不了的配方、候选显示成拆解台实际给的那种（`compat.jei.TwilightUncrafting`；配置另加的名单客户端不知道，照旧显示）。
