package com.yuewei.reader.mobile;

import android.content.Context;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;

import com.yuewei.reader.domain.BookDetail;
import com.yuewei.reader.domain.SearchResult;

import org.json.JSONArray;
import org.json.JSONObject;

import java.text.SimpleDateFormat;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.Date;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * 移动端同步：与桌面端共用 WebDAV 目录结构。
 * bookProgress/{书名_作者}.json 为 Legado 兼容进度，shelf.json / settings.json 为快照。
 */
public final class SyncManager {

    public interface Callback {
        void finished(String message, boolean ok);
    }

    /** 回调必须回到主线程：调用方会直接刷新列表、弹 Toast。 */
    private static final Handler MAIN = new Handler(Looper.getMainLooper());

    private static final String TAG = "YueweiSync";

    /** 防止连点或从不同页面同时触发同步，两个线程并发上传会互相覆盖。 */
    private static final java.util.concurrent.atomic.AtomicBoolean RUNNING =
            new java.util.concurrent.atomic.AtomicBoolean();

    private SyncManager() {
    }

    public static void sync(Context context, Callback callback) {
        AppSettings settings = new AppSettings(context);
        if (!settings.syncEnabled() || settings.syncUrl().isEmpty()) {
            if (callback != null) {
                callback.finished("请先在设置里填写 WebDAV 地址并启用同步", false);
            }
            return;
        }
        if (!RUNNING.compareAndSet(false, true)) {
            if (callback != null) {
                callback.finished("正在同步，请稍候", false);
            }
            return;
        }
        new Thread(() -> {
            String message;
            boolean ok;
            try {
                message = run(context, settings);
                ok = true;
                settings.saveSyncDone(message);
                Log.i(TAG, "同步完成：" + message);
            } catch (Exception e) {
                message = e.getMessage() == null ? e.getClass().getSimpleName() : e.getMessage();
                ok = false;
                Log.e(TAG, "同步失败", e);
            }
            String finalMessage = message;
            boolean finalOk = ok;
            MAIN.post(() -> {
                RUNNING.set(false);
                if (callback != null) {
                    callback.finished(finalMessage, finalOk);
                }
            });
        }, "yuewei-sync").start();
    }

    /**
     * 自动同步：关闭开关、未配置或距上次同步尝试不足 minIntervalMs 时静默跳过。
     *
     * @return 是否真正发起了同步
     */
    public static boolean autoSync(Context context, long minIntervalMs, Callback callback) {
        AppSettings settings = new AppSettings(context);
        if (!settings.autoSync() || !settings.syncEnabled() || settings.syncUrl().isEmpty()) {
            return false;
        }
        // 成功时间与尝试时间取较新者：失败后也不会每次回到应用都重试并弹提示
        long last = Math.max(settings.lastSyncAt(), settings.lastAttemptAt());
        if (System.currentTimeMillis() - last < minIntervalMs) {
            return false;
        }
        if (RUNNING.get()) {
            return false;
        }
        settings.saveSyncAttempt(System.currentTimeMillis());
        sync(context, callback);
        return true;
    }

    static boolean running() {
        return RUNNING.get();
    }

    /** 书架顶部显示的同步状态。 */
    static String lastSyncText(Context context) {
        AppSettings settings = new AppSettings(context);
        long at = settings.lastSyncAt();
        if (at <= 0) {
            return "尚未同步";
        }
        String time = new SimpleDateFormat("MM-dd HH:mm", Locale.CHINA).format(new Date(at));
        return "上次同步 " + time;
    }

    private static String run(Context context, AppSettings settings) throws Exception {
        Library library = new Library(context);
        SourceStore sources = new SourceStore(context);
        WebDav dav = new WebDav(settings.syncUrl(), settings.syncDir(),
                settings.syncAccount(), settings.syncPassword());
        return run(library, sources, settings, dav);
    }

    /** 同步主流程；独立成方法便于单元测试注入内存书库与 WebDAV。 */
    static String run(Library library, SourceStore sources, AppSettings settings, WebDav dav)
            throws Exception {

        int applied = pullProgress(dav, library);
        Log.i(TAG, "拉取进度 " + applied + " 本");
        int shelfMerged = pullShelf(dav, library, sources);
        Log.i(TAG, "合并书架 " + shelfMerged + " 本");
        boolean settingsApplied = pullSettings(dav, settings);
        Log.i(TAG, "应用远端设置 " + settingsApplied);
        int pushed = pushAll(dav, library, settings);
        Log.i(TAG, "上传进度 " + pushed + " 本");

        StringBuilder message = new StringBuilder();
        if (applied > 0) {
            message.append("拉取 ").append(applied).append(" 本进度");
        }
        if (shelfMerged > 0) {
            message.append(message.length() > 0 ? "；" : "").append("合并 ").append(shelfMerged).append(" 本书架");
        }
        if (settingsApplied) {
            message.append(message.length() > 0 ? "；" : "").append("应用远端设置");
        }
        message.append(message.length() > 0 ? "；" : "").append("上传 ").append(pushed).append(" 本进度与书架");
        return message.toString();
    }

    private static int pullProgress(WebDav dav, Library library) throws Exception {
        List<WebDav.RemoteFile> files = dav.list("bookProgress");
        Map<String, Library.Book> index = new HashMap<>();
        for (Library.Book book : library.books) {
            if (book.deleted) {
                continue;
            }
            index.putIfAbsent(fileKey(book.title, book.author), book);
        }
        int applied = 0;
        for (WebDav.RemoteFile file : files) {
            if (!file.name.endsWith(".json")) {
                continue;
            }
            String key = file.name.substring(0, file.name.length() - 5);
            Library.Book book = index.get(key);
            if (book == null) {
                continue;
            }
            String json = dav.get("bookProgress/" + file.name);
            if (json == null) {
                continue;
            }
            JSONObject node = new JSONObject(json);
            long remoteTime = node.optLong("durChapterTime", 0);
            if (remoteTime <= book.progressAt) {
                continue;
            }
            int chapter = Math.max(0, node.optInt("durChapterIndex", 0));
            if (!book.chapterTitles.isEmpty()) {
                chapter = Math.min(chapter, book.chapterTitles.size() - 1);
            }
            int length = chapter < book.chapterLengths.size() ? book.chapterLengths.get(chapter) : 0;
            int pos = Math.max(0, node.optInt("durChapterPos", 0));
            int percent = length > 0 ? (int) Math.round(Math.min(1, pos / (double) length) * 10000) : 0;
            book.chapterIndex = chapter;
            book.percent = percent;
            book.progressAt = remoteTime;
            book.lastReadAt = Math.max(book.lastReadAt, remoteTime);
            applied++;
        }
        if (applied > 0) {
            library.save();
        }
        return applied;
    }

    private static int pullShelf(WebDav dav, Library library, SourceStore sources) throws Exception {
        String json = dav.get("shelf.json");
        if (json == null) {
            return 0;
        }
        JSONArray books = new JSONObject(json).optJSONArray("books");
        if (books == null) {
            return 0;
        }
        int merged = 0;
        for (int i = 0; i < books.length(); i++) {
            JSONObject item = books.optJSONObject(i);
            if (item == null) {
                continue;
            }
            Library.Book book = matchAny(library, item);
            if (item.optBoolean("deleted", false)) {
                // 远端墓碑：本地隐藏并保留记录，避免下次同步复活
                if (book != null && !book.deleted) {
                    library.markDeleted(book);
                    merged++;
                }
                continue;
            }
            if (book == null) {
                // 这台设备还没有这本书：桌面端新增的在线书按书源重新抓目录入架
                book = importOnline(library, sources, item);
                if (book == null) {
                    continue;
                }
                merged++;
            }
            if (book.deleted) {
                // 本地已删：等墓碑同步，不再接受旧进度
                continue;
            }
            long progressAt = millis(item.optString("progressAt"));
            if (progressAt > book.progressAt) {
                int chapter = Math.max(0, item.optInt("chapterIndex", 0));
                if (!book.chapterTitles.isEmpty()) {
                    chapter = Math.min(chapter, book.chapterTitles.size() - 1);
                }
                book.chapterIndex = chapter;
                book.percent = Math.max(0, Math.min(10000, item.optInt("percent", 0)));
                book.progressAt = progressAt;
                merged++;
            }
            long lastRead = millis(item.optString("lastReadAt"));
            if (lastRead > book.lastReadAt) {
                book.lastReadAt = lastRead;
                merged++;
            }
        }
        if (merged > 0) {
            library.save();
        }
        return merged;
    }

    /** 书架匹配：本地 TXT 用文件指纹，在线书用「书源地址 + 书籍地址」，重建后仍能对上。 */
    private static Library.Book matchAny(Library library, JSONObject item) {
        Library.Book byHash = library.findByHash(item.optString("fileHash"));
        if (byHash != null) {
            return byHash;
        }
        return library.findOnline(item.optString("sourceUrl"), item.optString("bookUrl"));
    }

    /** 其他设备新增的在线书：本机有同一书源时重新抓目录入架，失败只跳过这本书。 */
    private static Library.Book importOnline(Library library, SourceStore store, JSONObject item) {
        if (!"ONLINE".equals(item.optString("sourceType"))) {
            return null;
        }
        String bookUrl = item.optString("bookUrl");
        SourceStore.Source source = store.find(item.optString("sourceUrl"));
        if (bookUrl.isEmpty() || source == null) {
            return null;
        }
        try {
            SearchResult result = new SearchResult();
            result.title = item.optString("title");
            result.author = item.optString("author");
            result.bookUrl = bookUrl;
            BookDetail detail = new OnlineService().detail(source, result);
            return library.addOnline(source, detail);
        } catch (Exception e) {
            return null;
        }
    }

    private static boolean pullSettings(WebDav dav, AppSettings settings) throws Exception {
        String json = dav.get("settings.json");
        if (json == null) {
            return false;
        }
        JSONObject root = new JSONObject(json);
        long remoteAt = millis(root.optString("updatedAt"));
        if (remoteAt <= Math.max(settings.settingsLocalAt(), settings.settingsRemoteAt())) {
            return false;
        }
        JSONObject values = root.optJSONObject("values");
        if (values == null) {
            return false;
        }
        int fontSize = parseInt(values.optString("reader.fontSize"), 0);
        float lineSpacing = parseFloat(values.optString("reader.lineSpacing"), 0f);
        String theme = values.optString("reader.theme", "");
        settings.applyRemoteSettings(fontSize, lineSpacing, theme, remoteAt);
        return true;
    }

    private static int pushAll(WebDav dav, Library library, AppSettings settings) throws Exception {
        dav.ensureDir();
        int pushed = 0;
        for (Library.Book book : library.books) {
            if (book.deleted || book.progressAt <= 0 || book.chapterTitles.isEmpty()) {
                continue;
            }
            JSONObject node = new JSONObject();
            node.put("name", book.title);
            node.put("author", book.author);
            node.put("durChapterIndex", book.chapterIndex);
            node.put("durChapterPos", chapterPos(book));
            node.put("durChapterTitle", book.chapterTitles.get(
                    Math.min(book.chapterIndex, book.chapterTitles.size() - 1)));
            node.put("durChapterTime", book.progressAt);
            dav.put("bookProgress/" + fileKey(book.title, book.author) + ".json", node.toString());
            pushed++;
        }
        dav.put("shelf.json", shelfJson(library));
        if (settings.settingsLocalAt() > 0) {
            dav.put("settings.json", settingsJson(settings));
        }
        return pushed;
    }

    private static String shelfJson(Library library) throws Exception {
        JSONObject root = new JSONObject();
        root.put("version", 1);
        root.put("device", "android");
        root.put("updatedAt", iso(System.currentTimeMillis()));
        JSONArray books = new JSONArray();
        for (Library.Book book : library.books) {
            JSONObject item = new JSONObject();
            item.put("deleted", book.deleted);
            item.put("title", book.title);
            item.put("author", book.author);
            item.put("sourceType", book.sourceType == null || book.sourceType.isEmpty()
                    ? "LOCAL" : book.sourceType);
            item.put("sourceId", 0);
            item.put("sourceUrl", book.sourceUrl == null ? "" : book.sourceUrl);
            item.put("sourceName", book.sourceName == null ? "" : book.sourceName);
            item.put("bookUrl", book.bookUrl == null ? "" : book.bookUrl);
            item.put("fileHash", book.hash == null ? "" : book.hash);
            item.put("chapterCount", book.chapterTitles.size());
            item.put("addedAt", iso(book.addedAt));
            item.put("lastReadAt", book.lastReadAt > 0 ? iso(book.lastReadAt) : "");
            if (book.progressAt > 0) {
                item.put("chapterIndex", book.chapterIndex);
                item.put("percent", book.percent);
                item.put("progressAt", iso(book.progressAt));
            }
            books.put(item);
        }
        root.put("books", books);
        return root.toString();
    }

    private static String settingsJson(AppSettings settings) throws Exception {
        JSONObject root = new JSONObject();
        long at = settings.settingsLocalAt() > 0 ? settings.settingsLocalAt() : System.currentTimeMillis();
        root.put("updatedAt", iso(at));
        JSONObject values = new JSONObject();
        values.put("reader.fontSize", String.valueOf(settings.fontSize()));
        values.put("reader.lineSpacing", String.valueOf(settings.lineSpacing()));
        values.put("reader.theme", settings.theme());
        root.put("values", values);
        return root.toString();
    }

    private static int chapterPos(Library.Book book) {
        int chapter = Math.min(book.chapterIndex, Math.max(0, book.chapterLengths.size() - 1));
        int length = book.chapterLengths.isEmpty() ? 0 : book.chapterLengths.get(chapter);
        return (int) Math.round(book.percent / 10000.0 * length);
    }

    static String fileKey(String title, String author) {
        String name = safe(title);
        String writer = safe(author);
        return writer.isEmpty() ? name : name + "_" + writer;
    }

    private static String safe(String value) {
        return (value == null ? "" : value)
                .replaceAll("[\\\\/:*?\"<>|\\r\\n\\t]", "").trim();
    }

    static String iso(long millis) {
        return LocalDateTime.ofInstant(Instant.ofEpochMilli(millis), ZoneId.systemDefault()).toString();
    }

    static long millis(String text) {
        if (text == null || text.isEmpty()) {
            return 0;
        }
        try {
            return LocalDateTime.parse(text).atZone(ZoneId.systemDefault()).toInstant().toEpochMilli();
        } catch (RuntimeException e) {
            return 0;
        }
    }

    private static int parseInt(String text, int fallback) {
        try {
            return Integer.parseInt(text.trim());
        } catch (RuntimeException e) {
            return fallback;
        }
    }

    private static float parseFloat(String text, float fallback) {
        try {
            return Float.parseFloat(text.trim());
        } catch (RuntimeException e) {
            return fallback;
        }
    }

    static List<Library.Book> sortedBooks(Library library) {
        List<Library.Book> books = new ArrayList<>();
        for (Library.Book book : library.books) {
            if (!book.deleted) {
                books.add(book);
            }
        }
        books.sort((a, b) -> Long.compare(
                b.lastReadAt > 0 ? b.lastReadAt : b.addedAt,
                a.lastReadAt > 0 ? a.lastReadAt : a.addedAt));
        return books;
    }

    static String percentText(Library.Book book) {
        if (book.chapterTitles.isEmpty()) {
            return "0%";
        }
        double value = (book.chapterIndex + Math.min(0.99, book.percent / 10000.0))
                / book.chapterTitles.size();
        return String.format(Locale.CHINA, "%.1f%%", Math.min(1, value) * 100);
    }
}
