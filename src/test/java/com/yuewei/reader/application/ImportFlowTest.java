package com.yuewei.reader.application;

import com.yuewei.reader.domain.Book;
import com.yuewei.reader.domain.Chapter;
import com.yuewei.reader.domain.ContentState;
import com.yuewei.reader.infrastructure.txt.ChapterSplitter;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.charset.Charset;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ImportFlowTest {

    @TempDir
    Path temp;

    @Test
    void importsGbkTxtAndSplitsChapters() throws Exception {
        Path file = temp.resolve("测试小说.txt");
        String text = """
                第一章 初见
                这是第一章的内容。

                第二章 相逢
                这是第二章的内容。

                第三章 离别
                这是第三章的内容。
                """;
        Files.write(file, text.getBytes(Charset.forName("GBK")));

        AppContext ctx = new AppContext(temp.resolve("data"));
        try {
            ImportPreview preview = ctx.importService.preview(file);
            assertTrue(preview.splittable());
            assertEquals(3, preview.chapterCount);
            assertEquals("测试小说", preview.defaultTitle);

            Book book = ctx.importService.importBook(file, preview.charset,
                    ChapterSplitter.Mode.AUTO, "", "测试小说", "测试作者", false);
            assertNotNull(book.id);
            assertEquals(3, book.chapterCount);
            assertTrue(book.offline, "本地书导入后应标记离线可读");

            List<Chapter> chapters = ctx.bookService.chapters(book.id);
            assertEquals(3, chapters.size());
            assertEquals("第一章 初见", chapters.get(0).title);
            assertTrue(chapters.stream().allMatch(chapter -> chapter.state == ContentState.CACHED));

            String content = ctx.onlineBookService.loadContent(book, chapters.get(1));
            assertTrue(content.contains("这是第二章的内容"));

            assertEquals(1, ctx.bookService.shelf(null, null, "recent").size());
            assertTrue(ctx.bookService.cacheSize(book.id) > 0);

            AppException duplicate = assertThrows(AppException.class, () -> ctx.importService.importBook(
                    file, preview.charset, ChapterSplitter.Mode.AUTO, "", "测试小说", "测试作者", false));
            assertTrue(duplicate.getMessage().startsWith("该书已在书架"));

            Book forced = ctx.importService.importBook(file, preview.charset,
                    ChapterSplitter.Mode.AUTO, "", "测试小说（副本）", "测试作者", true);
            assertEquals("测试小说（副本）", forced.title);
            assertEquals(2, ctx.bookService.shelf(null, null, "recent").size());
        } finally {
            ctx.close();
        }
    }

    @Test
    void previewFallsBackToSingleChapterForEmptySections() throws Exception {
        Path file = temp.resolve("短篇.txt");
        Files.writeString(file, "只有一段文字，没有任何章节标题。", Charset.forName("GBK"));
        AppContext ctx = new AppContext(temp.resolve("data2"));
        try {
            ImportPreview preview = ctx.importService.preview(file);
            assertTrue(!preview.splittable());
            assertNotNull(preview.error);

            Book book = ctx.importService.importBook(file, preview.charset,
                    ChapterSplitter.Mode.SINGLE, "", preview.defaultTitle, "未知", false);
            assertEquals(1, book.chapterCount);
            assertTrue(book.offline);
        } finally {
            ctx.close();
        }
    }
}
