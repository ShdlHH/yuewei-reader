package com.yuewei.reader.mobile;

import android.content.Context;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;

import java.io.File;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

/** 缓存管理：只统计与清理在线书，本地 TXT 章节保留。 */
@RunWith(RobolectricTestRunner.class)
public class CacheManagerTest {

    private Context context;
    private Library library;

    @Before
    public void setUp() {
        context = RuntimeEnvironment.getApplication();
        delete(new File(context.getFilesDir(), "library"));
        library = new Library(context);
    }

    @After
    public void tearDown() {
        delete(new File(context.getFilesDir(), "library"));
    }

    @Test
    public void countsAndClearsOnlyOnlineChapters() throws Exception {
        Library.Book online = book("cache-online", "在线书", "ONLINE");
        Library.Book local = book("cache-local", "本地书", "LOCAL");
        library.books.add(online);
        library.books.add(local);
        library.writeChapter(online.id, 0, "12345");
        library.writeChapter(online.id, 1, "1234567");
        library.writeChapter(local.id, 0, "1234");

        assertEquals(12, CacheManager.onlineCacheBytes(library));

        assertEquals(2, CacheManager.clearOnlineCache(library));
        assertFalse(library.chapterFile(online.id, 0).isFile());
        assertFalse(library.chapterFile(online.id, 1).isFile());
        assertTrue(library.chapterFile(local.id, 0).isFile());
    }

    @Test
    public void pruneKeepsCurrentBook() throws Exception {
        Library.Book first = book("cache-a", "第一本", "ONLINE");
        Library.Book current = book("cache-b", "正在读", "ONLINE");
        library.books.add(first);
        library.books.add(current);
        library.writeChapter(first.id, 0, "0123456789");
        library.writeChapter(current.id, 0, "0123456789");

        CacheManager.pruneOnline(library, 10, current.id);

        assertFalse(library.chapterFile(first.id, 0).isFile());
        assertTrue(library.chapterFile(current.id, 0).isFile());
    }

    private Library.Book book(String id, String title, String sourceType) {
        Library.Book book = new Library.Book();
        book.id = id;
        book.title = title;
        book.author = "作者";
        book.sourceType = sourceType;
        book.chapterTitles.add("第一章");
        book.chapterLengths.add(1);
        return book;
    }

    private static void delete(File file) {
        if (file.isDirectory()) {
            File[] children = file.listFiles();
            if (children != null) {
                for (File child : children) {
                    delete(child);
                }
            }
        }
        //noinspection ResultOfMethodCallIgnored
        file.delete();
    }
}
