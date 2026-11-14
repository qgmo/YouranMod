# YouranMod

悠然服务器自己的 Minecraft 1.12.2 Forge 模组（modid `youranmod`）：矿泉水、汞镓氯气、货币、主城维度与 `/youran`、`/hub` 指令、游戏内 IPv6 网络（服务器内网，接口在 `moe.qingu.youran.api.network`；HTTPS 只信任 QinguMoe CA，服务器配了中间 CA 才开）等服务器专属内容。用 `./gradlew build` 构建（需要 JDK 25 运行 Gradle），出两个 jar：`youranmod-<版本>.jar` 是服务器用的完整包，`youranmod-<版本>-client.jar` 是给玩家的客户端包（不含服务端的网络实现）。

IC2 流体/固体装罐机的富集模式加了“流体 + 物品 → 流体 + 物品”的反应配方（`moe.qingu.youran.recipe.CannerReactions`，CraftTweaker 里是 `mods.youranmod.CannerReaction`），用 Mixin 接进装罐机：服务端用 SpongeForge 自带的 Mixin，客户端与单人游戏用 MixinBooter；两边都没有时其余内容照常、只是这些配方不生效。
