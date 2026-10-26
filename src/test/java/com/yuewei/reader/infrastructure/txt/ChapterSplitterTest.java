package com.yuewei.reader.infrastructure.txt;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ChapterSplitterTest {

    @Test
    void splitsCommonChineseHeadings() {
        String text = """
                第一章 少年
                少年站在山巅。

                第二章 风起
                风从北方吹来。

                番外 后记
                作者的话。
                """;
        ChapterSplitter.Result result = ChapterSplitter.splitAuto(text);
        assertEquals(List.of("第一章 少年", "第二章 风起", "番外 后记"), result.titles);
        assertEquals(3, result.contents.size());
        assertTrue(result.contents.get(1).contains("风从北方吹来"));
    }

    @Test
    void keepsPrefixAsPreludeChapter() {
        String text = """
                书名：测试
                作者：某人

                第一章 开始
                正文一。
                第二章 继续
                正文二。
                """;
        ChapterSplitter.Result result = ChapterSplitter.splitAuto(text);
        assertEquals("序", result.titles.get(0));
        assertTrue(result.contents.get(0).contains("作者：某人"));
        assertEquals(3, result.titles.size());
    }

    @Test
    void throwsWhenHeadingsAreTooFew() {
        String text = "第一章 唯一\n只有一章内容。";
        assertThrows(ChapterSplitter.NoChaptersException.class, () -> ChapterSplitter.splitAuto(text));
    }

    @Test
    void splitsByFixedSize() {
        String text = "字".repeat(7000);
        ChapterSplitter.Result result = ChapterSplitter.splitByFixedSize(text, 3000);
        assertEquals(3, result.titles.size());
        assertEquals("第 1 部分", result.titles.get(0));
        assertTrue(result.contents.get(0).length() >= 3000);
        assertTrue(result.contents.get(2).length() <= 3000);
    }

    @Test
    void splitsByCustomRegex() {
        String text = "=== 第一幕 ===\n内容甲\n=== 第二幕 ===\n内容乙";
        ChapterSplitter.Result result = ChapterSplitter.splitByRegex(text, "^===\\s*.+\\s*===$");
        assertEquals(2, result.titles.size());
        assertEquals("=== 第一幕 ===", result.titles.get(0));
        assertTrue(result.contents.get(1).contains("内容乙"));
    }

    @Test
    void singleChapterKeepsWholeText() {
        String text = "整本书只有一段文字。\r\n第二行。";
        ChapterSplitter.Result result = ChapterSplitter.singleChapter(text, null);
        assertEquals("全文", result.titles.get(0));
        assertTrue(result.contents.get(0).contains("第二行"));
    }
}
