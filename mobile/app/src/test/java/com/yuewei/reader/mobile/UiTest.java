package com.yuewei.reader.mobile;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

import android.content.Context;
import android.text.InputType;
import android.widget.CheckBox;
import android.widget.EditText;
import android.widget.RadioButton;
import android.widget.RadioGroup;

import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;

/** 主题、字体与共享控件的样式规范。 */
@RunWith(RobolectricTestRunner.class)
public class UiTest {

    private Context context;

    @Before
    public void setUp() {
        context = RuntimeEnvironment.getApplication();
    }

    @Test
    public void nightPaletteIsDark() {
        assertTrue(Ui.palette("night").dark);
        assertEquals(false, Ui.palette("paper").dark);
        assertEquals(false, Ui.palette("green").dark);
    }

    @Test
    public void themeGroupSelectsCurrentAndReportsChange() {
        Ui.Palette palette = Ui.palette("paper");
        String[] selected = new String[1];
        RadioGroup group = Ui.themeGroup(context, palette, "night", value -> selected[0] = value);

        RadioButton checked = group.findViewById(group.getCheckedRadioButtonId());
        assertNotNull(checked);
        assertEquals("night", String.valueOf(checked.getTag()));

        ((RadioButton) group.getChildAt(0)).setChecked(true);
        assertEquals("paper", selected[0]);
    }

    @Test
    public void checkAndInputUseUnifiedTypography() {
        Ui.Palette palette = Ui.palette("paper");

        CheckBox box = Ui.check(context, palette, "标点着色", true);
        assertEquals("标点着色", box.getText().toString());
        assertTrue(box.isChecked());
        assertEquals(Ui.sp(context, Ui.TEXT_BODY), box.getTextSize(), 0.5f);

        EditText edit = Ui.input(context, palette, "搜索章节", InputType.TYPE_CLASS_TEXT);
        assertEquals("搜索章节", edit.getHint().toString());
        assertNotNull(edit.getBackground());
        assertEquals(Ui.sp(context, Ui.TEXT_BODY), edit.getTextSize(), 0.5f);
    }
}
