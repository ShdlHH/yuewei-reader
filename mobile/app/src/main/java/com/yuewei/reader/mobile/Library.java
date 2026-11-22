package com.yuewei.reader.mobile;

import android.content.Context;

import com.yuewei.reader.domain.BookDetail;
import com.yuewei.reader.domain.TocChapter;
import com.yuewei.reader.infrastructure.network.Urls;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/** 本地书库：books.json 保存书架元数据，章节正文按书存成独立文件。 */
public class Library {

    public static class Book {
        public String id;
        public String title;
        public String author;
        public String hash;
        public long addedAt;
        public long lastReadAt;
        public String sourceType = "LOCAL";
        public String sourceId = "";
        public String sourceUrl = "";
        public String sourceName = "";
        public String bookUrl = "";
        public boolean deleted;
        public List<String> chapterTitles = new ArrayList<>();
        public List<Integer> chapterLengths = new ArrayList<>();
        public List<String> chapterUrls = new ArrayList<>();
        public int chapterIndex;
        public int percent;
        public long progressAt;

        JSONObject toJson() throws JSONException {
            JSONObject node = new JSONObject();
            node.put("id", id);
            node.put("title", title);
            node.put("author", author);
            node.put("hash", hash);
            node.put("addedAt", addedAt);
            node.put("lastReadAt", lastReadAt);
            node.put("sourceType", sourceType);
            node.put("sourceId", sourceId);
            node.put("sourceUrl", sourceUrl);
            node.put("sourceName", sourceName);
            node.put("bookUrl", bookUrl);
            node.put("deleted", deleted);
            node.put("chapterIndex", chapterIndex);
            node.put("percent", percent);
            node.put("progressAt", progressAt);
            node.put("chapterTitles", new JSONArray(chapterTitles));
            node.put("chapterUrls", new JSONArray(chapterUrls));
            JSONArray lengths = new JSONArray();
            for (Integer length : chapterLengths) {
                lengths.put(length == null ? 0 : length);
            }
            node.put("chapterLengths", lengths);
            return node;
        }

        static Book fromJson(JSONObject node) {
            Book book = new Book();
            book.id = node.optString("id");
            book.title = node.optString("title");
            book.author = node.optString("author");
            book.hash = node.optString("hash");
            book.addedAt = node.optLong("addedAt");
            book.lastReadAt = node.optLong("lastReadAt");
            book.sourceType = node.optString("sourceType", "LOCAL");
            book.sourceId = node.optString("sourceId");
            book.sourceUrl = node.optString("sourceUrl");
            book.sourceName = node.optString("sourceName");
            book.bookUrl = node.optString("bookUrl");
            book.deleted = node.optBoolean("deleted", false);
            book.chapterIndex = node.optInt("chapterIndex");
            book.percent = node.optInt("percent");
            book.progressAt = node.optLong("progressAt");
            JSONArray titles = node.optJSONArray("chapterTitles");
            if (titles != null) {
                for (int i = 0; i < titles.length(); i++) {
                    book.chapterTitles.add(titles.optString(i));
                }
            }
            JSONArray urls = node.optJSONArray("chapterUrls");
            if (urls != null) {
                for (int i = 0; i < urls.length(); i++) {
                    book.chapterUrls.add(urls.optString(i));
                }
            }
            JSONArray lengths = node.optJSONArray("chapterLengths");
            if (lengths != null) {
                for (int i = 0; i < lengths.length(); i++) {
                    book.chapterLengths.add(lengths.optInt(i));
                }
            }
            return book;
        }
    }

    private final File root;
    private final File metaFile;
    private final File backupFile;
    public final List<Book> books = new ArrayList<>();

    public Library(Context context) {
        root = new File(context.getFilesDir(), "library");
        //noinspection ResultOfMethodCallIgnored
        root.mkdirs();
        metaFile = new File(root, "books.json");
        backupFile = new File(root, "books.json.bak");
        reload();
    }

    public synchronized void reload() {
        books.clear();
        JSONArray array = readBooksArray();
        if (array == null) {
            return;
        }
        try {
            List<Book> loaded = new ArrayList<>();
            for (int i = 0; i < array.length(); i++) {
                loaded.add(Book.fromJson(array.getJSONObject(i)));
            }
            books.addAll(dedupe(loaded));
        } catch (JSONException ignored) {
            // 元数据损坏时从空书架开始，章节文件仍在磁盘上
        }
    }

    /** 依次尝试正式文件与备份；返回 null 表示两者都不可用。 */
    private JSONArray readBooksArray() {
        for (File file : new File[]{metaFile, backupFile}) {
            if (!file.isFile()) {
                continue;
            }
            try {
                String text = new String(Files.readAllBytes(file.toPath()), StandardCharsets.UTF_8);
                JSONArray array = new JSONObject(text).optJSONArray("books");
                if (array != null) {
                    return array;
                }
            } catch (IOException | JSONException ignored) {
                // 尝试下一个文件
            }
        }
        return null;
    }

    /**
     * 写盘前与磁盘上的版本按 id 合并：多个页面各自持有 Library 快照时，
     * 同步线程的旧快照不会覆盖掉用户刚做的删除或最新进度。
     */
    public synchronized void save() {
        try {
            Map<String, Book> disk = readDiskBooks();
            List<Book> merged = new ArrayList<>();
            Set<String> seen = new HashSet<>();
            for (Book book : books) {
                if (book.id == null || book.id.isEmpty()) {
                    merged.add(book);
                    continue;
                }
                seen.add(book.id);
                Book older = disk.get(book.id);
                merged.add(older == null ? book : newer(book, older));
            }
            for (Book book : disk.values()) {
                if (!seen.contains(book.id)) {
                    merged.add(book);
                }
            }
            merged = dedupe(merged);
            books.clear();
            books.addAll(merged);
            JSONObject rootNode = new JSONObject();
            JSONArray array = new JSONArray();
            for (Book book : merged) {
                array.put(book.toJson());
            }
            rootNode.put("version", 1);
            rootNode.put("books", array);
            // 先写临时文件并落盘，再用整文件替换；进程被杀也不会留下半截 JSON
            File tmp = new File(root, "books.json.tmp");
            try (FileOutputStream out = new FileOutputStream(tmp)) {
                out.write(rootNode.toString().getBytes(StandardCharsets.UTF_8));
                out.getFD().sync();
            }
            if (metaFile.isFile()) {
                Files.move(metaFile.toPath(), backupFile.toPath(),
                        StandardCopyOption.REPLACE_EXISTING);
            }
            try {
                Files.move(tmp.toPath(), metaFile.toPath(),
                        StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
            } catch (AtomicMoveNotSupportedException e) {
                Files.move(tmp.toPath(), metaFile.toPath(), StandardCopyOption.REPLACE_EXISTING);
            }
        } catch (IOException | JSONException ignored) {
            // 写入失败时下次保存再试，旧文件与备份仍可读
        }
    }

    private Map<String, Book> readDiskBooks() {
        Map<String, Book> map = new HashMap<>();
        JSONArray array = readBooksArray();
        if (array == null) {
            return map;
        }
        try {
            for (int i = 0; i < array.length(); i++) {
                Book book = Book.fromJson(array.getJSONObject(i));
                if (book.id != null && !book.id.isEmpty()) {
                    map.put(book.id, book);
                }
            }
        } catch (JSONException ignored) {
            // 损坏时按空处理
        }
        return map;
    }

    /** 同一本书只保留一条：老版本并发写盘可能留下同 id 的活跃记录与墓碑。 */
    private static List<Book> dedupe(List<Book> source) {
        Map<String, Book> byId = new LinkedHashMap<>();
        List<Book> result = new ArrayList<>();
        for (Book book : source) {
            if (book.id == null || book.id.isEmpty()) {
                result.add(book);
                continue;
            }
            Book existing = byId.get(book.id);
            if (existing == null) {
                byId.put(book.id, book);
            } else {
                byId.put(book.id, newerState(existing, book));
            }
        }
        result.addAll(byId.values());
        return result;
    }

    private static Book newerState(Book a, Book b) {
        long ta = stateTime(a);
        long tb = stateTime(b);
        if (ta != tb) {
            return ta > tb ? a : b;
        }
        // 时间相同优先保留活跃记录，避免把刚复活的书又隐藏掉
        if (a.deleted != b.deleted) {
            return a.deleted ? b : a;
        }
        return a;
    }

    /** 同一本书的内存/磁盘版本取“状态时间”更新者：删除、阅读、入架都会推进该时间。 */
    private static Book newer(Book memory, Book disk) {
        long a = stateTime(memory);
        long b = stateTime(disk);
        return a >= b ? memory : disk;
    }

    private static long stateTime(Book book) {
        return Math.max(book.addedAt, Math.max(book.lastReadAt, book.progressAt));
    }

    public synchronized Book find(String id) {
        for (Book book : books) {
            if (book.id.equals(id)) {
                return book;
            }
        }
        return null;
    }

    public synchronized Book findByHash(String hash) {
        for (Book book : books) {
            if (hash != null && !hash.isEmpty() && hash.equals(book.hash)) {
                return book;
            }
        }
        return null;
    }

    /** 在线书按「书源地址 + 书籍地址」匹配，书源 ID 在不同设备上不通用。 */
    public synchronized Book findOnline(String sourceUrl, String bookUrl) {
        String sourceKey = Urls.key(sourceUrl);
        String bookKey = Urls.key(bookUrl);
        if (sourceKey.isEmpty() || bookKey.isEmpty()) {
            return null;
        }
        for (Book book : books) {
            if (sourceKey.equals(Urls.key(book.sourceUrl))
                    && bookKey.equals(Urls.key(book.bookUrl))) {
                return book;
            }
        }
        return null;
    }

    /** 用书源详情里的目录创建一本在线书并入架，搜索页与同步补书共用。 */
    public synchronized Book addOnline(SourceStore.Source source, BookDetail detail) {
        String id = "o" + Integer.toHexString((source.url + "|" + detail.bookUrl).hashCode());
        Book book = find(id);
        if (book == null) {
            // 老版本可能用别的 id 生成规则，按「书源地址 + 书籍地址」再找一次，避免出现重复条目
            book = findOnline(source.url, detail.bookUrl);
        }
        boolean revived = book != null && book.deleted;
        if (book == null) {
            book = new Book();
            book.id = id;
            books.add(book);
        }
        // 之前删除过的同一条记录直接复活，避免书架出现重复条目
        book.deleted = false;
        book.title = detail.title == null || detail.title.isBlank() ? "未命名" : detail.title;
        book.author = detail.author == null || detail.author.isBlank() ? "未知" : detail.author;
        book.hash = "";
        book.addedAt = System.currentTimeMillis();
        book.sourceType = "ONLINE";
        book.sourceId = source.url;
        book.sourceUrl = source.url;
        book.sourceName = source.name;
        book.bookUrl = detail.bookUrl;
        if (revived) {
            // 目录可能已变化：旧章节文件必须清掉，进度从零开始
            clearChapterFiles(book.id);
            book.chapterIndex = 0;
            book.percent = 0;
            book.progressAt = 0;
            book.lastReadAt = 0;
        }
        book.chapterTitles.clear();
        book.chapterUrls.clear();
        book.chapterLengths.clear();
        for (TocChapter chapter : detail.chapters) {
            book.chapterTitles.add(chapter.title);
            book.chapterUrls.add(chapter.url == null ? "" : chapter.url);
            book.chapterLengths.add(0);
        }
        save();
        return book;
    }

    private void clearChapterFiles(String bookId) {
        File[] files = new File(root, bookId).listFiles();
        if (files == null) {
            return;
        }
        for (File file : files) {
            //noinspection ResultOfMethodCallIgnored
            file.delete();
        }
    }

    public File chapterFile(String bookId, int index) {
        return new File(new File(root, bookId), index + ".txt");
    }

    /** 书的章节文件目录，供缓存统计与清理使用。 */
    public File bookDir(String bookId) {
        return new File(root, bookId);
    }

    public String chapterText(Book book, int index) throws IOException {
        File file = chapterFile(book.id, index);
        if (!file.isFile()) {
            return "本章内容缺失，请重新导入 TXT。";
        }
        return new String(Files.readAllBytes(file.toPath()), StandardCharsets.UTF_8);
    }

    public void writeChapter(String bookId, int index, String content) throws IOException {
        File dir = new File(root, bookId);
        //noinspection ResultOfMethodCallIgnored
        dir.mkdirs();
        try (FileOutputStream out = new FileOutputStream(chapterFile(bookId, index))) {
            out.write(content.getBytes(StandardCharsets.UTF_8));
        }
    }

    public synchronized void setProgress(Book book, int chapterIndex, int percent) {
        if (book == null) {
            return;
        }
        Book target = resolve(book);
        if (target.deleted) {
            return;
        }
        touchProgress(book, chapterIndex, percent);
        save();
    }

    /** 翻页时只更新内存，避免每页重写整份 books.json；退出阅读页时再调用 setProgress 落盘。 */
    public synchronized void touchProgress(Book book, int chapterIndex, int percent) {
        if (book == null) {
            return;
        }
        Book target = resolve(book);
        if (target.deleted) {
            return;
        }
        target.chapterIndex = Math.max(0, chapterIndex);
        target.percent = Math.max(0, Math.min(10000, percent));
        target.progressAt = System.currentTimeMillis();
        target.lastReadAt = target.progressAt;
        if (target != book) {
            book.chapterIndex = target.chapterIndex;
            book.percent = target.percent;
            book.progressAt = target.progressAt;
            book.lastReadAt = target.lastReadAt;
        }
    }

    /** 删除墓碑：本地隐藏，但保留记录让其他设备同步删除。 */
    public synchronized void markDeleted(Book book) {
        if (book == null) {
            return;
        }
        Book target = resolve(book);
        if (target.deleted) {
            return;
        }
        target.deleted = true;
        target.progressAt = System.currentTimeMillis();
        target.lastReadAt = target.progressAt;
        if (target != book) {
            book.deleted = true;
            book.progressAt = target.progressAt;
            book.lastReadAt = target.lastReadAt;
        }
        save();
    }

    public synchronized void cacheChapter(Book book, int index, String content) throws IOException {
        writeChapter(book.id, index, content);
        Book target = resolve(book);
        while (target.chapterLengths.size() <= index) {
            target.chapterLengths.add(0);
        }
        target.chapterLengths.set(index, content.length());
        save();
    }

    /** reload() 会替换列表里的对象，这里按 id 找回最新对象，避免把进度写到旧快照上。 */
    private Book resolve(Book book) {
        if (book.id == null || book.id.isEmpty()) {
            return book;
        }
        Book current = find(book.id);
        return current == null ? book : current;
    }
}
