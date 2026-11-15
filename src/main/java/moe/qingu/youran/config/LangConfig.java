package moe.qingu.youran.config;

import net.minecraftforge.common.config.Config;

/**
 * 服务端翻译（{@code api.lang.ServerLang}）读的语言目录。服务端本来没有原版的中文语言文件，放在这里，其他模组只在服务端用的
 * 中文名（MineBay 的搜索与排序、SCP-294 认名字等）就从这里取。
 */
@Config(modid = ServerConfig.KEY, name = ServerConfig.FILE, category = "lang")
public final class LangConfig {
    @Config.Comment({
            "服务端翻译的语言目录，默认和 YouranAPI 的语言目录（chat.lang-dir 留空时）是同一个，服主只放一份文件。相对路径相对于服务器目录；留空 = 不读。",
            "放 .lang 文件与资源包（文件夹或 zip，读其中 assets/*/lang/<语言>.lang，所有语言都读），优先于各模组 jar 自带的语言文件。",
            "直接放的 .lang 文件由文件名决定语言：<语言>.lang 或 <语言>-<任意>.lang，<语言> 是 xx_yy 形式的语言代码（不分大小写），",
            "如 zh_cn.lang、zh_tw.lang、en_us.lang、ja_jp.lang、zh_cn-覆盖.lang；文件名对不上的（如 zz-覆盖.lang）记警告并跳过。",
            "同一种语言内按文件名排序，同一个键以后面的为准（zh_cn-覆盖.lang 盖过 zh_cn.lang）；查不到的语言退回 en_us，简繁之间不互相回退。",
            "开服前在后台读，目录不存在、文件坏了只记警告。"
    })
    @Config.RequiresMcRestart
    public static String dir = "config/youranapi/lang";

    private LangConfig() {
    }
}
