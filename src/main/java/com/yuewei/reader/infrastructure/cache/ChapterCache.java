package com.yuewei.reader.infrastructure.cache;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.stream.Stream;

/** 章节正文文件缓存，按 bookId/idx.txt 存放。 */
public class ChapterCache {

    private final Path root;

    public ChapterCache(Path root) {
        this.root = root;
    }

    public Path root() {
        return root;
    }

    public Path fileOf(long bookId, int idx) {
        return root.resolve(String.valueOf(bookId)).resolve(idx + ".txt");
    }

    public boolean exists(long bookId, int idx) {
        return Files.isRegularFile(fileOf(bookId, idx));
    }

    public String read(long bookId, int idx) {
        Path file = fileOf(bookId, idx);
        if (!Files.isRegularFile(file)) {
            return null;
        }
        try {
            return Files.readString(file, StandardCharsets.UTF_8);
        } catch (IOException e) {
            return null;
        }
    }

    public void write(long bookId, int idx, String text) {
        Path file = fileOf(bookId, idx);
        try {
            Files.createDirectories(file.getParent());
            Path tmp = file.resolveSibling(idx + ".tmp");
            Files.writeString(tmp, text == null ? "" : text, StandardCharsets.UTF_8);
            Files.move(tmp, file, StandardCopyOption.REPLACE_EXISTING);
        } catch (IOException e) {
            throw new UncheckedIOException("写入章节缓存失败", e);
        }
    }

    public void deleteBook(long bookId) {
        Path dir = root.resolve(String.valueOf(bookId));
        if (!Files.exists(dir)) {
            return;
        }
        try (Stream<Path> walk = Files.walk(dir)) {
            walk.sorted((a, b) -> b.getNameCount() - a.getNameCount()).forEach(p -> {
                try {
                    Files.deleteIfExists(p);
                } catch (IOException ignored) {
                    // 清理失败不阻断主流程
                }
            });
        } catch (IOException ignored) {
            // 同上
        }
    }

    public long sizeOfBook(long bookId) {
        Path dir = root.resolve(String.valueOf(bookId));
        if (!Files.isDirectory(dir)) {
            return 0;
        }
        try (Stream<Path> walk = Files.walk(dir)) {
            return walk.filter(Files::isRegularFile).mapToLong(p -> {
                try {
                    return Files.size(p);
                } catch (IOException e) {
                    return 0;
                }
            }).sum();
        } catch (IOException e) {
            return 0;
        }
    }
}
