package com.yuewei.reader.mobile;

import android.app.Activity;
import android.content.Context;
import android.content.Intent;
import android.graphics.drawable.ColorDrawable;
import android.view.View;
import android.view.ViewGroup;
import android.widget.TextView;

import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.Robolectric;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;

import java.io.File;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

/** 界面冒烟测试：三个主要页面能正常创建，主题与同步状态生效。 */
@RunWith(RobolectricTestRunner.class)
public class UiSmokeTest {

    private Context context;

    @Before
    public void setUp() {
        context = RuntimeEnvironment.getApplication();
        awaitSyncIdle();
        resetAppState();
    }

    /** Robolectric 同一个类内会复用应用目录，手动清掉上个用例留下的设置与书架。 */
    private void resetAppState() {
        context.getSharedPreferences("yuewei-settings", Context.MODE_PRIVATE)
                .edit().clear().commit();
        delete(new File(context.getFilesDir(), "library"));
        new File(context.getFilesDir(), "sources.json").delete();
    }

    private static void delete(File file) {
        if (file.isDirectory()) {
            File[] children = file.listFiles();
            if (children != null) {
                for (File child : children) {
                    delete(child);
                }
            }
        }
        file.delete();
    }

    /** 上一个用例触发的后台同步可能还在跑，等它结束并释放同步锁再启动新页面。 */
    private static void awaitSyncIdle() {
        long deadline = System.currentTimeMillis() + 15_000;
        while (System.currentTimeMillis() < deadline) {
            // 同步结束后会 post 回主线程释放锁，Robolectric 默认暂停，需要手动 idle
            org.robolectric.shadows.ShadowLooper.idleMainLooper();
            if (!SyncManager.running()) {
                return;
            }
            try {
                Thread.sleep(20);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return;
            }
        }
    }

    @Test
    public void shelfShowsSyncStatusAndActions() {
        MainActivity activity = Robolectric.buildActivity(MainActivity.class).setup().get();

        assertNotNull(findText(activity, "尚未同步", false));
        assertNotNull(findText(activity, "导入 TXT", false));
        assertNotNull(findText(activity, "同步", false));
        assertNotNull(findTextContaining(activity, "书架还是空的"));
    }

    @Test
    public void shelfShowsBookCardAndNightTheme() throws Exception {
        new AppSettings(context).saveReader(18, 1.8f, "night");
        Library library = new Library(context);
        Library.Book book = new Library.Book();
        book.id = "b1";
        book.title = "测试书";
        book.author = "作者甲";
        book.chapterTitles.add("第一章 开始");
        book.chapterLengths.add(8);
        library.books.add(book);
        library.writeChapter(book.id, 0, "正文内容");
        library.setProgress(book, 0, 5000);

        MainActivity activity = Robolectric.buildActivity(MainActivity.class).setup().get();

        assertNotNull(findText(activity, "测试书", false));
        assertNotNull(findTextContaining(activity, "作者甲"));
        View root = ((ViewGroup) activity.findViewById(android.R.id.content)).getChildAt(0);
        assertEquals(Ui.palette("night").bg, ((ColorDrawable) root.getBackground()).getColor());
    }

    @Test
    public void readerShowsChapterAndSerifBody() throws Exception {
        Library library = new Library(context);
        Library.Book book = new Library.Book();
        book.id = "b2";
        book.title = "阅读测试";
        book.author = "作者乙";
        book.chapterTitles.add("第一章 开始");
        book.chapterTitles.add("第二章 继续");
        book.chapterLengths.add(8);
        book.chapterLengths.add(8);
        library.books.add(book);
        library.writeChapter(book.id, 0, "第一章正文");
        library.writeChapter(book.id, 1, "第二章正文");
        library.save();

        Intent intent = new Intent(context, ReaderActivity.class)
                .putExtra(ReaderActivity.EXTRA_BOOK_ID, book.id);
        ReaderActivity activity = Robolectric.buildActivity(ReaderActivity.class, intent).setup().get();

        assertNotNull(findText(activity, "第一章 开始", false));
        assertNotNull(findText(activity, "上一章", false));
        assertNotNull(findText(activity, "下一章", false));
        assertNotNull(findText(activity, "Aa", false));
        assertNotNull(findTextContaining(activity, "1/1"));
        PageView pageView = findView(activity.getWindow().getDecorView(), PageView.class);
        assertNotNull(pageView);
    }

    @Test
    public void settingsShowsFontPunctuationAndCacheControls() {
        SettingsActivity activity = Robolectric.buildActivity(SettingsActivity.class).setup().get();

        assertNotNull(findTextContaining(activity, "字体："));
        assertNotNull(findText(activity, "导入字体文件", false));
        assertNotNull(findText(activity, "标点着色", false));
        assertNotNull(findTextContaining(activity, "在线书缓存"));
        assertNotNull(findText(activity, "清理在线书缓存", false));
    }

    /** 沉浸阅读时页脚常驻，底部工具栏默认隐藏。 */
    @Test
    public void readerKeepsFooterVisibleWhenChromeHidden() throws Exception {
        Library library = new Library(context);
        Library.Book book = new Library.Book();
        book.id = "b6";
        book.title = "页脚测试";
        book.author = "作者己";
        book.chapterTitles.add("第一章 开始");
        book.chapterLengths.add(8);
        library.books.add(book);
        library.writeChapter(book.id, 0, "第一章正文");
        library.save();

        Intent intent = new Intent(context, ReaderActivity.class)
                .putExtra(ReaderActivity.EXTRA_BOOK_ID, book.id);
        ReaderActivity activity = Robolectric.buildActivity(ReaderActivity.class, intent).setup().get();

        TextView pageInfo = findTextContaining(activity, "1/1");
        assertNotNull("沉浸阅读时应显示页码", pageInfo);
        assertEquals(View.VISIBLE, ((View) pageInfo.getParent()).getVisibility());

        TextView nextChapter = findText(activity, "下一章", false);
        assertNotNull(nextChapter);
        View panel = ancestorWithTag(nextChapter, "panel");
        assertNotNull("底部面板应带 panel 标记", panel);
        assertEquals("默认应隐藏底部面板", View.GONE, panel.getVisibility());
    }

    private static View ancestorWithTag(View view, String tag) {
        View current = view;
        while (current != null) {
            if (tag.equals(current.getTag())) {
                return current;
            }
            current = current.getParent() instanceof View
                    ? (View) current.getParent() : null;
        }
        return null;
    }

    @Test
    public void searchAndSourcesScreensCreate() {
        assertNotNull(Robolectric.buildActivity(SearchActivity.class).setup().get());
        assertNotNull(Robolectric.buildActivity(SourcesActivity.class).setup().get());
    }

    @Test
    public void settingsShowsAutoSyncOption() {
        SettingsActivity activity = Robolectric.buildActivity(SettingsActivity.class).setup().get();

        assertNotNull(findTextContaining(activity, "自动同步"));
        assertNotNull(findTextContaining(activity, "立即同步"));
        assertTrue(findText(activity, "设置", false) != null);
    }

    @Test
    public void openingAppSchedulesAutoSync() {
        AppSettings settings = new AppSettings(context);
        settings.saveSync(true, "http://127.0.0.1:1/dav", "a", "b", "yuewei");
        settings.saveAutoSync(true);
        assertEquals(0, settings.lastAttemptAt());

        Robolectric.buildActivity(MainActivity.class).setup();

        assertTrue(new AppSettings(context).lastAttemptAt() > 0);
        awaitSyncIdle();
    }

    @Test
    public void leavingReaderSchedulesAutoSync() throws Exception {
        AppSettings settings = new AppSettings(context);
        settings.saveSync(true, "http://127.0.0.1:1/dav", "a", "b", "yuewei");
        settings.saveAutoSync(true);

        Library library = new Library(context);
        Library.Book book = new Library.Book();
        book.id = "b3";
        book.title = "退出测试";
        book.author = "作者丙";
        book.chapterTitles.add("第一章");
        book.chapterLengths.add(4);
        library.books.add(book);
        library.writeChapter(book.id, 0, "正文");
        library.save();

        Intent intent = new Intent(context, ReaderActivity.class)
                .putExtra(ReaderActivity.EXTRA_BOOK_ID, book.id);
        org.robolectric.android.controller.ActivityController<ReaderActivity> controller =
                Robolectric.buildActivity(ReaderActivity.class, intent).setup();
        controller.pause().stop();

        assertTrue(new AppSettings(context).lastAttemptAt() > 0);
        awaitSyncIdle();
    }

    @Test
    public void openingReaderDoesNotRewriteProgress() throws Exception {
        Library seed = new Library(context);
        Library.Book book = new Library.Book();
        book.id = "b4";
        book.title = "进度不动";
        book.author = "作者丁";
        book.addedAt = 1_700_000_000_000L;
        book.chapterTitles.add("第一章");
        book.chapterTitles.add("第二章");
        book.chapterLengths.add(8);
        book.chapterLengths.add(8);
        book.chapterIndex = 1;
        book.progressAt = 1_700_000_500_000L;
        book.lastReadAt = book.progressAt;
        seed.books.add(book);
        seed.writeChapter(book.id, 0, "第一章正文");
        seed.writeChapter(book.id, 1, "第二章正文");
        seed.save();

        Intent intent = new Intent(context, ReaderActivity.class)
                .putExtra(ReaderActivity.EXTRA_BOOK_ID, book.id);
        Robolectric.buildActivity(ReaderActivity.class, intent).setup().get();

        Library.Book loaded = new Library(context).find(book.id);
        assertEquals(1, loaded.chapterIndex);
        assertEquals(1_700_000_500_000L, loaded.progressAt);
    }

    @Test
    public void readerJumpsToNewerSyncedPositionOnResume() throws Exception {
        Library seed = new Library(context);
        Library.Book book = new Library.Book();
        book.id = "b5";
        book.title = "同步跳章";
        book.author = "作者戊";
        book.addedAt = 1_700_000_000_000L;
        book.progressAt = 1_700_000_500_000L;
        book.lastReadAt = book.progressAt;
        book.chapterTitles.add("第一章");
        book.chapterTitles.add("第二章");
        book.chapterLengths.add(8);
        book.chapterLengths.add(8);
        seed.books.add(book);
        seed.writeChapter(book.id, 0, "第一章正文");
        seed.writeChapter(book.id, 1, "第二章正文");
        seed.save();

        Intent intent = new Intent(context, ReaderActivity.class)
                .putExtra(ReaderActivity.EXTRA_BOOK_ID, book.id);
        org.robolectric.android.controller.ActivityController<ReaderActivity> controller =
                Robolectric.buildActivity(ReaderActivity.class, intent).setup();
        ReaderActivity activity = controller.get();

        // 模拟同步把更高章节写进书架
        Library other = new Library(context);
        other.setProgress(other.find(book.id), 1, 0);

        controller.pause().resume();

        assertNotNull(findText(activity, "第二章", false));
    }

    private static TextView findText(Activity activity, String text, boolean contains) {
        return find(activity.getWindow().getDecorView(), text, contains);
    }

    private static TextView findTextContaining(Activity activity, String text) {
        return find(activity.getWindow().getDecorView(), text, true);
    }

    private static TextView find(View root, String text, boolean contains) {
        if (root instanceof TextView) {
            String value = ((TextView) root).getText() == null ? "" : ((TextView) root).getText().toString();
            if (contains ? value.contains(text) : value.equals(text)) {
                return (TextView) root;
            }
        }
        if (root instanceof ViewGroup) {
            ViewGroup group = (ViewGroup) root;
            for (int i = 0; i < group.getChildCount(); i++) {
                TextView found = find(group.getChildAt(i), text, contains);
                if (found != null) {
                    return found;
                }
            }
        }
        return null;
    }

    private static <T extends View> T findView(View root, Class<T> type) {
        if (type.isInstance(root)) {
            return type.cast(root);
        }
        if (root instanceof ViewGroup) {
            ViewGroup group = (ViewGroup) root;
            for (int i = 0; i < group.getChildCount(); i++) {
                T found = findView(group.getChildAt(i), type);
                if (found != null) {
                    return found;
                }
            }
        }
        return null;
    }
}
