package moe.qingu.youran.logic.uncraft;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Locale;

/**
 * 物品名单（拆解台用）：每行一个物品注册名，可带 {@code @数据值}，也可以写 {@code 模组ID:*} 表示这个模组的全部物品。
 * 空行与 {@code #} 开头的行跳过；格式不对的行跳过，原因记在 {@link #errors}。
 * <pre>
 * minecraft:rail            所有数据值
 * minecraft:stone@5         只有数据值 5（安山岩）
 * industrialwires:*         这个模组的全部物品
 * </pre>
 */
public final class ItemPatterns {
    /** 任意数据值 */
    public static final int ANY_META = -1;

    private final List<Entry> entries;
    public final List<String> errors;

    private ItemPatterns(List<Entry> entries, List<String> errors) {
        this.entries = Collections.unmodifiableList(entries);
        this.errors = Collections.unmodifiableList(errors);
    }

    public static final ItemPatterns EMPTY = new ItemPatterns(new ArrayList<>(), new ArrayList<>());

    static final class Entry {
        final String namespace;
        /** null 表示这个模组的全部物品 */
        final String path;
        final int meta;

        Entry(String namespace, String path, int meta) {
            this.namespace = namespace;
            this.path = path;
            this.meta = meta;
        }
    }

    public static ItemPatterns parse(String... lines) {
        List<Entry> entries = new ArrayList<>();
        List<String> errors = new ArrayList<>();
        if (lines != null) {
            for (String raw : lines) {
                String line = raw == null ? "" : raw.trim();
                if (line.isEmpty() || line.startsWith("#")) {
                    continue;
                }
                Entry e = entry(line);
                if (e == null) {
                    errors.add("“" + line + "”不是“模组ID:物品名”、“模组ID:物品名@数据值”或“模组ID:*”");
                } else {
                    entries.add(e);
                }
            }
        }
        return new ItemPatterns(entries, errors);
    }

    /** 几份名单合在一起（错误也合在一起） */
    public static ItemPatterns concat(ItemPatterns... lists) {
        List<Entry> entries = new ArrayList<>();
        List<String> errors = new ArrayList<>();
        for (ItemPatterns l : lists) {
            entries.addAll(l.entries);
            errors.addAll(l.errors);
        }
        return new ItemPatterns(entries, errors);
    }

    private static Entry entry(String line) {
        int meta = ANY_META;
        String id = line;
        int at = line.lastIndexOf('@');
        if (at >= 0) {
            id = line.substring(0, at);
            try {
                meta = Integer.parseInt(line.substring(at + 1).trim());
            } catch (NumberFormatException e) {
                return null;
            }
            if (meta < 0 || meta > Short.MAX_VALUE) {
                return null;
            }
        }
        id = id.trim().toLowerCase(Locale.ROOT);
        int colon = id.indexOf(':');
        if (colon <= 0 || colon == id.length() - 1 || id.indexOf(':', colon + 1) >= 0 || id.contains(" ")) {
            return null;
        }
        String ns = id.substring(0, colon);
        String path = id.substring(colon + 1);
        if ("*".equals(path)) {
            return meta == ANY_META ? new Entry(ns, null, ANY_META) : null;
        }
        if (path.contains("*")) {
            return null;
        }
        return new Entry(ns, path, meta);
    }

    /** {@code id} 是物品注册名（如 {@code minecraft:stone}），{@code meta} 是数据值 */
    public boolean matches(String id, int meta) {
        if (id == null || entries.isEmpty()) {
            return false;
        }
        String lower = id.toLowerCase(Locale.ROOT);
        int colon = lower.indexOf(':');
        if (colon <= 0) {
            return false;
        }
        String ns = lower.substring(0, colon);
        String path = lower.substring(colon + 1);
        for (Entry e : entries) {
            if (!e.namespace.equals(ns)) {
                continue;
            }
            if (e.path == null) {
                return true;
            }
            if (e.path.equals(path) && (e.meta == ANY_META || e.meta == meta)) {
                return true;
            }
        }
        return false;
    }

    public int size() {
        return entries.size();
    }
}
