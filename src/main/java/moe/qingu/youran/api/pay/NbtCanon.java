package moe.qingu.youran.api.pay;

import net.minecraft.nbt.JsonToNBT;
import net.minecraft.nbt.NBTBase;
import net.minecraft.nbt.NBTException;
import net.minecraft.nbt.NBTTagByte;
import net.minecraft.nbt.NBTTagByteArray;
import net.minecraft.nbt.NBTTagCompound;
import net.minecraft.nbt.NBTTagDouble;
import net.minecraft.nbt.NBTTagFloat;
import net.minecraft.nbt.NBTTagInt;
import net.minecraft.nbt.NBTTagIntArray;
import net.minecraft.nbt.NBTTagList;
import net.minecraft.nbt.NBTTagLong;
import net.minecraft.nbt.NBTTagLongArray;
import net.minecraft.nbt.NBTTagShort;
import net.minecraft.nbt.NBTTagString;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * NBT 的规范写法（SNBT）：复合标签的键按字典序排、字符串和键一律加引号、数值带类型后缀，同样的 NBT 总是写成同样的字符串，
 * 可以拿来比较、当作记账的键。写出来的字符串原版的 {@code JsonToNBT} 能读回去（{@link #parse}）。
 * <p>
 * 有些值写出来读不回同样的东西（浮点数的 NaN、无穷大）：{@link #roundTrips} 为 false，pay.yr 不收这样的物品（记了账以后还不回原样）。
 */
public final class NbtCanon {
    private NbtCanon() {
    }

    public static String write(NBTBase tag) {
        StringBuilder sb = new StringBuilder();
        write(sb, tag);
        return sb.toString();
    }

    private static void write(StringBuilder sb, NBTBase tag) {
        switch (tag.getId()) {
            case 1:
                sb.append(((NBTTagByte) tag).getByte()).append('b');
                break;
            case 2:
                sb.append(((NBTTagShort) tag).getShort()).append('s');
                break;
            case 3:
                sb.append(((NBTTagInt) tag).getInt());
                break;
            case 4:
                sb.append(((NBTTagLong) tag).getLong()).append('L');
                break;
            case 5:
                sb.append(Float.toString(((NBTTagFloat) tag).getFloat())).append('f');
                break;
            case 6:
                sb.append(Double.toString(((NBTTagDouble) tag).getDouble())).append('d');
                break;
            case 7: {
                sb.append("[B;");
                byte[] b = ((NBTTagByteArray) tag).getByteArray();
                for (int i = 0; i < b.length; i++) {
                    sb.append(i == 0 ? "" : ",").append(b[i]).append('b');
                }
                sb.append(']');
                break;
            }
            case 8:
                quote(sb, ((NBTTagString) tag).getString());
                break;
            case 9: {
                NBTTagList l = (NBTTagList) tag;
                sb.append('[');
                for (int i = 0; i < l.tagCount(); i++) {
                    if (i > 0) {
                        sb.append(',');
                    }
                    write(sb, l.get(i));
                }
                sb.append(']');
                break;
            }
            case 10: {
                NBTTagCompound c = (NBTTagCompound) tag;
                List<String> keys = new ArrayList<>(c.getKeySet());
                Collections.sort(keys);
                sb.append('{');
                for (int i = 0; i < keys.size(); i++) {
                    if (i > 0) {
                        sb.append(',');
                    }
                    quote(sb, keys.get(i));
                    sb.append(':');
                    write(sb, c.getTag(keys.get(i)));
                }
                sb.append('}');
                break;
            }
            case 11: {
                sb.append("[I;");
                int[] a = ((NBTTagIntArray) tag).getIntArray();
                for (int i = 0; i < a.length; i++) {
                    sb.append(i == 0 ? "" : ",").append(a[i]);
                }
                sb.append(']');
                break;
            }
            case 12: {
                // 1.12 的 NBTTagLongArray 没有公开取数组的方法：照原版 toString 的写法拆（"[L;1L,2L]"）
                sb.append(normalizeLongArray(tag.toString()));
                break;
            }
            default:
                throw new IllegalArgumentException("不认识的 NBT 类型 " + tag.getId());
        }
    }

    /** 原版写的长整数组是 {@code [L;1L,2L]}，这里只去掉空白，保证写法固定 */
    static String normalizeLongArray(String s) {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            if (!Character.isWhitespace(c)) {
                sb.append(c);
            }
        }
        return sb.toString();
    }

    private static void quote(StringBuilder sb, String s) {
        sb.append('"');
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            if (c == '"' || c == '\\') {
                sb.append('\\');
            }
            sb.append(c);
        }
        sb.append('"');
    }

    /** 读回复合标签；不是合法 SNBT 时抛 {@link IllegalArgumentException} */
    public static NBTTagCompound parse(String snbt) {
        try {
            return JsonToNBT.getTagFromJson(snbt);
        } catch (NBTException | RuntimeException e) {
            throw new IllegalArgumentException("NBT 写法不对：" + e.getMessage(), e);
        }
    }

    /** 规范写法读回去再写一遍还是同一个字符串 */
    public static boolean roundTrips(String canonical) {
        try {
            return write(parse(canonical)).equals(canonical);
        } catch (RuntimeException e) {
            return false;
        }
    }

    /** 测试用：长整数组 */
    static NBTTagLongArray longArray(long... v) {
        return new NBTTagLongArray(v);
    }
}
