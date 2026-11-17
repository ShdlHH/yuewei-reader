package com.yuewei.reader.mobile;

import android.content.Context;

import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;

import java.nio.charset.StandardCharsets;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotEquals;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;

/** TXT 导入：分章、指纹与去重。 */
@RunWith(RobolectricTestRunner.class)
public class TxtImporterTest {

    private Library library;

    @Before
    public void setUp() {
        Context context = RuntimeEnvironment.getApplication();
        library = new Library(context);
    }

    @Test
    public void importsUtf8TxtIntoChapters() throws Exception {
        String text = "第一章 开始\n这是第一章内容。\n\n第二章 继续\n这是第二章内容。\n";
        Library.Book book = TxtImporter.importBook(library, "测试书.txt",
                text.getBytes(StandardCharsets.UTF_8));

        assertEquals("测试书", book.title);
        assertEquals(64, book.hash.length());
        assertEquals(2, book.chapterTitles.size());
        assertEquals("第一章 开始", book.chapterTitles.get(0));
        assertTrue(library.chapterFile(book.id, 0).isFile());
        assertTrue(library.chapterText(book, 0).contains("这是第一章内容"));
        assertEquals(1, library.books.size());
        assertNotEquals(0, book.chapterLengths.get(0).intValue());
    }

    @Test
    public void importingSameFileTwiceKeepsOneBook() throws Exception {
        byte[] bytes = "第一章 开始\n内容一\n第二章 继续\n内容二\n".getBytes(StandardCharsets.UTF_8);
        Library.Book first = TxtImporter.importBook(library, "a.txt", bytes);
        Library.Book second = TxtImporter.importBook(library, "b.txt", bytes);

        assertSame(first, second);
        assertEquals(1, library.books.size());
    }

    @Test
    public void decodesGbkTxt() throws Exception {
        String text = "第一章 开始\n这是第一章内容。\n第二章 继续\n这是第二章内容。";
        byte[] bytes = text.getBytes(java.nio.charset.Charset.forName("GBK"));
        Library.Book book = TxtImporter.importBook(library, "gbk.txt", bytes);

        assertEquals("gbk", book.title);
        assertTrue(library.chapterText(book, 0).contains("这是第一章内容"));
    }
}
