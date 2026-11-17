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
import static org.junit.Assert.assertTrue;

/** 移动端同步：进度推送、书架字段、删除墓碑与设置合并。 */
@RunWith(RobolectricTestRunner.class)
public class SyncManagerTest {

    private Library library;
    private SourceStore sources;
    private AppSettings settings;
    private FakeWebDav dav;

    @Before
    public void setUp() {
        Context context = RuntimeEnvironment.getApplication();
        library = new Library(context);
        sources = new SourceStore(context);
        settings = new AppSettings(context);
        dav = new FakeWebDav();
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

        String message = SyncManager.run(library, sources, settings, dav);
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

        SyncManager.run(library, sources, settings, dav);

        assertFalse(dav.files.containsKey("bookProgress/待删书_作者甲.json"));
        JSONObject item = itemByHash(
                new JSONObject(dav.files.get("shelf.json")).getJSONArray("books"), "hash-delete-1");
        assertTrue(item.getBoolean("deleted"));
    }

    @Test
    public void remoteTombstoneHidesLocalBook() throws Exception {
        Library.Book book = localBook("远删书", "作者甲", "hash-remote-del");
        dav.files.put("shelf.json", "{\"books\":[{\"title\":\"远删书\",\"author\":\"作者甲\","
                + "\"fileHash\":\"hash-remote-del\",\"deleted\":true}]}");

        SyncManager.run(library, sources, settings, dav);

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
        String progressAt = SyncManager.iso(System.currentTimeMillis() + 60_000);
        dav.files.put("shelf.json", "{\"books\":[{\"title\":\"已删书\",\"author\":\"作者甲\","
                + "\"fileHash\":\"hash-deleted-local\",\"deleted\":false,"
                + "\"chapterIndex\":1,\"percent\":9000,\"progressAt\":\"" + progressAt + "\"}]}");

        SyncManager.run(library, sources, settings, dav);

        assertTrue(book.deleted);
        assertEquals(0, book.percent);
        assertEquals(deletedAt, book.progressAt);
        assertFalse(dav.files.containsKey("bookProgress/已删书_作者甲.json"));
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

        SyncManager.run(library, sources, settings, dav);

        assertEquals(1, book.chapterIndex);
        assertEquals(5000, book.percent);
        assertEquals(remoteTime, book.progressAt);
    }

    @Test
    public void appliesRemoteReadingSettings() throws Exception {
        JSONObject root = new JSONObject();
        root.put("updatedAt", SyncManager.iso(System.currentTimeMillis() + 60_000));
        JSONObject values = new JSONObject();
        values.put("reader.fontSize", "24");
        values.put("reader.lineSpacing", "2.2");
        values.put("reader.theme", "night");
        root.put("values", values);
        dav.files.put("settings.json", root.toString());

        SyncManager.run(library, sources, settings, dav);

        assertEquals(24, settings.fontSize());
        assertEquals(2.2f, settings.lineSpacing(), 0.001f);
        assertEquals("night", settings.theme());
        assertTrue(settings.settingsRemoteAt() > 0);
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
