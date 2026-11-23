package com.yuewei.reader.mobile;

import android.content.Context;

import com.yuewei.reader.domain.BookDetail;
import com.yuewei.reader.domain.TocChapter;

import org.json.JSONArray;
import org.json.JSONObject;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

/** 书库持久化、在线书匹配与删除墓碑。 */
@RunWith(RobolectricTestRunner.class)
public class LibraryTest {

    private Context context;
    private Library library;

    @Before
    public void setUp() {
        context = RuntimeEnvironment.getApplication();
        library = new Library(context);
    }

    @Test
    public void addOnlineRevivesDeletedBookInsteadOfDuplicating() {
        SourceStore.Source source = new SourceStore.Source();
        source.name = "测试源";
        source.url = "https://source.test";
        source.ruleJson = "{}";

        BookDetail detail = new BookDetail();
        detail.title = "在线书";
        detail.author = "作者";
        detail.bookUrl = "https://source.test/book/1";
        detail.chapters.add(new TocChapter(0, "第1章", "https://source.test/c1"));

        Library.Book first = library.addOnline(source, detail);
        library.markDeleted(first);

        Library reloaded = new Library(context);
        Library.Book revived = reloaded.addOnline(source, detail);

        assertEquals(first.id, revived.id);
        assertFalse(revived.deleted);
        assertEquals(1, reloaded.books.size());
        assertEquals(1, revived.chapterTitles.size());
    }

    @Test
    public void reloadFallsBackToBackupWhenMainFileIsTruncated() throws Exception {
        Library.Book book = new Library.Book();
        book.id = "b1";
        book.title = "本地书";
        library.books.add(book);
        library.save();
        library.save();

        File dir = new File(context.getFilesDir(), "library");
        File meta = new File(dir, "books.json");
        File backup = new File(dir, "books.json.bak");
        assertTrue(meta.isFile());
        assertTrue(backup.isFile());
        assertFalse("临时文件不应残留", new File(dir, "books.json.tmp").exists());

        // 模拟进程在写盘途中被杀：正式文件留下半截 JSON
        Files.write(meta.toPath(), "{\"books\":[".getBytes(StandardCharsets.UTF_8));

        Library recovered = new Library(context);
        assertEquals(1, recovered.books.size());
        assertEquals("b1", recovered.books.get(0).id);
    }

    @Test
    public void reloadKeepsTombstoneAndOnlineFields() {
        Library.Book book = new Library.Book();
        book.id = "o1";
        book.title = "在线书";
        book.author = "作者";
        book.sourceType = "ONLINE";
        book.sourceUrl = "https://source.test";
        book.sourceName = "测试源";
        book.bookUrl = "https://source.test/book/1";
        book.chapterTitles.add("第1章");
        book.chapterUrls.add("https://source.test/c1");
        book.chapterLengths.add(12);
        library.books.add(book);
        library.markDeleted(book);

        Library reloaded = new Library(context);
        Library.Book loaded = reloaded.find("o1");
        assertNotNull(loaded);
        assertTrue(loaded.deleted);
        assertEquals("ONLINE", loaded.sourceType);
        assertEquals("https://source.test", loaded.sourceUrl);
        assertEquals("测试源", loaded.sourceName);
        assertEquals("https://source.test/book/1", loaded.bookUrl);
        assertEquals(1, loaded.chapterTitles.size());
        assertEquals("https://source.test/c1", loaded.chapterUrls.get(0));
    }

    @Test
    public void findOnlineMatchesSourceAndBookUrl() {
        Library.Book book = new Library.Book();
        book.id = "o2";
        book.title = "在线书";
        book.author = "作者";
        book.sourceUrl = "https://source.test";
        book.bookUrl = "https://source.test/book/2";
        library.books.add(book);

        assertNotNull(library.findOnline("https://source.test", "https://source.test/book/2"));
        assertNull(library.findOnline("https://source.test", "https://source.test/book/3"));
        assertNull(library.findOnline("", "https://source.test/book/2"));
    }

    @Test
    public void addOnlineBuildsChaptersFromDetail() {
        SourceStore.Source source = new SourceStore.Source();
        source.name = "测试源";
        source.url = "https://source.test";
        source.ruleJson = "{}";

        BookDetail detail = new BookDetail();
        detail.title = "在线书";
        detail.author = "作者";
        detail.bookUrl = "https://source.test/book/2";
        detail.chapters.add(new TocChapter(0, "第1章", "https://source.test/c1"));
        detail.chapters.add(new TocChapter(1, "第2章", null));

        Library.Book book = library.addOnline(source, detail);

        assertEquals("ONLINE", book.sourceType);
        assertEquals("https://source.test", book.sourceUrl);
        assertEquals("https://source.test/book/2", book.bookUrl);
        assertEquals(2, book.chapterTitles.size());
        assertEquals("", book.chapterUrls.get(1));
        assertEquals(2, book.chapterLengths.size());
        assertTrue(book.id.startsWith("o"));
        assertNotNull(new Library(context).find(book.id));
    }

    @Test
    public void reloadDropsDuplicateIdKeepingNewerRecord() throws Exception {
        Library.Book book = new Library.Book();
        book.id = "dup-1";
        book.title = "重复书";
        book.author = "作者";
        book.addedAt = 1_700_000_000_000L;
        book.progressAt = 1_700_000_100_000L;
        library.books.add(book);
        library.save();

        File meta = new File(context.getFilesDir(), "library/books.json");
        JSONObject root = new JSONObject(new String(Files.readAllBytes(meta.toPath()), StandardCharsets.UTF_8));
        JSONArray array = root.getJSONArray("books");
        JSONObject duplicate = new JSONObject(array.getJSONObject(0).toString());
        duplicate.put("deleted", true);
        duplicate.put("progressAt", 1L);
        duplicate.put("lastReadAt", 1L);
        array.put(duplicate);
        Files.write(meta.toPath(), root.toString().getBytes(StandardCharsets.UTF_8));

        Library reloaded = new Library(context);

        assertEquals(1, reloaded.books.size());
        assertFalse(reloaded.books.get(0).deleted);
    }

    @Test
    public void deletedBookIsHiddenFromShelfOrder() {
        Library.Book book = new Library.Book();
        book.id = "b1";
        book.title = "待删书";
        book.author = "作者";
        book.addedAt = 1;
        library.books.add(book);
        assertEquals(1, SyncManager.sortedBooks(library).size());

        library.markDeleted(book);
        assertTrue(SyncManager.sortedBooks(library).isEmpty());
        assertFalse(library.books.isEmpty());
    }

    @Test
    public void cacheChapterDeferredDefersMetaWrite() throws Exception {
        SourceStore.Source source = new SourceStore.Source();
        source.name = "源";
        source.url = "https://s.test";
        source.ruleJson = "{}";

        BookDetail detail = new BookDetail();
        detail.title = "书";
        detail.author = "作";
        detail.bookUrl = "https://s.test/b";
        detail.chapters.add(new TocChapter(0, "第1章", "https://s.test/c1"));
        detail.chapters.add(new TocChapter(1, "第2章", "https://s.test/c2"));

        Library.Book book = library.addOnline(source, detail);

        // 延迟落盘：章节文件已写，但字数尚未写入 books.json
        library.cacheChapter(book, 0, "第一章正文内容", false);
        Library reloaded = new Library(context);
        Library.Book loaded = reloaded.find(book.id);
        assertEquals("延迟落盘时字数不应已持久化", 0, (int) loaded.chapterLengths.get(0));
        assertTrue("章节文件应已写入", reloaded.chapterFile(book.id, 0).isFile());

        // 显式 save 后字数才落盘
        library.save();
        Library reloaded2 = new Library(context);
        assertEquals("save 后字数应持久化",
                "第一章正文内容".length(), (int) reloaded2.find(book.id).chapterLengths.get(0));
    }

    @Test
    public void metaCacheInvalidatesWhenFileChanges() throws Exception {
        Library.Book book = new Library.Book();
        book.id = "cache-1";
        book.title = "缓存书";
        book.author = "作";
        library.books.add(book);
        library.save();
        // 首次构造填充解析缓存
        assertEquals(1, new Library(context).books.size());

        // 外部改写 books.json（增加一条，长度必变），新构造应看到改动
        File meta = new File(context.getFilesDir(), "library/books.json");
        JSONObject root = new JSONObject(new String(Files.readAllBytes(meta.toPath()), StandardCharsets.UTF_8));
        JSONArray arr = root.getJSONArray("books");
        JSONObject extra = new JSONObject(arr.getJSONObject(0).toString());
        extra.put("id", "cache-2");
        extra.put("title", "缓存书2");
        arr.put(extra);
        Files.write(meta.toPath(), root.toString().getBytes(StandardCharsets.UTF_8));

        Library fresh = new Library(context);
        assertEquals("外部改动后缓存应失效并重读", 2, fresh.books.size());
    }
}
