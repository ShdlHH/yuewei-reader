package com.yuewei.reader.mobile;

import android.content.Context;

import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

/** 阅读设置：字体、标点开关与缓存上限的默认值与持久化。 */
@RunWith(RobolectricTestRunner.class)
public class AppSettingsTest {

    private Context context;

    @Before
    public void setUp() {
        context = RuntimeEnvironment.getApplication();
        context.getSharedPreferences("yuewei-settings", Context.MODE_PRIVATE)
                .edit().clear().commit();
    }

    @Test
    public void hasExpectedDefaults() {
        AppSettings settings = new AppSettings(context);

        assertEquals(MobileFonts.KEY_SERIF, settings.fontKey());
        assertTrue(settings.punctEnabled());
        assertTrue(settings.punctDialogue());
        assertTrue(settings.punctStop());
        assertTrue(settings.punctAsk());
        assertTrue(settings.punctExcl());
        assertFalse(settings.punctEllipsis());
        assertFalse(settings.punctParen());
        assertEquals(200, settings.cacheLimitMb());
    }

    @Test
    public void persistsFontPunctuationAndCacheLimit() {
        AppSettings settings = new AppSettings(context);
        settings.saveReader(20, 2.0f, "night", "builtin:sans-serif");
        settings.savePunctuation(false, true, false, true, false, true, false);
        settings.saveCacheLimit(512);

        AppSettings reloaded = new AppSettings(context);
        assertEquals(20, reloaded.fontSize());
        assertEquals("night", reloaded.theme());
        assertEquals("builtin:sans-serif", reloaded.fontKey());
        assertFalse(reloaded.punctEnabled());
        assertTrue(reloaded.punctDialogue());
        assertFalse(reloaded.punctStop());
        assertTrue(reloaded.punctEllipsis());
        assertFalse(reloaded.punctParen());
        assertEquals(512, reloaded.cacheLimitMb());
    }
}
