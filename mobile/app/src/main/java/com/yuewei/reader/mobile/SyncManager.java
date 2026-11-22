package com.yuewei.reader.mobile;

import android.content.Context;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;

import com.yuewei.reader.domain.BookDetail;
import com.yuewei.reader.domain.SearchResult;
import com.yuewei.reader.infrastructure.network.Urls;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.text.SimpleDateFormat;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.Date;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * 移动端同步：与桌面端共用 WebDAV 目录结构。
 * bookProgress/{书名_作者}.json 为 Legado 兼容进度（含删除墓碑），shelf.json 为书架快照；
 * 阅读排版设置只保存在本机，不参与同步。
 *
 * <p>推送书架前先读取远端并按条目合并，未在本机导入成功的远端条目会原样保留。</p>
 */
public final class SyncManager {

    public interface Callback {
        void finished(String message, boolean ok);
    }

    /** 回调必须回到主线程：调用方会直接刷新列表、弹 Toast。 */
    private static final Handler MAIN = new Handler(Looper.getMainLooper());

    private static final String TAG = "YueweiSync";

    /** shelf.json 结构版本：2 增加毫秒时间字段，仍保留原有 ISO 字段供旧客户端读取。 */
    private static final int SHELF_VERSION = 2;

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
        settings.saveSyncPending(true);
        new Thread(() -> {
            String message;
            boolean ok;
            try {
                message = run(context, settings);
                ok = true;
                settings.saveSyncDone(message);
                settings.saveSyncPending(false);
                Log.i(TAG, "同步完成：" + message);
            } catch (Exception e) {
                message = e.getMessage() == null ? e.getClass().getSimpleName() : e.getMessage();
                ok = false;
                // 已捕获的失败算一次完成的尝试；只有进程被杀才会留下进行中标记
                settings.saveSyncPending(false);
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
        boolean pending = settings.syncPending();
        if ((!pending && !settings.autoSync()) || !settings.syncEnabled() || settings.syncUrl().isEmpty()) {
            return false;
        }
        // 上次同步被中断时立即补跑；否则按间隔限流
        long last = pending ? 0 : Math.max(settings.lastSyncAt(), settings.lastAttemptAt());
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
        if (settings.syncPending()) {
            return "上次同步未完成";
        }
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
        return run(library, sources, dav);
    }

    /** 同步主流程；独立成方法便于单元测试注入内存书库与 WebDAV。 */
    static String run(Library library, SourceStore sources, WebDav dav)
            throws Exception {

        int applied = pullProgress(dav, library);
        Log.i(TAG, "拉取进度 " + applied + " 本");
        int shelfMerged = pullShelf(dav, library, sources);
        Log.i(TAG, "合并书架 " + shelfMerged + " 本");
        // 推送前重新读盘，避免用同步开始时的旧快照覆盖阅读页刚写入的进度
        library.reload();
        int pushed = pushAll(dav, library);
        Log.i(TAG, "上传进度 " + pushed + " 本");

        StringBuilder message = new StringBuilder();
        if (applied > 0) {
            message.append("拉取 ").append(applied).append(" 本进度");
        }
        if (shelfMerged > 0) {
            message.append(message.length() > 0 ? "；" : "").append("合并 ").append(shelfMerged).append(" 本书架");
        }
        message.append(message.length() > 0 ? "；" : "").append("上传 ").append(pushed).append(" 本进度与书架");
        return message.toString();
    }

    private static int pullProgress(WebDav dav, Library library) throws Exception {
        List<WebDav.RemoteFile> files = dav.list("bookProgress");
        ProgressIndex index = progressIndex(library);
        int applied = 0;
        for (WebDav.RemoteFile file : files) {
            if (!file.name.endsWith(".json")) {
                continue;
            }
            String key = file.name.substring(0, file.name.length() - 5);
            String json = dav.get("bookProgress/" + file.name);
            if (json == null) {
                continue;
            }
            JSONObject node;
            try {
                node = new JSONObject(json);
            } catch (JSONException e) {
                // 单个文件损坏不影响其它书同步
                continue;
            }
            Library.Book book = index.find(node, key);
            if (book == null) {
                continue;
            }
            long remoteTime = node.optLong("durChapterTime", 0);
            if (remoteTime <= 0 || remoteTime <= book.progressAt) {
                continue;
            }
            int chapter = Math.max(0, node.optInt("durChapterIndex", 0));
            if (!book.chapterTitles.isEmpty()) {
                chapter = Math.min(chapter, book.chapterTitles.size() - 1);
            }
            int length = chapter < book.chapterLengths.size() ? book.chapterLengths.get(chapter) : 0;
            int pos = Math.max(0, node.optInt("durChapterPos", 0));
            if (length <= 0 && pos > 0) {
                // 本地还没缓存本章，无法换算比例；交给 shelf.json 的 percent，避免写坏位点
                continue;
            }
            book.chapterIndex = chapter;
            book.percent = length > 0
                    ? (int) Math.round(Math.min(1, pos / (double) length) * 10000) : 0;
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
        JSONArray books;
        try {
            books = new JSONObject(json).optJSONArray("books");
        } catch (JSONException e) {
            return 0;
        }
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
            long remoteActivity = activity(item);
            long remoteDeletedAt = deletedAt(item);
            if (item.optBoolean("deleted", false)) {
                if (book == null || book.deleted) {
                    continue;
                }
                // 远端删除之后本机仍有阅读活动时保留本地，交给推送合并
                long localActivity = Math.max(book.addedAt,
                        Math.max(book.lastReadAt, book.progressAt));
                if (remoteDeletedAt > 0 && localActivity > remoteDeletedAt) {
                    continue;
                }
                library.markDeleted(book);
                merged++;
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
                // 本地有墓碑：只有远端在删除之后仍有入架/阅读活动才复活
                long localDeletedAt = Math.max(book.lastReadAt, book.progressAt);
                if (localDeletedAt > 0 && remoteActivity < localDeletedAt) {
                    continue;
                }
                Library.Book revived = importOnline(library, sources, item);
                if (revived == null) {
                    // 书源不可用时退回本地旧目录，至少让书重新可见
                    revived = book;
                    revived.deleted = false;
                }
                long activatedAt = remoteActivity > 0 ? remoteActivity : System.currentTimeMillis();
                revived.chapterIndex = 0;
                revived.percent = 0;
                revived.progressAt = 0;
                revived.lastReadAt = activatedAt;
                revived.addedAt = activatedAt;
                library.save();
                writeProgressReset(dav, library, revived, activatedAt);
                merged++;
                continue;
            }
            long progressAt = time(item, "progressAtMs", "progressAt");
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
            long lastRead = time(item, "lastReadAtMs", "lastReadAt");
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

    /** 复活后写一条归零进度，避免下一次同步把删除前的旧进度拉回来。 */
    private static void writeProgressReset(WebDav dav, Library library, Library.Book book, long at) {
        try {
            JSONObject node = progressJson(book, 0, 0, at > 0 ? at : System.currentTimeMillis());
            node.put("durChapterTitle", "");
            dav.put("bookProgress/" + fileKey(library, book) + ".json", node.toString());
        } catch (Exception ignored) {
            // 复活本身已生效，下次推送会补
        }
    }

    /** 书架匹配：本地 TXT 用文件指纹，在线书用「书源地址 + 书籍地址」，重建后仍能对上。 */
    private static Library.Book matchAny(Library library, JSONObject item) {
        Library.Book byHash = library.findByHash(str(item, "fileHash"));
        if (byHash != null) {
            return byHash;
        }
        Library.Book online = library.findOnline(str(item, "sourceUrl"), str(item, "bookUrl"));
        if (online != null) {
            return online;
        }
        String bookUrl = str(item, "bookUrl");
        String bookKey = Urls.key(bookUrl);
        if (!bookKey.isEmpty()) {
            for (Library.Book book : library.books) {
                if (bookKey.equals(Urls.key(book.bookUrl))) {
                    return book;
                }
            }
        }
        return null;
    }

    /** 其他设备新增的在线书：本机有同一书源时重新抓目录入架，失败只跳过这本书。 */
    private static Library.Book importOnline(Library library, SourceStore store, JSONObject item) {
        if (!"ONLINE".equals(str(item, "sourceType"))) {
            return null;
        }
        String bookUrl = str(item, "bookUrl");
        SourceStore.Source source = store.find(str(item, "sourceUrl"));
        if (bookUrl.isEmpty() || source == null) {
            return null;
        }
        try {
            SearchResult result = new SearchResult();
            result.title = str(item, "title");
            result.author = str(item, "author");
            result.bookUrl = bookUrl;
            BookDetail detail = new OnlineService().detail(source, result);
            return library.addOnline(source, detail);
        } catch (Exception e) {
            return null;
        }
    }

    private static int pushAll(WebDav dav, Library library) throws Exception {
        dav.ensureDir();
        int pushed = 0;
        for (Library.Book book : library.books) {
            if (book.deleted || book.progressAt <= 0 || book.chapterTitles.isEmpty()) {
                continue;
            }
            int chapter = Math.max(0, Math.min(book.chapterIndex, book.chapterTitles.size() - 1));
            Integer cached = chapter < book.chapterLengths.size() ? book.chapterLengths.get(chapter) : 0;
            int length = cached == null ? 0 : cached;
            if (length <= 0 && book.percent > 0) {
                // 章内长度未知时无法换算 durChapterPos，宁可不写也不要清空远端位点
                continue;
            }
            try {
                JSONObject node = progressJson(book, book.chapterIndex, chapterPos(book), book.progressAt);
                node.put("durChapterTitle", book.chapterTitles.get(chapter));
                dav.put("bookProgress/" + fileKey(library, book) + ".json", node.toString());
                pushed++;
            } catch (Exception ignored) {
                // 单本书失败不影响其它书与书架上传
            }
        }
        for (Library.Book book : library.books) {
            if (!book.deleted) {
                continue;
            }
            long at = Math.max(book.progressAt, book.lastReadAt);
            if (at <= 0) {
                continue;
            }
            try {
                JSONObject node = progressJson(book, 0, 0, at);
                node.put("durChapterTitle", "");
                dav.put("bookProgress/" + fileKey(library, book) + ".json", node.toString());
            } catch (Exception ignored) {
                // 单本书失败不影响其它书
            }
        }
        pushShelf(dav, library);
        return pushed;
    }

    /**
     * 上传前先读远端 shelf.json，按条目合并后再整份写入：
     * 本机没有导入成功的远端条目（另一台设备的本地书、删除墓碑）原样保留。
     */
    private static void pushShelf(WebDav dav, Library library) throws Exception {
        Map<String, JSONObject> localById = new LinkedHashMap<>();
        for (Library.Book book : library.books) {
            JSONObject item = shelfItem(book);
            localById.put(identity(item), item);
        }
        JSONObject remoteRoot = null;
        String remote = dav.get("shelf.json");
        if (remote != null) {
            try {
                remoteRoot = new JSONObject(remote);
            } catch (JSONException e) {
                remoteRoot = null;
            }
        }
        JSONArray merged = new JSONArray();
        Set<String> used = new HashSet<>();
        JSONArray remoteBooks = remoteRoot == null ? null : remoteRoot.optJSONArray("books");
        if (remoteBooks != null) {
            for (int i = 0; i < remoteBooks.length(); i++) {
                JSONObject item = remoteBooks.optJSONObject(i);
                if (item == null) {
                    continue;
                }
                String id = identity(item);
                JSONObject local = localById.get(id);
                if (local == null) {
                    merged.put(item);
                    continue;
                }
                used.add(id);
                merged.put(pick(local, item));
            }
        }
        for (Map.Entry<String, JSONObject> entry : localById.entrySet()) {
            if (!used.contains(entry.getKey())) {
                merged.put(entry.getValue());
            }
        }
        JSONObject root = new JSONObject();
        root.put("version", SHELF_VERSION);
        root.put("device", "android");
        root.put("updatedAt", iso(System.currentTimeMillis()));
        root.put("books", merged);
        dav.put("shelf.json", root.toString());
    }

    private static JSONObject shelfItem(Library.Book book) throws JSONException {
        JSONObject item = new JSONObject();
        item.put("deleted", book.deleted);
        item.put("title", nvl(book.title));
        item.put("author", nvl(book.author));
        item.put("sourceType", book.sourceType == null || book.sourceType.isEmpty()
                ? "LOCAL" : book.sourceType);
        item.put("sourceId", 0);
        item.put("sourceUrl", nvl(book.sourceUrl));
        item.put("sourceName", nvl(book.sourceName));
        item.put("bookUrl", nvl(book.bookUrl));
        item.put("fileHash", nvl(book.hash));
        item.put("chapterCount", book.chapterTitles.size());
        item.put("addedAt", book.addedAt > 0 ? iso(book.addedAt) : "");
        item.put("addedAtMs", book.addedAt);
        item.put("lastReadAt", book.lastReadAt > 0 ? iso(book.lastReadAt) : "");
        item.put("lastReadAtMs", book.lastReadAt);
        if (book.deleted) {
            item.put("deletedAtMs", Math.max(book.progressAt, book.lastReadAt));
        }
        if (book.progressAt > 0) {
            item.put("chapterIndex", book.chapterIndex);
            item.put("percent", book.percent);
            item.put("progressAt", iso(book.progressAt));
            item.put("progressAtMs", book.progressAt);
        }
        return item;
    }

    private static JSONObject pick(JSONObject local, JSONObject remote) throws JSONException {
        boolean localDeleted = local.optBoolean("deleted", false);
        boolean remoteDeleted = remote.optBoolean("deleted", false);
        long localActivity = activity(local);
        long remoteActivity = activity(remote);
        long localDeletedAt = deletedAt(local);
        long remoteDeletedAt = deletedAt(remote);
        if (localDeleted && remoteDeleted) {
            return localDeletedAt >= remoteDeletedAt ? local : remote;
        }
        if (localDeleted) {
            if (remoteActivity > 0 && localDeletedAt > 0 && remoteActivity >= localDeletedAt) {
                return remote;
            }
            return local;
        }
        if (remoteDeleted) {
            if (remoteDeletedAt > 0 && localActivity > remoteDeletedAt) {
                return local;
            }
            return remote;
        }
        return mergeActive(local, remote);
    }

    private static JSONObject mergeActive(JSONObject local, JSONObject remote) throws JSONException {
        long remoteLastRead = time(remote, "lastReadAtMs", "lastReadAt");
        if (remoteLastRead > time(local, "lastReadAtMs", "lastReadAt")) {
            copy(local, remote, "lastReadAt");
            local.put("lastReadAtMs", remoteLastRead);
        }
        long remoteProgress = time(remote, "progressAtMs", "progressAt");
        if (remoteProgress > time(local, "progressAtMs", "progressAt")) {
            copy(local, remote, "chapterIndex");
            copy(local, remote, "percent");
            copy(local, remote, "progressAt");
            local.put("progressAtMs", remoteProgress);
        }
        int remoteCount = remote.optInt("chapterCount", 0);
        if (remoteCount > local.optInt("chapterCount", 0)) {
            local.put("chapterCount", remoteCount);
        }
        long remoteAdded = time(remote, "addedAtMs", "addedAt");
        if (remoteAdded > time(local, "addedAtMs", "addedAt")) {
            copy(local, remote, "addedAt");
            local.put("addedAtMs", remoteAdded);
        }
        return local;
    }

    private static void copy(JSONObject target, JSONObject source, String field) {
        if (source.has(field) && !source.isNull(field)) {
            try {
                target.put(field, source.opt(field));
            } catch (JSONException ignored) {
                // 同名字段写入失败时保持本地值
            }
        }
    }

    private static String identity(JSONObject item) {
        String hash = str(item, "fileHash");
        if (!hash.isEmpty()) {
            return "h:" + hash;
        }
        String url = str(item, "bookUrl");
        if (!url.isEmpty()) {
            return "o:" + url;
        }
        return "t:" + str(item, "title") + "|" + str(item, "author");
    }

    private static long activity(JSONObject item) {
        return Math.max(time(item, "addedAtMs", "addedAt"),
                Math.max(time(item, "lastReadAtMs", "lastReadAt"),
                        time(item, "progressAtMs", "progressAt")));
    }

    private static long deletedAt(JSONObject item) {
        long value = item.optLong("deletedAtMs", 0);
        return value > 0 ? value : time(item, "lastReadAtMs", "lastReadAt");
    }

    private static long time(JSONObject item, String msField, String isoField) {
        long value = item.optLong(msField, 0);
        return value > 0 ? value : millis(str(item, isoField));
    }

    private static JSONObject progressJson(Library.Book book, int chapterIndex, int position, long time)
            throws JSONException {
        JSONObject node = new JSONObject();
        node.put("name", nvl(book.title));
        node.put("author", nvl(book.author));
        node.put("bookUrl", nvl(book.bookUrl));
        node.put("fileHash", nvl(book.hash));
        node.put("durChapterIndex", chapterIndex);
        node.put("durChapterPos", position);
        node.put("durChapterTime", time);
        return node;
    }

    private static int chapterPos(Library.Book book) {
        int chapter = Math.max(0, Math.min(book.chapterIndex,
                Math.max(0, book.chapterLengths.size() - 1)));
        Integer length = book.chapterLengths.isEmpty() ? 0 : book.chapterLengths.get(chapter);
        int size = length == null ? 0 : length;
        return (int) Math.round(book.percent / 10000.0 * size);
    }

    /** 进度文件索引：文件名命中不了时，再用 bookUrl/fileHash 精确对齐，避免同名串书。 */
    private static final class ProgressIndex {
        final Map<String, Library.Book> byKey = new HashMap<>();
        final Map<String, Library.Book> byHash = new HashMap<>();
        final Map<String, Library.Book> byUrl = new HashMap<>();

        Library.Book find(JSONObject node, String key) {
            String hash = str(node, "fileHash");
            if (!hash.isEmpty()) {
                Library.Book book = byHash.get(hash);
                if (book != null) {
                    return book;
                }
            }
            String url = str(node, "bookUrl");
            if (!url.isEmpty()) {
                Library.Book book = byUrl.get(url);
                if (book != null) {
                    return book;
                }
            }
            return byKey.get(key);
        }
    }

    private static ProgressIndex progressIndex(Library library) {
        Map<String, Integer> counts = new HashMap<>();
        for (Library.Book book : library.books) {
            if (book.deleted) {
                continue;
            }
            String base = fileKey(book.title, book.author);
            counts.put(base, counts.containsKey(base) ? counts.get(base) + 1 : 1);
        }
        ProgressIndex index = new ProgressIndex();
        for (Library.Book book : library.books) {
            if (book.deleted) {
                continue;
            }
            String hash = nvl(book.hash);
            if (!hash.isEmpty()) {
                if (!index.byHash.containsKey(hash)) {
                    index.byHash.put(hash, book);
                }
            }
            String url = nvl(book.bookUrl);
            if (!url.isEmpty()) {
                if (!index.byUrl.containsKey(url)) {
                    index.byUrl.put(url, book);
                }
            }
            String base = fileKey(book.title, book.author);
            if (counts.getOrDefault(base, 0) == 1) {
                if (!index.byKey.containsKey(base)) {
                    index.byKey.put(base, book);
                }
            }
            String disambiguated = base + "_" + shortId(book);
            if (!index.byKey.containsKey(disambiguated)) {
                index.byKey.put(disambiguated, book);
            }
        }
        return index;
    }

    static String fileKey(String title, String author) {
        String name = safe(title);
        String writer = safe(author);
        return writer.isEmpty() ? name : name + "_" + writer;
    }

    private static String fileKey(Library library, Library.Book book) {
        String base = fileKey(book.title, book.author);
        int count = 0;
        for (Library.Book other : library.books) {
            if (fileKey(other.title, other.author).equals(base)) {
                count++;
            }
        }
        return count > 1 ? base + "_" + shortId(book) : base;
    }

    private static String shortId(Library.Book book) {
        String seed = nvl(book.hash);
        if (seed.isEmpty()) {
            seed = nvl(book.bookUrl);
        }
        return String.format(Locale.ROOT, "%08x", seed.hashCode());
    }

    /** org.json 对 JSON null 会返回字面量 "null"，统一按空串处理。 */
    private static String str(JSONObject object, String name) {
        String value = object.optString(name, "");
        return "null".equals(value) ? "" : value;
    }

    private static String nvl(String value) {
        return value == null ? "" : value;
    }

    private static String safe(String value) {
        return (value == null ? "" : value)
                .replaceAll("[\\\\/:*?\"<>|\\r\\n\\t]", "").trim();
    }

    static String iso(long millis) {
        return LocalDateTime.ofInstant(Instant.ofEpochMilli(millis), ZoneId.systemDefault()).toString();
    }

    static long millis(String text) {
        if (text == null || text.isEmpty() || "null".equals(text)) {
            return 0;
        }
        try {
            return OffsetDateTime.parse(text).toInstant().toEpochMilli();
        } catch (RuntimeException ignored) {
            // 继续尝试无时区格式
        }
        try {
            return LocalDateTime.parse(text).atZone(ZoneId.systemDefault()).toInstant().toEpochMilli();
        } catch (RuntimeException e) {
            return 0;
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
