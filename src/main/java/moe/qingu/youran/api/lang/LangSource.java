package moe.qingu.youran.api.lang;

import javax.annotation.Nullable;

/**
 * 加载好的一份服务端翻译表（语言目录与模组 jar 里的语言文件），只读，可以在线程间共享。
 * 由 YouranMod 的服务端加载（见 {@link ServerLang}）；调用方一般不直接用它，用 {@link ServerLang} 的静态方法。
 */
public interface LangSource {
    /** 什么都没有的表 */
    LangSource EMPTY = new LangSource() {
        @Nullable
        @Override
        public String find(String locale, String key) {
            return null;
        }

        @Override
        public String summary() {
            return "没有加载语言文件";
        }
    };

    /**
     * 这个语言（小写，如 {@code zh_cn}）里这个键的值：语言目录优先，其次模组 jar；都没有时为 null。不回退到别的语言。
     * 值是语言文件里的格式串（数字格式已统一成 {@code %s}）。
     */
    @Nullable
    String find(String locale, String key);

    /** 各来源的条数（日志用） */
    String summary();
}
