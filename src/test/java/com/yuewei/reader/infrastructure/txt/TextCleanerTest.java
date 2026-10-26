package com.yuewei.reader.infrastructure.txt;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class TextCleanerTest {

    @Test
    void convertsHtmlBreaksToParagraphs() {
        String text = TextCleaner.fromHtml(
                "<div>第一段<br>第二段</div><div>第三段 &amp; 符号</div>");
        assertTrue(text.contains("第一段"));
        assertTrue(text.contains("第二段"));
        assertTrue(text.contains("第三段 & 符号"));
        assertTrue(text.lines().count() >= 3);
    }

    @Test
    void filtersAdvertisementLines() {
        String text = "正文第一行\n请收藏本站最新地址\n正文第二行\nhttps://example.com";
        String cleaned = TextCleaner.clean(text, true);
        assertTrue(cleaned.contains("正文第一行"));
        assertTrue(cleaned.contains("正文第二行"));
        assertFalse(cleaned.contains("请收藏本站"));
        assertFalse(cleaned.contains("example.com"));
    }

    @Test
    void localCleaningKeepsSuspiciousLines() {
        String text = "正文\n请收藏本站最新地址";
        String cleaned = TextCleaner.cleanLocal(text);
        assertTrue(cleaned.contains("请收藏本站最新地址"));
    }

    @Test
    void collapsesBlankLines() {
        String text = "第一段\n\n\n\n第二段";
        String cleaned = TextCleaner.clean(text, false);
        assertEquals("第一段\n第二段", cleaned);
    }
}
