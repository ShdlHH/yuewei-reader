package com.yuewei.reader.mobile;

import android.content.Context;

import com.yuewei.reader.domain.BookDetail;
import com.yuewei.reader.domain.TocChapter;

import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;

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
}
