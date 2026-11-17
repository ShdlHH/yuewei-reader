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
import java.time.ZoneId;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 两阶段同步：进度写成 Legado 兼容的 bookProgress JSON；
 * 书架与阅读设置上传快照，并在启动时反向合并远端较新的内容。
 */
public class SyncService {

    /** 同步结果：skipped 表示未配置或未启用。 */
    public record Result(boolean skipped, int pushed, int applied, String message) {
        public static Result skipped(String message) {
            return new Result(true, 0, 0, message);
        }
    }

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
    public Result pull() throws IOException {
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
        boolean settingsApplied = pullSettings(dav);
        StringBuilder message = new StringBuilder();
        if (applied > 0) {
            message.append("拉取 ").append(applied).append(" 本进度");
        }
        if (merged > 0) {
            message.append(message.length() > 0 ? "；" : "").append("合并 ").append(merged).append(" 本书架");
        }
        if (settingsApplied) {
            message.append(message.length() > 0 ? "；" : "").append("应用远端设置");
        }
        if (message.length() == 0) {
            message.append("没有新的远端数据");
        }
        settings.saveSyncResult(message.toString());
        return new Result(false, 0, applied, message.toString());
    }

    private int pullProgress(WebDavClient dav) throws IOException {
        List<WebDavClient.RemoteFile> files = dav.list("bookProgress");
        Map<String, Book> index = bookIndex();
        int applied = 0;
        for (WebDavClient.RemoteFile file : files) {
            if (!file.name().endsWith(".json")) {
                continue;
            }
            String key = file.name().substring(0, file.name().length() - ".json".length());
            Book book = index.get(key);
            if (book == null) {
                continue;
            }
            byte[] data = dav.get(file.path());
            if (data == null) {
                continue;
            }
            if (applyRemote(book, data)) {
                applied++;
            }
        }
        return applied;
    }

    /** 退出阅读、切章或手动同步时调用：上传进度、书架与设置。 */
    public Result pushAll() throws IOException {
        if (!configured()) {
            return Result.skipped("未启用 WebDAV 同步");
        }
        WebDavClient dav = client();
        dav.ensureDir();
        int pushed = 0;
        for (Book book : bookDao.findAll()) {
            if (pushBookProgress(dav, book)) {
                pushed++;
            }
        }
        pushShelf(dav);
        pushSettings(dav);
        String message = "上传 " + pushed + " 本进度、书架与设置";
        settings.saveSyncResult(message);
        return new Result(false, pushed, 0, message);
    }

    /** 只看一本书的进度，切章时调用，避免整库遍历。 */
    public void pushBookProgress(Book book) throws IOException {
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
        ObjectNode node = json.createObjectNode();
        node.put("name", book.title == null ? "" : book.title);
        node.put("author", book.author == null ? "" : book.author);
        node.put("durChapterIndex", progress.chapterIndex);
        node.put("durChapterPos", chapterPos(chapter, progress.charOffset));
        node.put("durChapterTitle", chapter == null ? "" : chapter.title);
        node.put("durChapterTime", millis(progress.updatedAt));
        byte[] body = json.writeValueAsBytes(node);
        dav.put("bookProgress/" + fileKey(book) + ".json", body, "application/json; charset=utf-8");
        return true;
    }

    private void pushShelf(WebDavClient dav) throws IOException {
        ObjectNode root = json.createObjectNode();
        root.put("version", 1);
        root.put("device", "desktop");
        root.put("updatedAt", LocalDateTime.now().toString());
        ArrayNode books = root.putArray("books");
        for (Book book : bookDao.findAllWithDeleted()) {
            ObjectNode item = books.addObject();
            item.put("deleted", book.deleted);
            item.put("title", book.title);
            item.put("author", book.author);
            item.put("sourceType", book.sourceType.name());
            item.put("sourceId", book.sourceId == null ? 0 : book.sourceId);
            BookSource source = book.sourceId == null ? null : sourceDao.findById(book.sourceId);
            item.put("sourceUrl", source == null ? "" : source.url);
            item.put("sourceName", source == null ? "" : source.name);
            item.put("bookUrl", book.bookUrl);
            item.put("fileHash", book.fileHash);
            item.put("chapterCount", book.chapterCount);
            item.put("addedAt", book.addedAt);
            item.put("lastReadAt", book.lastReadAt);
            ReadingProgress progress = progressDao.findByBook(book.id);
            if (progress != null) {
                item.put("chapterIndex", progress.chapterIndex);
                item.put("percent", progress.charOffset);
                item.put("progressAt", progress.updatedAt);
            }
        }
        dav.put("shelf.json", json.writeValueAsBytes(root), "application/json; charset=utf-8");
    }

    private void pushSettings(WebDavClient dav) throws IOException {
        ObjectNode root = json.createObjectNode();
        root.put("updatedAt", LocalDateTime.now().toString());
        ObjectNode values = root.putObject("values");
        values.put(SettingsService.FONT_SIZE, String.valueOf(settings.fontSize()));
        values.put(SettingsService.LINE_SPACING, String.valueOf(settings.lineSpacing()));
        values.put(SettingsService.FONT_FAMILY, settings.fontFamily());
        values.put(SettingsService.THEME, settings.theme());
        values.put(SettingsService.TOC_WIDTH,
                String.valueOf(settings.getInt(SettingsService.TOC_WIDTH, 270)));
        values.put(SettingsService.PUNCT_ENABLED, String.valueOf(settings.punctEnabled()));
        values.put(SettingsService.PUNCT_DIALOGUE, String.valueOf(settings.punctDialogue()));
        values.put(SettingsService.PUNCT_STOP, String.valueOf(settings.punctStop()));
        values.put(SettingsService.PUNCT_ASK, String.valueOf(settings.punctAsk()));
        values.put(SettingsService.PUNCT_EXCL, String.valueOf(settings.punctExcl()));
        values.put(SettingsService.PUNCT_ELLIPSIS, String.valueOf(settings.punctEllipsis()));
        values.put(SettingsService.PUNCT_PAREN, String.valueOf(settings.punctParen()));
        values.put(SettingsService.PUNCT_DIALOGUE_COLOR, settings.punctColor(
                SettingsService.PUNCT_DIALOGUE_COLOR, SettingsService.DEFAULT_PUNCT_DIALOGUE_COLOR));
        values.put(SettingsService.PUNCT_STOP_COLOR, settings.punctColor(
                SettingsService.PUNCT_STOP_COLOR, SettingsService.DEFAULT_PUNCT_STOP_COLOR));
        values.put(SettingsService.PUNCT_ASK_COLOR, settings.punctColor(
                SettingsService.PUNCT_ASK_COLOR, SettingsService.DEFAULT_PUNCT_ASK_COLOR));
        values.put(SettingsService.PUNCT_EXCL_COLOR, settings.punctColor(
                SettingsService.PUNCT_EXCL_COLOR, SettingsService.DEFAULT_PUNCT_EXCL_COLOR));
        values.put(SettingsService.PUNCT_ELLIPSIS_COLOR, settings.punctColor(
                SettingsService.PUNCT_ELLIPSIS_COLOR, SettingsService.DEFAULT_PUNCT_ELLIPSIS_COLOR));
        values.put(SettingsService.PUNCT_PAREN_COLOR, settings.punctColor(
                SettingsService.PUNCT_PAREN_COLOR, SettingsService.DEFAULT_PUNCT_PAREN_COLOR));
        dav.put("settings.json", json.writeValueAsBytes(root), "application/json; charset=utf-8");
    }

    private boolean applyRemote(Book book, byte[] data) {
        JsonNode node;
        try {
            node = json.readTree(new String(data, StandardCharsets.UTF_8));
        } catch (IOException e) {
            return false;
        }
        int remoteChapter = node.path("durChapterIndex").asInt(0);
        int remotePos = node.path("durChapterPos").asInt(0);
        long remoteTime = node.path("durChapterTime").asLong(0);
        ReadingProgress local = progressDao.findByBook(book.id);
        if (local != null && millis(local.updatedAt) >= remoteTime) {
            return false;
        }
        if (book.chapterCount > 0) {
            remoteChapter = Math.max(0, Math.min(book.chapterCount - 1, remoteChapter));
        } else {
            remoteChapter = Math.max(0, remoteChapter);
        }
        int percent = 0;
        Chapter chapter = chapterDao.findByIndex(book.id, remoteChapter);
        if (chapter != null && chapter.wordCount > 0 && remotePos > 0) {
            percent = (int) Math.round(Math.min(1, remotePos / (double) chapter.wordCount) * 10000);
        }
        String updatedAt = remoteTime > 0
                ? LocalDateTime.ofInstant(Instant.ofEpochMilli(remoteTime), ZoneId.systemDefault()).toString()
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
            Book local = matchAny(item);
            boolean remoteDeleted = item.path("deleted").asBoolean(false);
            if (remoteDeleted) {
                if (local != null && !local.deleted) {
                    bookDao.markDeleted(local.id);
                    chapterDao.deleteByBook(local.id);
                    progressDao.deleteByBook(local.id);
                    merged++;
                }
                continue;
            }
            if (local != null && local.deleted) {
                continue;
            }
            if (local == null) {
                if (importOnline(item)) {
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

    /** 匹配包含墓碑的记录，避免已删除的书被重新入架。 */
    private Book matchAny(JsonNode item) {
        String hash = text(item, "fileHash");
        String bookUrl = text(item, "bookUrl");
        BookSource source = bookUrl.isEmpty() ? null : resolveSource(item);
        for (Book book : bookDao.findAllWithDeleted()) {
            if (!hash.isEmpty() && hash.equals(book.fileHash)) {
                return book;
            }
            if (source != null && bookUrl.equals(book.bookUrl)
                    && book.sourceId != null && book.sourceId == source.id) {
                return book;
            }
        }
        return null;
    }

    /** 书源 ID 在不同设备上不通用，优先按书源地址匹配，旧数据才退回本地 ID。 */
    private BookSource resolveSource(JsonNode item) {
        String sourceUrl = text(item, "sourceUrl");
        if (!sourceUrl.isEmpty()) {
            BookSource byUrl = sourceDao.findByUrl(sourceUrl);
            if (byUrl != null) {
                return byUrl;
            }
        }
        long sourceId = item.path("sourceId").asLong(0);
        return sourceId > 0 ? sourceDao.findById(sourceId) : null;
    }

    private boolean mergeMeta(Book local, JsonNode item) {
        boolean changed = false;
        String lastRead = text(item, "lastReadAt");
        if (!lastRead.isEmpty() && (local.lastReadAt == null || local.lastReadAt.compareTo(lastRead) < 0)) {
            bookDao.updateLastReadAt(local.id, lastRead);
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
        long progressTime = millis(text(item, "progressAt"));
        ReadingProgress current = progressDao.findByBook(local.id);
        if (current != null && millis(current.updatedAt) >= progressTime) {
            return false;
        }
        int chapter = Math.max(0, item.path("chapterIndex").asInt(0));
        if (local.chapterCount > 0) {
            chapter = Math.min(chapter, local.chapterCount - 1);
        }
        int percent = Math.max(0, Math.min(10000, item.path("percent").asInt(0)));
        progressDao.saveRemote(local.id, chapter, percent, progressTime > 0
                ? iso(progressTime) : LocalDateTime.now().toString());
        return true;
    }

    /** 手机端新增的在线书：按书源重新抓目录并入架；失败只跳过这本书。 */
    private boolean importOnline(JsonNode item) {
        String type = text(item, "sourceType");
        if (!type.isEmpty() && !"ONLINE".equals(type)) {
            return false;
        }
        String bookUrl = text(item, "bookUrl");
        if (bookUrl.isEmpty()) {
            return false;
        }
        BookSource source = resolveSource(item);
        if (source == null) {
            return false;
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
            return true;
        } catch (Exception e) {
            return false;
        }
    }

    private boolean pullSettings(WebDavClient dav) throws IOException {
        byte[] data = dav.get("settings.json");
        if (data == null) {
            return false;
        }
        JsonNode root;
        try {
            root = json.readTree(new String(data, StandardCharsets.UTF_8));
        } catch (IOException e) {
            return false;
        }
        long remoteTime = millis(text(root, "updatedAt"));
        if (remoteTime <= 0 || !settings.acceptRemoteSettings(remoteTime)) {
            return false;
        }
        JsonNode values = root.path("values");
        if (!values.isObject()) {
            return false;
        }
        Map<String, String> incoming = new LinkedHashMap<>();
        values.fields().forEachRemaining(entry -> incoming.put(entry.getKey(), entry.getValue().asText("")));
        settings.applyExternalSettings(incoming, remoteTime);
        return true;
    }

    private static String text(JsonNode node, String field) {
        JsonNode value = node == null ? null : node.get(field);
        return value == null || value.isNull() ? "" : value.asText("");
    }

    private static String iso(long millis) {
        return LocalDateTime.ofInstant(Instant.ofEpochMilli(millis), ZoneId.systemDefault()).toString();
    }

    /** 与本地书对上号：优先“书名_作者”，同名同作者在书架里视为同一本书。 */
    private Map<String, Book> bookIndex() {
        Map<String, Book> index = new HashMap<>();
        for (Book book : bookDao.findAll()) {
            index.putIfAbsent(fileKey(book), book);
        }
        return index;
    }

    private static String fileKey(Book book) {
        String title = safe(book.title == null ? "" : book.title);
        String author = safe(book.author == null ? "" : book.author);
        return author.isEmpty() ? title : title + "_" + author;
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

    private static long millis(String updatedAt) {
        if (updatedAt == null || updatedAt.isBlank()) {
            return 0;
        }
        try {
            return LocalDateTime.parse(updatedAt).atZone(ZoneId.systemDefault()).toInstant().toEpochMilli();
        } catch (RuntimeException e) {
            return 0;
        }
    }
}
