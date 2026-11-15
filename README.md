# YouranMod

悠然服务器自己的 Minecraft 1.12.2 Forge 模组（modid `youranmod`）：矿泉水、汞镓氯气、货币、主城维度与 `/youran`、`/hub` 指令、游戏内 IPv6 网络（服务器内网，接口在 `moe.qingu.youran.api.network`；HTTPS 只信任 QinguMoe CA，服务器配了中间 CA 才开）等服务器专属内容。用 `./gradlew build` 构建（需要 JDK 25 运行 Gradle；先把整合包里的 `immersivepetroleum-1.12.2-1.1.10.jar` 放到 `libs/`，只编译用，沉浸原油没有许可证，jar 不进仓库），出两个 jar：`youranmod-<版本>.jar` 是服务器用的完整包，`youranmod-<版本>-client.jar` 是给玩家的客户端包（不含服务端的网络实现与服务端翻译的实现）。

IC2 流体/固体装罐机的富集模式加了“流体 + 物品 → 流体 + 物品”的反应配方（`moe.qingu.youran.recipe.CannerReactions`，CraftTweaker 里是 `mods.youranmod.CannerReaction`），用 Mixin 接进装罐机：服务端用 SpongeForge 自带的 Mixin，客户端与单人游戏用 MixinBooter；两边都没有时其余内容照常、只是这些配方不生效。

服务端翻译（`moe.qingu.youran.api.lang.ServerLang`）：服务端读语言目录（`config/youranmod.cfg` 的 `lang.dir`，默认与 YouranAPI 共用 `config/youranapi/lang`）与各模组 jar 里所有语言的语言文件（语言目录里直接放的 .lang 由文件名定语言，如 zh_cn.lang、zh_tw.lang、zh_cn-覆盖.lang），给其他模组查只在服务端用的中文名（家具的 MineBay、YouranSCP 的 SCP-294 在用）。

芯片线“硅”：电弧炉出硅锭、打粉机与粉碎机出硅粉、装罐机与搅拌机出三氯氢硅（纯度记在流体 NBT 里），IC2 太阳能蒸馏机与沉浸原油精馏塔提纯，都用 Mixin 接进各机器；有 JEI 时显示这些配方。
