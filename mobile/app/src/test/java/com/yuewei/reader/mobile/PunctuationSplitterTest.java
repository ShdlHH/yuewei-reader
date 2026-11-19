package com.yuewei.reader.mobile;

import android.text.SpannableString;
import android.text.style.ForegroundColorSpan;

import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;

import static org.junit.Assert.assertEquals;

/** 标点着色：六条规则的取色范围。 */
@RunWith(RobolectricTestRunner.class)
public class PunctuationSplitterTest {

    private static final int INK = 0xFF111111;
    private static final int[] COLORS = {
            0xFFA00001, 0xFFA00002, 0xFFA00003, 0xFFA00004, 0xFFA00005, 0xFFA00006};
    private static final boolean[] ALL = {true, true, true, true, true, true};
    private static final boolean[] NONE = {false, false, false, false, false, false};

    @Test
    public void colorsDialogueAndStopPunctuation() {
        SpannableString text = new SpannableString("“你好。”他说。");
        PunctuationSplitter.apply(text, true, ALL, COLORS);

        assertEquals(COLORS[PunctuationSplitter.DIALOGUE], colorAt(text, 1));
        assertEquals(COLORS[PunctuationSplitter.STOP], colorAt(text, 3));
        assertEquals(COLORS[PunctuationSplitter.DIALOGUE], colorAt(text, 4));
        assertEquals(COLORS[PunctuationSplitter.STOP], colorAt(text, 7));
    }

    @Test
    public void colorsParenthesisWhenEnabled() {
        SpannableString text = new SpannableString("（旁白）他说。");
        PunctuationSplitter.apply(text, true, ALL, COLORS);

        assertEquals(COLORS[PunctuationSplitter.PAREN], colorAt(text, 1));
        assertEquals(COLORS[PunctuationSplitter.STOP], colorAt(text, 6));
    }

    @Test
    public void disabledRulesKeepInk() {
        SpannableString text = new SpannableString("“你好。”他说。");
        PunctuationSplitter.apply(text, true, NONE, COLORS);

        assertEquals(INK, colorAt(text, 1));
        assertEquals(INK, colorAt(text, 3));
        assertEquals(INK, colorAt(text, 7));
    }

    private static int colorAt(SpannableString text, int index) {
        ForegroundColorSpan[] spans = text.getSpans(index, index + 1, ForegroundColorSpan.class);
        return spans.length == 0 ? INK : spans[0].getForegroundColor();
    }
}
