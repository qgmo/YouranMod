package moe.qingu.youran.server.net.nav;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 广告的存档（世界存档的 {@code youranmod/180/}）：{@code ads.json}（账本）与 {@code img/<编号>.png}（检查并重新写过的图）。
 * 写的时候先写临时文件、落盘，再原子改名。图片读过一次就留在内存里（每张最多几十 KB）。
 */
final class AdStore {
    /** 存下来的图最多多大（重新写过的 16 色 PNG，256×256 以内远不到） */
    static final int MAX_IMAGE_BYTES = 48 * 1024;

    private final Path dir;
    private final Map<Integer, byte[]> images = new ConcurrentHashMap<>();

    AdStore(Path dir) {
        this.dir = dir;
    }

    Path dir() {
        return dir;
    }

    /** 读账本；没有文件时返回 null */
    JsonObject load() throws IOException {
        Path f = dir.resolve("ads.json");
        if (!Files.exists(f)) {
            return null;
        }
        return new JsonParser().parse(new String(Files.readAllBytes(f), StandardCharsets.UTF_8)).getAsJsonObject();
    }

    synchronized void save(JsonObject o) throws IOException {
        write(dir.resolve("ads.json"), o.toString().getBytes(StandardCharsets.UTF_8));
    }

    void putImage(int id, byte[] png) throws IOException {
        if (png.length > MAX_IMAGE_BYTES) {
            throw new IOException("图片太大");
        }
        Files.createDirectories(dir.resolve("img"));
        write(dir.resolve("img").resolve(id + ".png"), png);
        images.put(id, png);
    }

    /** 广告的图；没有为 null */
    byte[] image(int id) {
        byte[] b = images.get(id);
        if (b != null) {
            return b;
        }
        Path f = dir.resolve("img").resolve(id + ".png");
        try {
            if (!Files.exists(f) || Files.size(f) > MAX_IMAGE_BYTES) {
                return null;
            }
            b = Files.readAllBytes(f);
        } catch (IOException e) {
            return null;
        }
        images.put(id, b);
        return b;
    }

    void deleteImage(int id) {
        images.remove(id);
        try {
            Files.deleteIfExists(dir.resolve("img").resolve(id + ".png"));
        } catch (IOException ignored) {
            // 删不掉下次再说
        }
    }

    private void write(Path f, byte[] data) throws IOException {
        Files.createDirectories(f.getParent());
        Path tmp = f.resolveSibling(f.getFileName() + ".tmp");
        try (FileChannel ch = FileChannel.open(tmp, StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING, StandardOpenOption.WRITE)) {
            ByteBuffer b = ByteBuffer.wrap(data);
            while (b.hasRemaining()) {
                ch.write(b);
            }
            ch.force(true);
        }
        try {
            Files.move(tmp, f, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
        } catch (AtomicMoveNotSupportedException e) {
            Files.move(tmp, f, StandardCopyOption.REPLACE_EXISTING);
        }
    }
}
