package com.yuewei.reader.mobile;

import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;

import java.util.Arrays;
import java.util.Collections;
import java.util.List;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

/** 目录搜索过滤。 */
@RunWith(RobolectricTestRunner.class)
public class ChapterFilterTest {

    private static final List<String> TITLES = Arrays.asList(
            "第一章 夺灵", "第二章 前世今生", "第八章 屠家困局", "番外 Abc篇");

    @Test
    public void emptyQueryReturnsAll() {
        assertEquals(Arrays.asList(0, 1, 2, 3), ReaderActivity.filterChapters(TITLES, ""));
        assertEquals(Arrays.asList(0, 1, 2, 3), ReaderActivity.filterChapters(TITLES, null));
    }

    @Test
    public void matchesChineseAndIgnoresCase() {
        assertEquals(Collections.singletonList(2), ReaderActivity.filterChapters(TITLES, "屠家"));
        assertEquals(Collections.singletonList(3), ReaderActivity.filterChapters(TITLES, "abc"));
        assertEquals(Collections.singletonList(1), ReaderActivity.filterChapters(TITLES, " 前世 "));
    }

    @Test
    public void noMatchReturnsEmpty() {
        assertTrue(ReaderActivity.filterChapters(TITLES, "不存在的章节").isEmpty());
    }
}
