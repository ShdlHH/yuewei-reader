package com.yuewei.reader.mobile;

import android.view.View;

import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

/** 正文缩进与分页。 */
@RunWith(RobolectricTestRunner.class)
public class PageViewTest {

    @Test
    public void indentSkipsChapterTitleAndIndentsParagraphs() {
        String text = "第一章 开始\n正文第一段\n\n正文第二段";
        assertEquals("第一章 开始\n　　正文第一段\n\n　　正文第二段", PageView.indent(text));
    }

    @Test
    public void paginatesLongChapterIntoSeveralPages() {
        PageView view = new PageView(RuntimeEnvironment.getApplication());
        int[] report = new int[]{0, 0, 0};
        boolean[] userTurn = new boolean[]{false};
        view.setListener(new PageView.Listener() {
            @Override
            public void onPageChanged(int page, int pages, int percent, boolean user) {
                report[0] = page;
                report[1] = pages;
                report[2] = percent;
                userTurn[0] = user;
            }

            @Override
            public boolean onNextChapter() {
                return false;
            }

            @Override
            public boolean onPreviousChapter() {
                return false;
            }
        });
        view.measure(View.MeasureSpec.makeMeasureSpec(1080, View.MeasureSpec.EXACTLY),
                View.MeasureSpec.makeMeasureSpec(1800, View.MeasureSpec.EXACTLY));
        view.layout(0, 0, 1080, 1800);

        StringBuilder text = new StringBuilder();
        for (int i = 0; i < 200; i++) {
            text.append("这是第").append(i).append("段测试文字，用来验证分页与段间距。\n");
        }
        view.setText(text.toString());

        assertEquals(1, report[0]);
        assertTrue(report[1] >= 2);
        assertFalse("排版触发的 onPageChanged 不算用户翻页", userTurn[0]);
        view.nextPage();
        assertEquals(2, report[0]);
        assertTrue(report[2] > 0);
        assertTrue("用户翻页应标记 userTurn", userTurn[0]);
    }

    @Test
    public void togglingPunctuationDoesNotRelayout() {
        PageView view = new PageView(RuntimeEnvironment.getApplication());
        int[] pages = {0};
        int[] changes = {0};
        view.setListener(new PageView.Listener() {
            @Override
            public void onPageChanged(int page, int pgs, int percent, boolean user) {
                pages[0] = pgs;
                changes[0]++;
            }

            @Override
            public boolean onNextChapter() {
                return false;
            }

            @Override
            public boolean onPreviousChapter() {
                return false;
            }
        });
        view.measure(View.MeasureSpec.makeMeasureSpec(1080, View.MeasureSpec.EXACTLY),
                View.MeasureSpec.makeMeasureSpec(1800, View.MeasureSpec.EXACTLY));
        view.layout(0, 0, 1080, 1800);

        StringBuilder text = new StringBuilder();
        for (int i = 0; i < 120; i++) {
            text.append("“对话内容。”普通正文。疑问？感叹！\n");
        }
        view.setText(text.toString());
        int pagesAfterText = pages[0];
        int changesAfterText = changes[0];
        assertTrue("应有多页", pagesAfterText >= 2);

        int[] colors = {0xFFFF0000, 0xFF00FF00, 0xFF0000FF, 0xFFFFFF00, 0xFF00FFFF, 0xFFFF00FF};
        view.setPunctuation(true, new boolean[]{true, true, true, true, true, true}, colors);
        view.setPunctuation(false, null, null);

        // 标点着色只改 span 颜色、不改分页：不应触发重排回调，页数不变
        assertEquals("切换标点不应触发重排", changesAfterText, changes[0]);
        assertEquals("切换标点不应改变页数", pagesAfterText, pages[0]);
    }
}
