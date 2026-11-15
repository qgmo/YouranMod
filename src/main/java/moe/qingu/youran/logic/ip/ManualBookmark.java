package moe.qingu.youran.logic.ip;

/**
 * 沉浸工程手册“最后看的多方块结构”的名字（沉浸原油 {@code CloseBookPacket} 写进手册的 {@code lastMultiblock}，审查 IP-L2）。
 *
 * <p>正常客户端发的是手册页面上多方块结构的注册名（{@code IMultiblock.getUniqueName()}，如 {@code IE:ArcFurnace}、
 * {@code IP:DistillationTower}），都很短，只有字母、数字与少数符号。超过 {@value #MAX_LENGTH} 个字或有别的字符的不收。
 */
public final class ManualBookmark {
    /** 名字最长几个字（现有多方块结构的注册名都在 30 个字以内） */
    public static final int MAX_LENGTH = 64;

    private ManualBookmark() {
    }

    /** 名字长度、字符合不合理（是否真有这个多方块结构另外查） */
    public static boolean plausible(String name) {
        if (name == null || name.isEmpty() || name.length() > MAX_LENGTH) {
            return false;
        }
        for (int i = 0; i < name.length(); i++) {
            char c = name.charAt(i);
            boolean ok = (c >= 'a' && c <= 'z') || (c >= 'A' && c <= 'Z') || (c >= '0' && c <= '9')
                    || c == ':' || c == '_' || c == '-' || c == '.' || c == '/';
            if (!ok) {
                return false;
            }
        }
        return true;
    }
}
