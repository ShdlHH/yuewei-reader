package com.yuewei.reader.application;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.yuewei.reader.domain.Book;
import com.yuewei.reader.domain.BookDetail;
import com.yuewei.reader.domain.BookSource;
import com.yuewei.reader.domain.Chapter;
import com.yuewei.reader.domain.ReadingProgress;
import com.yuewei.reader.domain.SearchResult;
import com.yuewei.reader.infrastructure.db.BookDao;
import com.yuewei.reader.infrastructure.db.BookSourceDao;
import com.yuewei.reader.infrastructure.db.ChapterDao;
import com.yuewei.reader.infrastructure.db.ProgressDao;
import com.yuewei.reader.infrastructure.sync.WebDavClient;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 两阶段同步：进度写成 Legado 兼容的 bookProgress JSON；
 * 书架与阅读设置上传快照，并在启动时反向合并远端较新的内容。
 *
 * <p>推送书架前会先读取远端 shelf.json 并按条目合并，避免某一端导入失败、
 * 离线或未拉取时把远端条目（含删除墓碑）整份覆盖掉。</p>
 */
public class SyncService {

    /** 同步结果：skipped 表示未配置或未启用。 */
    public record Result(boolean skipped, int pushed, int applied, String message) {
        public static Result skipped(String message) {
            return new Result(true, 0, 0, message);
        }
    }

    /** shelf.json 结构版本：2 增加毫秒时间字段，仍保留原有 ISO 字段供旧客户端读取。 */
    private static final int SHELF_VERSION = 2;

    private final SettingsService settings;
    private final BookDao bookDao;
    private final ChapterDao chapterDao;
    private final ProgressDao progressDao;
    private final BookSourceDao sourceDao;
    private final OnlineBookService onlineBookService;
    private final ObjectMapper json = new ObjectMapper();

    public SyncService(SettingsService settings, BookDao bookDao, ChapterDao chapterDao,
                       ProgressDao progressDao, BookSourceDao sourceDao,
                       OnlineBookService onlineBookService) {
        this.settings = settings;
        this.bookDao = bookDao;
        this.chapterDao = chapterDao;
        this.progressDao = progressDao;
        this.sourceDao = sourceDao;
        this.onlineBookService = onlineBookService;
    }

    public boolean configured() {
        return settings.syncEnabled() && !settings.syncUrl().isBlank();
    }

    private WebDavClient client() {
        return new WebDavClient(settings.syncUrl(), settings.syncDir(),
                settings.syncAccount(), settings.syncPassword(), settings.timeout());
    }

    /** 启动时调用：远端进度、书架与设置中较新的部分合并到本地。 */
    public synchronized Result pull() throws IOException {
        if (!configured()) {
            return Result.skipped("未启用 WebDAV 同步");
        }
        WebDavClient dav = client();
        int applied;
        try {
            applied = pullProgress(dav);
        } catch (IOException e) {
            settings.saveSyncResult("拉取失败：" + e.getMessage());
            throw e;
        }
        int merged = pullShelf(dav);
        StringBuilder message = new StringBuilder();
        if (applied > 0) {
            message.append("拉取 ").append(applied).append(" 本进度");
        }
        if (merged > 0) {
            message.append(message.length() > 0 ? "；" : "").append("合并 ").append(merged).append(" 本书架");
        }
        if (message.length() == 0) {
            message.append("没有新的远端数据");
        }
        settings.saveSyncResult(message.toString());
        return new Result(false, 0, applied, message.toString());
    }

    /** 设置页“立即同步”：同一把锁内先拉后推，避免与启动拉取/退出推送交错。 */
    public synchronized Result syncNow() throws IOException {
        if (!configured()) {
            return Result.skipped("未启用 WebDAV 同步");
        }
        Result pulled = pull();
        Result pushed = pushAll();
        String message = pulled.message() + "；" + pushed.message();
        settings.saveSyncResult(message);
        return new Result(false, pushed.pushed(), pulled.applied(), message);
    }

    private int pullProgress(WebDavClient dav) throws IOException {
        List<WebDavClient.RemoteFile> files = dav.list("bookProgress");
        ProgressIndex index = bookIndex();
        int applied = 0;
        for (WebDavClient.RemoteFile file : files) {
            if (!file.name().endsWith(".json")) {
                continue;
            }
            byte[] data = dav.get(file.path());
            if (data == null) {
                continue;
            }
            JsonNode node;
            try {
                node = json.readTree(new String(data, StandardCharsets.UTF_8));
            } catch (IOException e) {
                continue;
            }
            if (node == null || !node.isObject()) {
                continue;
            }
            String key = file.name().substring(0, file.name().length() - ".json".length());
            Book book = index.find(node, key);
            if (book == null) {
                continue;
            }
            if (applyRemote(book, node)) {
                applied++;
            }
        }
        return applied;
    }

    /** 退出阅读、切章或手动同步时调用：上传进度、书架与设置。 */
    public synchronized Result pushAll() throws IOException {
        if (!configured()) {
            return Result.skipped("未启用 WebDAV 同步");
        }
        WebDavClient dav = client();
        dav.ensureDir();
        int pushed = 0;
        int failed = 0;
        for (Book book : bookDao.findAll()) {
            try {
                if (pushBookProgress(dav, book)) {
                    pushed++;
                }
            } catch (Exception e) {
                // 单本书失败不影响其它书与书架上传
                failed++;
            }
        }
        for (Book book : bookDao.findAllWithDeleted()) {
            if (!book.deleted) {
                continue;
            }
            long deletedAt = millis(book.lastReadAt);
            if (deletedAt <= 0) {
                // 老库墓碑没有删除时间，写一个“现在”的时间反而会压掉别处的真实进度
                continue;
            }
            try {
                pushProgressTombstone(dav, book, deletedAt);
            } catch (IOException e) {
                failed++;
            }
        }
        pushShelf(dav);
        String message = "上传 " + pushed + " 本进度、书架"
                + (failed > 0 ? "（" + failed + " 本失败）" : "");
        settings.saveSyncResult(message);
        return new Result(false, pushed, 0, message);
    }

    /** 只看一本书的进度，切章时调用，避免整库遍历。 */
    public synchronized void pushBookProgress(Book book) throws IOException {
        if (!configured() || book == null) {
            return;
        }
        WebDavClient dav = client();
        dav.ensureDir();
        if (pushBookProgress(dav, book)) {
            settings.saveSyncResult("已上传《" + book.title + "》进度");
        }
    }

    private boolean pushBookProgress(WebDavClient dav, Book book) throws IOException {
        if (book.deleted) {
            return false;
        }
        ReadingProgress progress = progressDao.findByBook(book.id);
        if (progress == null) {
            return false;
        }
        Chapter chapter = chapterDao.findByIndex(book.id, progress.chapterIndex);
        int percent = Math.max(0, Math.min(10000, progress.charOffset));
        if (percent > 0 && (chapter == null || chapter.wordCount <= 0)) {
            // 章节字数未知时无法换算 durChapterPos，宁可不写也不要清空远端位点
            return false;
        }
        ObjectNode node = progressJson(book, progress.chapterIndex,
                chapterPos(chapter, percent), millis(progress.updatedAt));
        node.put("durChapterTitle", chapter == null ? "" : chapter.title);
        byte[] body = json.writeValueAsBytes(node);
        dav.put("bookProgress/" + fileKey(book) + ".json", body, "application/json; charset=utf-8");
        return true;
    }

    /** 删除/复活后写入“进度归零”标记，防止残留的旧 bookProgress 在下次同步把进度拉回来。 */
    private void pushProgressTombstone(WebDavClient dav, Book book, long at) throws IOException {
        long time = at > 0 ? at : System.currentTimeMillis();
        ObjectNode node = progressJson(book, 0, 0, time);
        node.put("durChapterTitle", "");
        dav.put("bookProgress/" + fileKey(book) + ".json",
                json.writeValueAsBytes(node), "application/json; charset=utf-8");
    }

    private ObjectNode progressJson(Book book, int chapterIndex, int position, long time) {
        ObjectNode node = json.createObjectNode();
        node.put("name", book.title == null ? "" : book.title);
        node.put("author", book.author == null ? "" : book.author);
        node.put("bookUrl", book.bookUrl == null ? "" : book.bookUrl);
        node.put("fileHash", book.fileHash == null ? "" : book.fileHash);
        node.put("durChapterIndex", chapterIndex);
        node.put("durChapterPos", position);
        node.put("durChapterTime", time);
        return node;
    }

    /**
     * 上传前先读远端 shelf.json，按条目合并后再整份写入：
     * 本地没有的远端条目（另一台设备新增的本地书、删除墓碑）原样保留。
     */
    private void pushShelf(WebDavClient dav) throws IOException {
        List<ObjectNode> localItems = new ArrayList<>();
        for (Book book : bookDao.findAllWithDeleted()) {
            localItems.add(shelfItem(book));
        }
        JsonNode remoteRoot = readRemote(dav, "shelf.json");
        ObjectNode root = json.createObjectNode();
        root.put("version", SHELF_VERSION);
        root.put("device", "desktop");
        root.put("updatedAt", LocalDateTime.now().toString());
        ArrayNode books = root.putArray("books");
        for (JsonNode item : mergeShelf(remoteRoot, localItems)) {
            books.add(item);
        }
        dav.put("shelf.json", json.writeValueAsBytes(root), "application/json; charset=utf-8");
    }

    private ObjectNode shelfItem(Book book) {
        ObjectNode item = json.createObjectNode();
        item.put("deleted", book.deleted);
        item.put("title", book.title == null ? "" : book.title);
        item.put("author", book.author == null ? "" : book.author);
        item.put("sourceType", book.sourceType.name());
        item.put("sourceId", book.sourceId == null ? 0 : book.sourceId);
        BookSource source = book.sourceId == null ? null : sourceDao.findById(book.sourceId);
        item.put("sourceUrl", source == null ? "" : source.url);
        item.put("sourceName", source == null ? "" : source.name);
        item.put("bookUrl", book.bookUrl == null ? "" : book.bookUrl);
        item.put("fileHash", book.fileHash == null ? "" : book.fileHash);
        item.put("chapterCount", book.chapterCount);
        item.put("addedAt", book.addedAt == null ? "" : book.addedAt);
        item.put("addedAtMs", millis(book.addedAt));
        item.put("lastReadAt", book.lastReadAt == null ? "" : book.lastReadAt);
        item.put("lastReadAtMs", millis(book.lastReadAt));
        if (book.deleted) {
            item.put("deletedAtMs", millis(book.lastReadAt));
        }
        ReadingProgress progress = progressDao.findByBook(book.id);
        if (progress != null) {
            item.put("chapterIndex", progress.chapterIndex);
            item.put("percent", progress.charOffset);
            item.put("progressAt", progress.updatedAt == null ? "" : progress.updatedAt);
            item.put("progressAtMs", millis(progress.updatedAt));
        }
        return item;
    }

    /** 读取远端 JSON；文件不存在返回 null，内容损坏也按不存在处理，但网络/认证异常向上抛。 */
    private JsonNode readRemote(WebDavClient dav, String path) throws IOException {
        byte[] data = dav.get(path);
        if (data == null) {
            return null;
        }
        try {
            JsonNode node = json.readTree(new String(data, StandardCharsets.UTF_8));
            return node != null && node.isObject() ? node : null;
        } catch (IOException e) {
            return null;
        }
    }

    private List<JsonNode> mergeShelf(JsonNode remoteRoot, List<ObjectNode> localItems) {
        Map<String, ObjectNode> localById = new LinkedHashMap<>();
        for (ObjectNode item : localItems) {
            localById.put(identity(item), item);
        }
        List<JsonNode> result = new ArrayList<>();
        Set<String> used = new HashSet<>();
        JsonNode remoteBooks = remoteRoot == null ? null : remoteRoot.path("books");
        if (remoteBooks != null && remoteBooks.isArray()) {
            for (JsonNode remote : remoteBooks) {
                if (!remote.isObject()) {
                    continue;
                }
                String id = identity(remote);
                ObjectNode local = localById.get(id);
                if (local == null) {
                    result.add(remote);
                    continue;
                }
                used.add(id);
                result.add(pick(local, remote));
            }
        }
        for (ObjectNode local : localItems) {
            if (!used.contains(identity(local))) {
                result.add(local);
            }
        }
        return result;
    }

    private JsonNode pick(ObjectNode local, JsonNode remote) {
        boolean localDeleted = local.path("deleted").asBoolean(false);
        boolean remoteDeleted = remote.path("deleted").asBoolean(false);
        long localActivity = activity(local);
        long remoteActivity = activity(remote);
        long localDeletedAt = deletedAt(local);
        long remoteDeletedAt = deletedAt(remote);
        if (localDeleted && remoteDeleted) {
            return localDeletedAt >= remoteDeletedAt ? local : remote;
        }
        if (localDeleted) {
            // 远端在本地删除之后仍有阅读/重新入架活动时，以远端为准
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

    private JsonNode mergeActive(ObjectNode local, JsonNode remote) {
        long remoteLastRead = time(remote, "lastReadAtMs", "lastReadAt");
        if (remoteLastRead > time(local, "lastReadAtMs", "lastReadAt")) {
            copyField(local, remote, "lastReadAt");
            local.put("lastReadAtMs", remoteLastRead);
        }
        long remoteProgress = time(remote, "progressAtMs", "progressAt");
        if (remoteProgress > time(local, "progressAtMs", "progressAt")) {
            copyField(local, remote, "chapterIndex");
            copyField(local, remote, "percent");
            copyField(local, remote, "progressAt");
            local.put("progressAtMs", remoteProgress);
        }
        int remoteCount = remote.path("chapterCount").asInt(0);
        if (remoteCount > local.path("chapterCount").asInt(0)) {
            local.put("chapterCount", remoteCount);
        }
        long remoteAdded = time(remote, "addedAtMs", "addedAt");
        if (remoteAdded > time(local, "addedAtMs", "addedAt")) {
            copyField(local, remote, "addedAt");
            local.put("addedAtMs", remoteAdded);
        }
        return local;
    }

    private static void copyField(ObjectNode target, JsonNode source, String field) {
        JsonNode value = source.get(field);
        if (value != null && !value.isNull()) {
            target.set(field, value);
        }
    }

    private static String identity(JsonNode item) {
        String hash = text(item, "fileHash");
        if (!hash.isEmpty()) {
            return "h:" + hash;
        }
        String url = text(item, "bookUrl");
        if (!url.isEmpty()) {
            return "o:" + url;
        }
        return "t:" + text(item, "title") + "|" + text(item, "author");
    }

    private static long activity(JsonNode item) {
        return Math.max(time(item, "addedAtMs", "addedAt"),
                Math.max(time(item, "lastReadAtMs", "lastReadAt"),
                        time(item, "progressAtMs", "progressAt")));
    }

    private static long deletedAt(JsonNode item) {
        long value = item.path("deletedAtMs").asLong(0);
        return value > 0 ? value : time(item, "lastReadAtMs", "lastReadAt");
    }

    private static long time(JsonNode item, String msField, String isoField) {
        long value = item.path(msField).asLong(0);
        return value > 0 ? value : millis(text(item, isoField));
    }

    private boolean applyRemote(Book book, JsonNode node) {
        int remoteChapter = Math.max(0, node.path("durChapterIndex").asInt(0));
        int remotePos = Math.max(0, node.path("durChapterPos").asInt(0));
        long remoteTime = node.path("durChapterTime").asLong(0);
        ReadingProgress local = progressDao.findByBook(book.id);
        if (local != null && millis(local.updatedAt) >= remoteTime) {
            return false;
        }
        if (book.chapterCount > 0) {
            remoteChapter = Math.max(0, Math.min(book.chapterCount - 1, remoteChapter));
        }
        Chapter chapter = chapterDao.findByIndex(book.id, remoteChapter);
        int percent;
        if (chapter != null && chapter.wordCount > 0) {
            percent = (int) Math.round(Math.min(1, remotePos / (double) chapter.wordCount) * 10000);
        } else if (remotePos <= 0) {
            percent = 0;
        } else {
            // 本地还没有章节字数，无法换算；落 0 会破坏远端位点，直接跳过
            return false;
        }
        String updatedAt = remoteTime > 0
                ? iso(remoteTime)
                : LocalDateTime.now().toString();
        progressDao.saveRemote(book.id, remoteChapter, percent, updatedAt);
        return true;
    }

    /** 反向合并书架：已有书更新最近阅读时间与进度，在线书缺失时按书源重新入架。 */
    private int pullShelf(WebDavClient dav) throws IOException {
        byte[] data = dav.get("shelf.json");
        if (data == null) {
            return 0;
        }
        JsonNode root;
        try {
            root = json.readTree(new String(data, StandardCharsets.UTF_8));
        } catch (IOException e) {
            return 0;
        }
        JsonNode books = root.path("books");
        if (!books.isArray()) {
            return 0;
        }
        int merged = 0;
        for (JsonNode item : books) {
            if (!item.isObject()) {
                continue;
            }
            Book local = matchAny(item);
            long remoteActivity = activity(item);
            long remoteDeletedAt = deletedAt(item);
            if (item.path("deleted").asBoolean(false)) {
                if (local == null || local.deleted) {
                    continue;
                }
                // 远端删除之后本地仍有阅读活动时，保留本地，交给推送合并
                if (remoteDeletedAt > 0 && localActivity(local) > remoteDeletedAt) {
                    continue;
                }
                bookDao.markDeleted(local.id);
                chapterDao.deleteByBook(local.id);
                progressDao.deleteByBook(local.id);
                merged++;
                continue;
            }
            if (local != null && local.deleted) {
                // 本地有墓碑：只有远端在删除之后重新入架（addedAt/进度/最近阅读更新）才复活
                long localDeletedAt = millis(local.lastReadAt);
                if (localDeletedAt > 0 && remoteActivity < localDeletedAt) {
                    continue;
                }
                Book revived = importOnline(item);
                if (revived == null) {
                    // 书源不可用：保留墓碑，推送合并时不会抹掉远端这本书
                    continue;
                }
                resetRemoteProgress(dav, revived, remoteActivity);
                merged++;
                continue;
            }
            if (local == null) {
                Book added = importOnline(item);
                if (added != null) {
                    mergeMeta(added, item);
                    merged++;
                }
                continue;
            }
            boolean changed = mergeMeta(local, item);
            changed |= mergeShelfProgress(local, item);
            if (changed) {
                merged++;
            }
        }
        return merged;
    }

    /** 复活后写一条归零进度，避免下一次同步把删除前的旧进度拉回来。 */
    private void resetRemoteProgress(WebDavClient dav, Book book, long activationMillis) {
        long at = activationMillis > 0 ? activationMillis : System.currentTimeMillis();
        try {
            pushProgressTombstone(dav, book, at);
        } catch (IOException ignored) {
            // 复活本身已生效，进度文件下次推送会再补
        }
    }

    /** 匹配包含墓碑的记录，避免已删除的书被重新入架。 */
    private Book matchAny(JsonNode item) {
        String hash = text(item, "fileHash");
        String bookUrl = text(item, "bookUrl");
        List<Book> all = bookDao.findAllWithDeleted();
        if (!hash.isEmpty()) {
            for (Book book : all) {
                if (hash.equals(book.fileHash)) {
                    return book;
                }
            }
        }
        if (!bookUrl.isEmpty()) {
            for (Book book : all) {
                if (bookUrl.equals(book.bookUrl)) {
                    return book;
                }
            }
        }
        return null;
    }

    /** 书源 ID 在不同设备上不通用，优先按书源地址匹配；地址存在但本机没有时不再猜 ID。 */
    private BookSource resolveSource(JsonNode item) {
        String sourceUrl = text(item, "sourceUrl");
        if (!sourceUrl.isEmpty()) {
            return sourceDao.findByUrl(sourceUrl);
        }
        long sourceId = item.path("sourceId").asLong(0);
        return sourceId > 0 ? sourceDao.findById(sourceId) : null;
    }

    private boolean mergeMeta(Book local, JsonNode item) {
        boolean changed = false;
        long lastRead = time(item, "lastReadAtMs", "lastReadAt");
        if (lastRead > 0 && millis(local.lastReadAt) < lastRead) {
            String text = text(item, "lastReadAt");
            bookDao.updateLastReadAt(local.id, text.isEmpty() ? iso(lastRead) : text);
            changed = true;
        }
        int remoteCount = item.path("chapterCount").asInt(0);
        if (remoteCount > local.chapterCount) {
            bookDao.updateChapterCount(local.id, remoteCount);
            changed = true;
        }
        return changed;
    }

    private boolean mergeShelfProgress(Book local, JsonNode item) {
        if (!item.hasNonNull("chapterIndex")) {
            return false;
        }
        long progressTime = time(item, "progressAtMs", "progressAt");
        if (progressTime <= 0) {
            return false;
        }
        ReadingProgress current = progressDao.findByBook(local.id);
        if (current != null && millis(current.updatedAt) >= progressTime) {
            return false;
        }
        int chapter = Math.max(0, item.path("chapterIndex").asInt(0));
        if (local.chapterCount > 0) {
            chapter = Math.min(chapter, local.chapterCount - 1);
        }
        int percent = Math.max(0, Math.min(10000, item.path("percent").asInt(0)));
        String text = text(item, "progressAt");
        progressDao.saveRemote(local.id, chapter, percent, text.isEmpty() ? iso(progressTime) : text);
        return true;
    }

    /** 其他设备新增的在线书：按书源重新抓目录并入架；失败返回 null，只跳过这本书。 */
    private Book importOnline(JsonNode item) {
        String type = text(item, "sourceType");
        if (!type.isEmpty() && !"ONLINE".equals(type)) {
            return null;
        }
        String bookUrl = text(item, "bookUrl");
        if (bookUrl.isEmpty()) {
            return null;
        }
        BookSource source = resolveSource(item);
        if (source == null) {
            return null;
        }
        try {
            SearchResult result = new SearchResult();
            result.title = text(item, "title");
            result.author = text(item, "author");
            result.bookUrl = bookUrl;
            result.sourceId = source.id;
            result.sourceName = source.name;
            BookDetail detail = onlineBookService.fetchDetail(source, result);
            Book added = onlineBookService.addToShelf(source, detail);
            mergeShelfProgress(added, item);
            return added;
        } catch (Exception e) {
            return null;
        }
    }

    private static String text(JsonNode node, String field) {
        JsonNode value = node == null ? null : node.get(field);
        return value == null || value.isNull() ? "" : value.asText("");
    }

    private static String iso(long millis) {
        return LocalDateTime.ofInstant(Instant.ofEpochMilli(millis), ZoneId.systemDefault()).toString();
    }

    private static long millis(String value) {
        if (value == null || value.isBlank()) {
            return 0;
        }
        try {
            return OffsetDateTime.parse(value).toInstant().toEpochMilli();
        } catch (RuntimeException ignored) {
            // 继续尝试无时区格式
        }
        try {
            return LocalDateTime.parse(value).atZone(ZoneId.systemDefault()).toInstant().toEpochMilli();
        } catch (RuntimeException e) {
            return 0;
        }
    }

    private long localActivity(Book book) {
        long progress = 0;
        ReadingProgress reading = progressDao.findByBook(book.id);
        if (reading != null) {
            progress = millis(reading.updatedAt);
        }
        return Math.max(millis(book.addedAt), Math.max(millis(book.lastReadAt), progress));
    }

    /** 进度文件索引：书名_作者 命中不了时，再用 bookUrl/fileHash 精确对齐，避免同名串书。 */
    private record ProgressIndex(Map<String, Book> byKey, Map<String, Book> byHash,
                                 Map<String, Book> byUrl) {
        Book find(JsonNode node, String key) {
            String hash = text(node, "fileHash");
            if (!hash.isEmpty()) {
                Book book = byHash.get(hash);
                if (book != null) {
                    return book;
                }
            }
            String url = text(node, "bookUrl");
            if (!url.isEmpty()) {
                Book book = byUrl.get(url);
                if (book != null) {
                    return book;
                }
            }
            return byKey.get(key);
        }
    }

    private ProgressIndex bookIndex() {
        List<Book> books = bookDao.findAll();
        Map<String, Integer> counts = new HashMap<>();
        for (Book book : books) {
            counts.merge(baseKey(book), 1, Integer::sum);
        }
        Map<String, Book> byKey = new HashMap<>();
        Map<String, Book> byHash = new HashMap<>();
        Map<String, Book> byUrl = new HashMap<>();
        for (Book book : books) {
            if (book.fileHash != null && !book.fileHash.isBlank()) {
                byHash.putIfAbsent(book.fileHash, book);
            }
            if (book.bookUrl != null && !book.bookUrl.isBlank()) {
                byUrl.putIfAbsent(book.bookUrl, book);
            }
            String base = baseKey(book);
            if (counts.getOrDefault(base, 0) == 1) {
                byKey.putIfAbsent(base, book);
            }
            byKey.putIfAbsent(base + "_" + shortId(book), book);
        }
        return new ProgressIndex(byKey, byHash, byUrl);
    }

    /** 书名_作者；同一本书在不同设备上生成同一个文件名。 */
    private String fileKey(Book book) {
        String base = baseKey(book);
        int count = 0;
        for (Book other : bookDao.findAllWithDeleted()) {
            if (baseKey(other).equals(base)) {
                count++;
            }
        }
        return count > 1 ? base + "_" + shortId(book) : base;
    }

    private static String baseKey(Book book) {
        String title = safe(book.title == null ? "" : book.title);
        String author = safe(book.author == null ? "" : book.author);
        return author.isEmpty() ? title : title + "_" + author;
    }

    /** 同名同作者时用 bookUrl/fileHash 派生稳定后缀，两端算出的结果一致。 */
    private static String shortId(Book book) {
        String seed = book.fileHash != null && !book.fileHash.isBlank()
                ? book.fileHash
                : (book.bookUrl == null ? "" : book.bookUrl);
        return String.format(java.util.Locale.ROOT, "%08x", seed.hashCode());
    }

    /** 去掉文件名非法字符，保留中文；与 Legado 的文件名规则大体一致。 */
    private static String safe(String value) {
        return value.replaceAll("[\\\\/:*?\"<>|\\r\\n\\t]", "").trim();
    }

    private static int chapterPos(Chapter chapter, int percent) {
        if (chapter == null || chapter.wordCount <= 0) {
            return 0;
        }
        return (int) Math.round(Math.max(0, Math.min(10000, percent)) / 10000.0 * chapter.wordCount);
    }
}
