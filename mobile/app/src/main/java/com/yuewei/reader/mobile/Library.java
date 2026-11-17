package com.yuewei.reader.mobile;

import android.content.Context;

import com.yuewei.reader.domain.BookDetail;
import com.yuewei.reader.domain.TocChapter;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.List;

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
    public final List<Book> books = new ArrayList<>();

    public Library(Context context) {
        root = new File(context.getFilesDir(), "library");
        //noinspection ResultOfMethodCallIgnored
        root.mkdirs();
        metaFile = new File(root, "books.json");
        reload();
    }

    public void reload() {
        books.clear();
        if (!metaFile.isFile()) {
            return;
        }
        try {
            String text = new String(Files.readAllBytes(metaFile.toPath()), StandardCharsets.UTF_8);
            JSONObject rootNode = new JSONObject(text);
            JSONArray array = rootNode.optJSONArray("books");
            if (array != null) {
                for (int i = 0; i < array.length(); i++) {
                    books.add(Book.fromJson(array.getJSONObject(i)));
                }
            }
        } catch (IOException | JSONException ignored) {
            // 元数据损坏时从空书架开始，章节文件仍在磁盘上
        }
    }

    public void save() {
        try {
            JSONObject rootNode = new JSONObject();
            JSONArray array = new JSONArray();
            for (Book book : books) {
                array.put(book.toJson());
            }
            rootNode.put("version", 1);
            rootNode.put("books", array);
            try (FileOutputStream out = new FileOutputStream(metaFile)) {
                out.write(rootNode.toString().getBytes(StandardCharsets.UTF_8));
            }
        } catch (IOException | JSONException ignored) {
            // 写入失败时下次保存再试
        }
    }

    public Book find(String id) {
        for (Book book : books) {
            if (book.id.equals(id)) {
                return book;
            }
        }
        return null;
    }

    public Book findByHash(String hash) {
        for (Book book : books) {
            if (hash != null && !hash.isEmpty() && hash.equals(book.hash)) {
                return book;
            }
        }
        return null;
    }

    /** 在线书按「书源地址 + 书籍地址」匹配，书源 ID 在不同设备上不通用。 */
    public Book findOnline(String sourceUrl, String bookUrl) {
        if (sourceUrl == null || sourceUrl.isEmpty() || bookUrl == null || bookUrl.isEmpty()) {
            return null;
        }
        for (Book book : books) {
            if (sourceUrl.equals(book.sourceUrl) && bookUrl.equals(book.bookUrl)) {
                return book;
            }
        }
        return null;
    }

    /** 用书源详情里的目录创建一本在线书并入架，搜索页与同步补书共用。 */
    public Book addOnline(SourceStore.Source source, BookDetail detail) {
        Book book = new Book();
        book.id = "o" + Integer.toHexString((source.url + "|" + detail.bookUrl).hashCode());
        book.title = detail.title == null || detail.title.isBlank() ? "未命名" : detail.title;
        book.author = detail.author == null || detail.author.isBlank() ? "未知" : detail.author;
        book.hash = "";
        book.addedAt = System.currentTimeMillis();
        book.sourceType = "ONLINE";
        book.sourceId = source.url;
        book.sourceUrl = source.url;
        book.sourceName = source.name;
        book.bookUrl = detail.bookUrl;
        for (TocChapter chapter : detail.chapters) {
            book.chapterTitles.add(chapter.title);
            book.chapterUrls.add(chapter.url == null ? "" : chapter.url);
            book.chapterLengths.add(0);
        }
        books.add(book);
        save();
        return book;
    }

    public File chapterFile(String bookId, int index) {
        return new File(new File(root, bookId), index + ".txt");
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

    public void setProgress(Book book, int chapterIndex, int percent) {
        book.chapterIndex = Math.max(0, chapterIndex);
        book.percent = Math.max(0, Math.min(10000, percent));
        book.progressAt = System.currentTimeMillis();
        book.lastReadAt = book.progressAt;
        save();
    }

    /** 删除墓碑：本地隐藏，但保留记录让其他设备同步删除。 */
    public void markDeleted(Book book) {
        if (book == null || book.deleted) {
            return;
        }
        book.deleted = true;
        book.progressAt = System.currentTimeMillis();
        book.lastReadAt = book.progressAt;
        save();
    }

    public void cacheChapter(Book book, int index, String content) throws IOException {
        writeChapter(book.id, index, content);
        while (book.chapterLengths.size() <= index) {
            book.chapterLengths.add(0);
        }
        book.chapterLengths.set(index, content.length());
        save();
    }
}
