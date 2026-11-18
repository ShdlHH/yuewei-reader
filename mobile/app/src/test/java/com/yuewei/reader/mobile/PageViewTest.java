package com.yuewei.reader.mobile;

import android.view.View;

import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;

import static org.junit.Assert.assertEquals;
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
        view.setListener(new PageView.Listener() {
            @Override
            public void onPageChanged(int page, int pages, int percent) {
                report[0] = page;
                report[1] = pages;
                report[2] = percent;
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
        view.nextPage();
        assertEquals(2, report[0]);
        assertTrue(report[2] > 0);
    }
}
