package com.yuewei.reader.mobile;

import android.content.Context;

import org.json.JSONArray;
import org.json.JSONObject;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

/** 移动端同步：进度推送、书架字段、删除墓碑与设置合并。 */
@RunWith(RobolectricTestRunner.class)
public class SyncManagerTest {

    private Context context;
    private Library library;
    private SourceStore sources;
    private AppSettings settings;
    private FakeWebDav dav;

    @Before
    public void setUp() {
        context = RuntimeEnvironment.getApplication();
        library = new Library(context);
        sources = new SourceStore(context);
        settings = new AppSettings(context);
        dav = new FakeWebDav();
    }

    @Test
    public void autoSyncIsOnByDefaultAndCanBeDisabled() {
        assertTrue(settings.autoSync());
        settings.saveAutoSync(false);
        assertFalse(new AppSettings(context).autoSync());
    }

    @Test
    public void autoSyncSkipsWhenIntervalNotElapsed() {
        settings.saveSync(true, "https://example.test/dav", "a", "b", "yuewei");
        settings.saveAutoSync(true);
        settings.saveSyncDone("测试同步");
        assertFalse(SyncManager.autoSync(context, 60_000, null));
    }

    @Test
    public void lastSyncTextReflectsSavedTime() {
        assertEquals("尚未同步", SyncManager.lastSyncText(context));
        settings.saveSyncDone("上传 1 本");
        assertTrue(SyncManager.lastSyncText(context).startsWith("上次同步 "));
        assertEquals("上传 1 本", settings.lastSyncMessage());
    }

    private Library.Book localBook(String title, String author, String hash) {
        Library.Book book = new Library.Book();
        book.id = hash.substring(0, 8);
        book.title = title;
        book.author = author;
        book.hash = hash;
        book.addedAt = 1_700_000_000_000L;
        book.chapterTitles.add("第一章");
        book.chapterTitles.add("第二章");
        book.chapterLengths.add(100);
        book.chapterLengths.add(200);
        library.books.add(book);
        return book;
    }

    private Library.Book onlineBook() {
        Library.Book book = new Library.Book();
        book.id = "o1";
        book.title = "在线书";
        book.author = "作者乙";
        book.hash = "";
        book.addedAt = 1_700_000_000_000L;
        book.sourceType = "ONLINE";
        book.sourceId = "https://source.test";
        book.sourceUrl = "https://source.test";
        book.sourceName = "测试源";
        book.bookUrl = "https://source.test/book/9";
        book.chapterTitles.add("第1章");
        book.chapterLengths.add(500);
        library.books.add(book);
        return book;
    }

    @Test
    public void pushWritesLegadoProgressAndShelf() throws Exception {
        Library.Book local = localBook("本地书", "作者甲", "hash-local-1");
        library.setProgress(local, 1, 5000);
        Library.Book online = onlineBook();
        library.setProgress(online, 0, 2000);

        String message = SyncManager.run(library, sources, dav);
        assertTrue(message.contains("上传"));

        JSONObject progress = new JSONObject(dav.files.get("bookProgress/本地书_作者甲.json"));
        assertEquals("本地书", progress.getString("name"));
        assertEquals("作者甲", progress.getString("author"));
        assertEquals(1, progress.getInt("durChapterIndex"));
        assertEquals(100, progress.getInt("durChapterPos"));
        assertEquals("第二章", progress.getString("durChapterTitle"));
        assertEquals(local.progressAt, progress.getLong("durChapterTime"));

        JSONArray books = new JSONObject(dav.files.get("shelf.json")).getJSONArray("books");
        assertEquals(2, books.length());
        JSONObject localItem = itemByHash(books, "hash-local-1");
        assertFalse(localItem.getBoolean("deleted"));
        assertEquals("LOCAL", localItem.getString("sourceType"));
        assertEquals(1, localItem.getInt("chapterIndex"));
        assertEquals(5000, localItem.getInt("percent"));

        JSONObject onlineItem = itemByTitle(books, "在线书");
        assertEquals("ONLINE", onlineItem.getString("sourceType"));
        assertEquals("https://source.test", onlineItem.getString("sourceUrl"));
        assertEquals("https://source.test/book/9", onlineItem.getString("bookUrl"));
        assertEquals("测试源", onlineItem.getString("sourceName"));
        assertFalse(dav.files.containsKey("settings.json"));
    }

    @Test
    public void deletingBookPushesTombstoneAndSkipsProgress() throws Exception {
        Library.Book book = localBook("待删书", "作者甲", "hash-delete-1");
        library.setProgress(book, 0, 1000);
        library.markDeleted(book);
        long deletedAt = book.progressAt;

        SyncManager.run(library, sources, dav);

        JSONObject progress = new JSONObject(dav.files.get("bookProgress/待删书_作者甲.json"));
        assertEquals("墓碑进度应从第一章开始", 0, progress.getInt("durChapterIndex"));
        assertEquals(0, progress.getInt("durChapterPos"));
        assertEquals("墓碑进度时间应为删除时间", deletedAt, progress.getLong("durChapterTime"));
        JSONObject item = itemByHash(
                new JSONObject(dav.files.get("shelf.json")).getJSONArray("books"), "hash-delete-1");
        assertTrue(item.getBoolean("deleted"));
    }

    @Test
    public void remoteTombstoneHidesLocalBook() throws Exception {
        Library.Book book = localBook("远删书", "作者甲", "hash-remote-del");
        dav.files.put("shelf.json", "{\"books\":[{\"title\":\"远删书\",\"author\":\"作者甲\","
                + "\"fileHash\":\"hash-remote-del\",\"deleted\":true}]}");

        SyncManager.run(library, sources, dav);

        assertTrue(book.deleted);
        assertTrue(SyncManager.sortedBooks(library).isEmpty());
        JSONObject item = itemByHash(
                new JSONObject(dav.files.get("shelf.json")).getJSONArray("books"), "hash-remote-del");
        assertTrue(item.getBoolean("deleted"));
    }

    @Test
    public void deletedBookIsNotRevivedByOldShelfSnapshot() throws Exception {
        Library.Book book = localBook("已删书", "作者甲", "hash-deleted-local");
        library.markDeleted(book);
        long deletedAt = book.progressAt;
        String progressAt = SyncManager.iso(deletedAt - 60_000);
        dav.files.put("shelf.json", "{\"books\":[{\"title\":\"已删书\",\"author\":\"作者甲\","
                + "\"fileHash\":\"hash-deleted-local\",\"deleted\":false,"
                + "\"chapterIndex\":1,\"percent\":9000,\"progressAt\":\"" + progressAt + "\"}]}");

        SyncManager.run(library, sources, dav);

        assertTrue(book.deleted);
        assertEquals(0, book.percent);
        assertEquals(deletedAt, book.progressAt);
        JSONObject tombstone = new JSONObject(dav.files.get("bookProgress/已删书_作者甲.json"));
        assertEquals(0, tombstone.getInt("durChapterIndex"));
        assertEquals(deletedAt, tombstone.getLong("durChapterTime"));
    }

    @Test
    public void readingAfterDeletionRevivesTombstone() throws Exception {
        Library.Book book = localBook("删后仍读", "作者甲", "hash-read-after-delete");
        library.markDeleted(book);
        long deletedAt = book.progressAt;
        String progressAt = SyncManager.iso(deletedAt + 60_000);
        dav.files.put("shelf.json", "{\"books\":[{\"title\":\"删后仍读\",\"author\":\"作者甲\","
                + "\"fileHash\":\"hash-read-after-delete\",\"deleted\":false,"
                + "\"chapterIndex\":1,\"percent\":9000,\"progressAt\":\"" + progressAt + "\"}]}");

        SyncManager.run(library, sources, dav);

        assertFalse(book.deleted);
        assertEquals(1, SyncManager.sortedBooks(library).size());
    }

    @Test
    public void newerRemoteAdditionRevivesLocalTombstone() throws Exception {
        Library.Book book = onlineBook();
        library.markDeleted(book);
        long activatedAt = book.lastReadAt + 60_000;
        dav.files.put("shelf.json", "{\"books\":[{\"title\":\"在线书\",\"author\":\"作者乙\","
                + "\"sourceType\":\"ONLINE\",\"sourceUrl\":\"https://source.test\","
                + "\"bookUrl\":\"https://source.test/book/9\",\"deleted\":false,"
                + "\"addedAt\":\"" + SyncManager.iso(activatedAt) + "\"}]}");

        SyncManager.run(library, sources, dav);

        assertFalse(book.deleted);
        assertEquals(0, book.chapterIndex);
        assertEquals(0, book.percent);
        assertEquals(activatedAt, book.addedAt);
        assertEquals(1, SyncManager.sortedBooks(library).size());
    }

    @Test
    public void newerRemoteProgressUpdatesBook() throws Exception {
        Library.Book book = localBook("进度书", "作者甲", "hash-progress-1");
        long remoteTime = System.currentTimeMillis() + 60_000;
        JSONObject node = new JSONObject();
        node.put("name", "进度书");
        node.put("author", "作者甲");
        node.put("durChapterIndex", 1);
        node.put("durChapterPos", 100);
        node.put("durChapterTitle", "第二章");
        node.put("durChapterTime", remoteTime);
        dav.files.put("bookProgress/进度书_作者甲.json", node.toString());

        SyncManager.run(library, sources, dav);

        assertEquals(1, book.chapterIndex);
        assertEquals(5000, book.percent);
        assertEquals(remoteTime, book.progressAt);
    }

    @Test
    public void settingsAreNotPulledFromRemote() throws Exception {
        settings.saveReader(18, 1.6f, "paper");
        JSONObject root = new JSONObject();
        root.put("updatedAt", SyncManager.iso(System.currentTimeMillis() + 60_000));
        JSONObject values = new JSONObject();
        values.put("reader.fontSize", "24");
        values.put("reader.lineSpacing", "2.2");
        values.put("reader.theme", "night");
        root.put("values", values);
        dav.files.put("settings.json", root.toString());

        SyncManager.run(library, sources, dav);

        assertEquals(18, settings.fontSize());
        assertEquals(1.6f, settings.lineSpacing(), 0.001f);
        assertEquals("paper", settings.theme());
    }

    @Test
    public void settingsAreNotPushedToRemote() throws Exception {
        settings.saveReader(20, 1.9f, "night");

        SyncManager.run(library, sources, dav);

        assertFalse(dav.files.containsKey("settings.json"));
    }

    @Test
    public void remoteProgressAppliesEvenWhenBookWasAddedLater() throws Exception {
        Library.Book book = localBook("时间边界", "作者甲", "hash-time-edge");
        book.addedAt = System.currentTimeMillis();
        book.progressAt = 0;
        library.save();

        long remoteTime = book.addedAt - 10;
        JSONObject node = new JSONObject();
        node.put("name", "时间边界");
        node.put("author", "作者甲");
        node.put("durChapterIndex", 1);
        node.put("durChapterPos", 100);
        node.put("durChapterTitle", "第二章");
        node.put("durChapterTime", remoteTime);
        dav.files.put("bookProgress/时间边界_作者甲.json", node.toString());

        SyncManager.run(library, sources, dav);

        assertEquals(1, book.chapterIndex);
        assertEquals(5000, book.percent);
        assertEquals(remoteTime, book.progressAt);
    }

    @Test
    public void pushKeepsRemoteEntriesThatCouldNotBeImported() throws Exception {
        String remoteShelf = "{\"books\":[{\"title\":\"远端在线书\",\"author\":\"作者丁\","
                + "\"sourceType\":\"ONLINE\",\"sourceUrl\":\"https://missing.test\","
                + "\"bookUrl\":\"https://missing.test/book/1\",\"fileHash\":\"\",\"deleted\":false,"
                + "\"addedAt\":\"" + SyncManager.iso(System.currentTimeMillis()) + "\"}]}";
        dav.files.put("shelf.json", remoteShelf);

        SyncManager.run(library, sources, dav);

        JSONObject shelf = new JSONObject(dav.files.get("shelf.json"));
        itemByTitle(shelf.getJSONArray("books"), "远端在线书");
    }

    @Test
    public void pushKeepsRemoteProgressWhenChapterLengthUnknown() throws Exception {
        Library.Book book = new Library.Book();
        book.id = "o-unknown";
        book.title = "未缓存在线书";
        book.author = "作者己";
        book.hash = "";
        book.sourceType = "ONLINE";
        book.sourceUrl = "https://source.test";
        book.bookUrl = "https://source.test/book/7";
        book.addedAt = System.currentTimeMillis() - 60_000;
        book.chapterTitles.add("第1章");
        book.chapterLengths.add(0);
        library.books.add(book);
        library.save();

        long remoteTime = System.currentTimeMillis() + 60_000;
        dav.files.put("bookProgress/未缓存在线书_作者己.json",
                "{\"name\":\"未缓存在线书\",\"author\":\"作者己\",\"durChapterIndex\":0,"
                        + "\"durChapterPos\":120,\"durChapterTime\":" + remoteTime + "}");

        SyncManager.run(library, sources, dav);

        JSONObject progress = new JSONObject(dav.files.get("bookProgress/未缓存在线书_作者己.json"));
        assertEquals("章内长度未知时不应把远端位点清零", 120, progress.getInt("durChapterPos"));
    }

    @Test
    public void matchesBookSourceUrlWithTrailingSlash() throws Exception {
        sources.importJson("[{\"bookSourceName\":\"测试源\",\"bookSourceUrl\":\"https://source.test/\"}]");
        assertEquals("https://source.test", sources.sources.get(0).url);
        assertNotNull(sources.find("https://source.test"));
        assertNotNull(sources.find("https://source.test/"));

        Library.Book book = onlineBook();
        assertNotNull(library.findOnline("https://source.test/", "https://source.test/book/9/"));
        assertEquals(book, library.findOnline("https://SOURCE.TEST", "https://source.test/book/9"));
    }

    private static JSONObject itemByHash(JSONArray books, String hash) throws Exception {
        for (int i = 0; i < books.length(); i++) {
            JSONObject item = books.getJSONObject(i);
            if (hash.equals(item.optString("fileHash"))) {
                return item;
            }
        }
        throw new AssertionError("书架快照里没有 fileHash=" + hash);
    }

    private static JSONObject itemByTitle(JSONArray books, String title) throws Exception {
        for (int i = 0; i < books.length(); i++) {
            JSONObject item = books.getJSONObject(i);
            if (title.equals(item.optString("title"))) {
                return item;
            }
        }
        throw new AssertionError("书架快照里没有书名=" + title);
    }
}
