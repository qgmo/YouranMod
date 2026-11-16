package moe.qingu.youran.api.pay;

import net.minecraft.entity.player.EntityPlayerMP;
import net.minecraft.nbt.CompressedStreamTools;
import net.minecraft.nbt.NBTTagCompound;
import net.minecraft.nbt.NBTTagList;
import net.minecraft.nbt.NBTTagString;
import net.minecraft.server.MinecraftServer;
import net.minecraftforge.fml.common.FMLCommonHandler;

import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.UUID;
import java.util.regex.Pattern;

/**
 * 玩家存档里的“这笔在进行”标记，给想照 pay.yr 的做法往玩家背包里发东西、又要扛得住崩溃的模组 / 插件用
 * （先记下要发 → 改背包并加标记 → {@link #save} 写玩家存档 → {@link #onDisk} 读文件核对 → 记完成、去掉标记；
 * 开服时还没完成的，按存档里有没有标记判断东西到没到）。标记和背包写在同一个玩家存档里、一起存，所以“文件里有标记”就等于“背包的改动存下来了”。
 * <p>
 * 标记放在玩家数据（ForgeData）里，按 {@code owner}（“模组 ID:名字”）分开，每个 owner 一个字符串列表。
 * 除 {@link #onDisk} 外都在服务端主线程上调用。
 */
public final class PlayerMarks {
    /** owner 的写法，同网络登记名 */
    public static final Pattern OWNER = Pattern.compile("[a-z0-9_.-]{1,64}:[a-z0-9_./-]{1,64}");
    /** 一个 owner 在一名玩家身上最多留多少个标记（再多的 {@link #add} 抛异常，防止忘了去掉越积越多） */
    public static final int MAX_MARKS = 256;

    private PlayerMarks() {
    }

    /** 加一个标记（只改内存，随玩家存档存盘；要马上存用 {@link #save}） */
    public static void add(EntityPlayerMP player, String owner, String mark) {
        add(player.getEntityData(), key(owner), checkMark(mark));
    }

    /** 去掉一个标记（只改内存） */
    public static void remove(EntityPlayerMP player, String owner, String mark) {
        remove(player.getEntityData(), key(owner), mark);
    }

    /** 这名玩家身上 {@code owner} 的标记 */
    public static List<String> list(EntityPlayerMP player, String owner) {
        return list(player.getEntityData(), key(owner));
    }

    /** 现在就把这名玩家的数据（背包、标记……）写进玩家存档 */
    public static void save(EntityPlayerMP player) {
        MinecraftServer s = player.getServer();
        if (s == null) {
            throw new IllegalStateException("玩家不在服务端");
        }
        s.getWorld(0).getSaveHandler().getPlayerNBTManager().writePlayerData(player);
    }

    /**
     * 读玩家存档文件（{@code playerdata/<uuid>.dat}），看里面有没有这个标记。任意线程（只读文件），玩家不用在线。
     * 没有这个文件时返回 false；文件读不了抛 {@link IOException}。服务器没在运行时抛 {@link IllegalStateException}。
     */
    public static boolean onDisk(UUID player, String owner, String mark) throws IOException {
        MinecraftServer s = FMLCommonHandler.instance().getMinecraftServerInstance();
        if (s == null || s.getWorld(0) == null) {
            throw new IllegalStateException("服务器没在运行");
        }
        File f = new File(new File(s.getWorld(0).getSaveHandler().getWorldDirectory(), "playerdata"), player + ".dat");
        return onDisk(f, key(owner), mark);
    }

    static boolean onDisk(File f, String key, String mark) throws IOException {
        if (!f.isFile()) {
            return false;
        }
        NBTTagCompound c;
        try (InputStream in = new FileInputStream(f)) {
            c = CompressedStreamTools.readCompressed(in);
        }
        return list(c.getCompoundTag("ForgeData"), key).contains(mark);
    }

    static String key(String owner) {
        if (owner == null || !OWNER.matcher(owner).matches()) {
            throw new IllegalArgumentException("owner 要写成“模组 ID:名字”：" + owner);
        }
        return "marks:" + owner;
    }

    private static String checkMark(String mark) {
        if (mark == null || mark.isEmpty() || mark.length() > 128) {
            throw new IllegalArgumentException("标记要 1–128 个字符");
        }
        return mark;
    }

    static void add(NBTTagCompound data, String key, String mark) {
        NBTTagList l = data.getTagList(key, 8);
        for (int i = 0; i < l.tagCount(); i++) {
            if (mark.equals(l.getStringTagAt(i))) {
                return;
            }
        }
        if (l.tagCount() >= MAX_MARKS) {
            throw new IllegalStateException("标记太多了（" + MAX_MARKS + " 个），先去掉已经完成的");
        }
        l.appendTag(new NBTTagString(mark));
        data.setTag(key, l);
    }

    static void remove(NBTTagCompound data, String key, String mark) {
        if (!data.hasKey(key)) {
            return;
        }
        NBTTagList l = data.getTagList(key, 8);
        NBTTagList keep = new NBTTagList();
        for (int i = 0; i < l.tagCount(); i++) {
            if (!l.getStringTagAt(i).equals(mark)) {
                keep.appendTag(new NBTTagString(l.getStringTagAt(i)));
            }
        }
        if (keep.tagCount() == 0) {
            data.removeTag(key);
        } else {
            data.setTag(key, keep);
        }
    }

    static List<String> list(NBTTagCompound data, String key) {
        if (!data.hasKey(key)) {
            return Collections.emptyList();
        }
        NBTTagList l = data.getTagList(key, 8);
        List<String> out = new ArrayList<>(l.tagCount());
        for (int i = 0; i < l.tagCount(); i++) {
            out.add(l.getStringTagAt(i));
        }
        return out;
    }
}
