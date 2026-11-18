package com.yuewei.reader.mobile;

import android.app.Activity;
import android.app.AlertDialog;
import android.graphics.Insets;
import android.graphics.drawable.ColorDrawable;
import android.os.Build;
import android.os.Bundle;
import android.view.DisplayCutout;
import android.view.Gravity;
import android.view.KeyEvent;
import android.view.View;
import android.view.WindowInsets;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;

import java.util.List;

/** 阅读页：按页排版，点击左右、滑动、音量键与按钮都能翻页。 */
public class ReaderActivity extends Activity {

    public static final String EXTRA_BOOK_ID = "bookId";

    private final Library.Book[] current = new Library.Book[1];
    private Library library;
    private AppSettings settings;
    private Ui.Palette palette;
    private PageView pageView;
    private LinearLayout topBar;
    private LinearLayout bottomBar;
    private TextView titleView;
    private TextView progressView;
    /** 上下工具栏当前是否显示；隐藏时正文全屏且系统栏一并隐藏。 */
    private boolean chromeVisible;
    private boolean ready;
    /** 阅读页打开时书籍的进度时间；用于判断同步回来的进度是否比当前页更新。 */
    private long loadedProgressAt;
    /** 用户是否真的翻过页/切过章；只打开书不应写进度，否则会覆盖另一台设备的章节。 */
    private boolean progressDirty;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        // 允许窗口画进屏幕开孔区，隐藏状态栏时顶部才不会出现黑边
        Ui.allowDisplayCutout(this);
        library = new Library(this);
        settings = new AppSettings(this);
        palette = Ui.palette(settings.theme());
        String bookId = getIntent().getStringExtra(EXTRA_BOOK_ID);
        Library.Book book = library.find(bookId);
        if (book == null || book.deleted || book.chapterTitles.isEmpty()) {
            Toast.makeText(this, "这本书没有可读章节", Toast.LENGTH_SHORT).show();
            finish();
            return;
        }
        current[0] = book;
        buildViews();
        applyTheme();
        Ui.systemBars(this, palette);
        setChromeVisible(false);
        openChapter(book.chapterIndex, book.percent, false);
        ready = true;
    }

    private void buildViews() {
        FrameLayout root = new FrameLayout(this);
        root.setId(View.generateViewId());
        setContentView(root);

        // 顶部工具栏只作为浮层，阅读时默认隐藏，不再挤压正文高度
        topBar = new LinearLayout(this);
        topBar.setOrientation(LinearLayout.HORIZONTAL);
        topBar.setGravity(Gravity.CENTER_VERTICAL);
        topBar.setPadding(dp(14), dp(10), dp(14), dp(8));
        topBar.setTag("bar");

        TextView back = Ui.pill(this, "‹ 书架", false, palette);
        back.setOnClickListener(v -> finish());
        topBar.addView(back);

        titleView = new TextView(this);
        titleView.setTextSize(15);
        titleView.setSingleLine(true);
        titleView.setEllipsize(android.text.TextUtils.TruncateAt.END);
        titleView.setPadding(dp(12), 0, dp(12), 0);
        topBar.addView(titleView, new LinearLayout.LayoutParams(
                0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f));

        TextView toc = Ui.pill(this, "目录", false, palette);
        toc.setOnClickListener(v -> showToc());
        topBar.addView(toc);

        TextView sync = Ui.pill(this, "同步", false, palette);
        sync.setOnClickListener(v -> {
            // 先把本页真实阅读位置落盘，再同步；没翻过页则不动，避免覆盖远端更新的进度
            saveDirtyProgress();
            SyncManager.sync(this, (message, ok) -> {
                Toast.makeText(this, message, Toast.LENGTH_LONG).show();
                if (!ok) {
                    return;
                }
                library.reload();
                Library.Book refreshed = library.find(current[0].id);
                if (refreshed == null || refreshed.deleted) {
                    Toast.makeText(this, "这本书已在其他设备删除", Toast.LENGTH_LONG).show();
                    finish();
                    return;
                }
                applySyncedPosition();
            });
        });
        topBar.addView(sync);

        // 底部工具栏同样浮在正文之上，隐藏时不占任何高度
        bottomBar = new LinearLayout(this);
        bottomBar.setOrientation(LinearLayout.HORIZONTAL);
        bottomBar.setGravity(Gravity.CENTER_VERTICAL);
        bottomBar.setPadding(dp(14), dp(8), dp(14), dp(10));
        bottomBar.setTag("bar");

        TextView prev = Ui.pill(this, "上一页", false, palette);
        prev.setOnClickListener(v -> pageView.previousPage());
        bottomBar.addView(prev);

        TextView next = Ui.pill(this, "下一页", false, palette);
        next.setOnClickListener(v -> pageView.nextPage());
        LinearLayout.LayoutParams nextParams = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        nextParams.setMargins(dp(8), 0, 0, 0);
        bottomBar.addView(next, nextParams);

        progressView = new TextView(this);
        progressView.setTextSize(12);
        progressView.setGravity(Gravity.END);
        bottomBar.addView(progressView, new LinearLayout.LayoutParams(
                0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f));

        pageView = new PageView(this);
        root.addView(pageView, new FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT));

        FrameLayout.LayoutParams topParams = new FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.WRAP_CONTENT);
        topParams.gravity = Gravity.TOP;
        root.addView(topBar, topParams);

        FrameLayout.LayoutParams bottomParams = new FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.WRAP_CONTENT);
        bottomParams.gravity = Gravity.BOTTOM;
        root.addView(bottomBar, bottomParams);

        pageView.setListener(new PageView.Listener() {
            @Override
            public void onPageChanged(int page, int pages, int percent, boolean userTurn) {
                progressView.setText((current[0].chapterIndex + 1) + "/" + current[0].chapterTitles.size()
                        + " 章 · " + page + "/" + pages + " 页");
                if (ready && userTurn && percent != current[0].percent) {
                    progressDirty = true;
                    library.touchProgress(current[0], current[0].chapterIndex, percent);
                }
            }

            @Override
            public boolean onNextChapter() {
                return openChapter(current[0].chapterIndex + 1, 0, true);
            }

            @Override
            public boolean onPreviousChapter() {
                return openChapter(current[0].chapterIndex - 1, 10000, true);
            }

            @Override
            public void onToggleChrome() {
                setChromeVisible(!chromeVisible);
            }
        });

        // 隐藏系统栏后正文会画进屏幕开孔区域，按开孔安全区给正文留白
        root.setOnApplyWindowInsetsListener((view, insets) -> {
            applyCutoutInsets(insets);
            return insets;
        });
    }

    private void applyCutoutInsets(WindowInsets insets) {
        int top = 0;
        int bottom = 0;
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            Insets cutout = insets.getInsets(WindowInsets.Type.displayCutout());
            Insets gestures = insets.getInsets(WindowInsets.Type.mandatorySystemGestures());
            top = cutout.top;
            // 底部避开手势条，避免最后一行被手势提示盖住
            bottom = Math.max(cutout.bottom, gestures.bottom);
        } else if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            DisplayCutout cutout = insets.getDisplayCutout();
            if (cutout != null) {
                top = cutout.getSafeInsetTop();
                bottom = cutout.getSafeInsetBottom();
            }
        }
        pageView.setSafeInsets(top, bottom);
    }

    /** 开孔区域由窗口背景填充：阅读时用纸色，呼出工具栏时用栏底色，避免顶部出现色带。 */
    private void applyWindowBackground() {
        if (palette == null) {
            return;
        }
        getWindow().setBackgroundDrawable(
                new ColorDrawable(chromeVisible ? palette.panel : palette.bg));
    }

    /** 阅读时默认隐藏上下工具栏与系统栏，正文占满屏幕；轻点页面中间切换。 */
    private void setChromeVisible(boolean visible) {
        chromeVisible = visible;
        int visibility = visible ? View.VISIBLE : View.GONE;
        if (topBar != null) {
            topBar.setVisibility(visibility);
        }
        if (bottomBar != null) {
            bottomBar.setVisibility(visibility);
        }
        // 工具栏是浮层，显示时让正文避开它占的高度，避免最后一行被切掉一半
        if (pageView != null) {
            pageView.setChromeInsets(visible ? barHeight(topBar) : 0,
                    visible ? barHeight(bottomBar) : 0);
        }
        Ui.immersive(this, !visible);
        applyWindowBackground();
    }

    /** 工具栏尚未完成布局时也能量出高度，保证正文避让值准确。 */
    private int barHeight(View bar) {
        if (bar == null) {
            return 0;
        }
        if (bar.getHeight() > 0) {
            return bar.getHeight();
        }
        int width = getResources().getDisplayMetrics().widthPixels;
        bar.measure(View.MeasureSpec.makeMeasureSpec(width, View.MeasureSpec.EXACTLY),
                View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED));
        return bar.getMeasuredHeight();
    }

    private int loadToken;

    private boolean openChapter(int index, int percent, boolean userAction) {
        Library.Book book = current[0];
        if (index < 0) {
            Toast.makeText(this, "已经是第一章", Toast.LENGTH_SHORT).show();
            return false;
        }
        if (index >= book.chapterTitles.size()) {
            Toast.makeText(this, "已经是最后一章", Toast.LENGTH_SHORT).show();
            return false;
        }
        book.chapterIndex = index;
        book.percent = Math.max(0, Math.min(10000, percent));
        if (userAction) {
            // 只有用户主动切章/选目录才立即落盘；单纯打开书不写进度
            library.setProgress(book, index, book.percent);
        }
        loadedProgressAt = book.progressAt;
        progressDirty = false;
        titleView.setText(book.chapterTitles.get(index));
        int token = ++loadToken;
        if (library.chapterFile(book.id, index).isFile()) {
            try {
                applyText(library.chapterText(book, index), percent);
                return true;
            } catch (Exception e) {
                applyText("章节读取失败：" + e.getMessage(), percent);
                return false;
            }
        }
        if (!"ONLINE".equals(book.sourceType)) {
            applyText("本章内容缺失，请重新导入 TXT。", percent);
            return true;
        }
        pageView.setText("正在加载本章…");
        SourceStore store = new SourceStore(this);
        SourceStore.Source source = store.find(book.sourceUrl);
        if (source == null) {
            applyText("书源已删除，无法加载本章。", percent);
            return true;
        }
        String url = index < book.chapterUrls.size() ? book.chapterUrls.get(index) : "";
        if (url == null || url.isEmpty()) {
            applyText("本章缺少正文地址，可能书源规则已失效。", percent);
            return true;
        }
        new Thread(() -> {
            try {
                String content = new OnlineService().content(source, url);
                library.cacheChapter(book, index, content);
                runOnUiThread(() -> {
                    if (token == loadToken) {
                        applyText(content, percent);
                    }
                });
            } catch (Exception e) {
                runOnUiThread(() -> {
                    if (token == loadToken) {
                        applyText("加载失败：" + e.getMessage(), percent);
                    }
                });
            }
        }, "yuewei-chapter").start();
        return true;
    }

    private void applyText(String text, int percent) {
        pageView.setText(text);
        pageView.goToPercent(percent);
    }

    /** 同步或回到阅读页后，如果书架里的进度比当前页更新，跳到同步回来的章节。 */
    private void applySyncedPosition() {
        if (!ready || current[0] == null || isFinishing()) {
            return;
        }
        library.reload();
        Library.Book refreshed = library.find(current[0].id);
        if (refreshed == null || refreshed.deleted) {
            return;
        }
        int previousChapter = current[0].chapterIndex;
        int previousPercent = current[0].percent;
        loadedProgressAt = Math.max(loadedProgressAt, current[0].progressAt);
        current[0] = refreshed;
        boolean newer = refreshed.progressAt > loadedProgressAt;
        boolean moved = refreshed.chapterIndex != previousChapter
                || Math.abs(refreshed.percent - previousPercent) > 1;
        if (newer && moved) {
            applyTheme();
            openChapter(refreshed.chapterIndex, refreshed.percent, false);
        }
    }

    /** 把用户真正翻页后的位置落盘；没翻过页时不动，避免覆盖另一台设备更新的章节。 */
    private void saveDirtyProgress() {
        if (!progressDirty || current[0] == null || pageView == null) {
            return;
        }
        library.setProgress(current[0], current[0].chapterIndex, pageView.currentPercent());
        loadedProgressAt = current[0].progressAt;
        progressDirty = false;
    }

    private void showToc() {
        Library.Book book = current[0];
        List<String> titles = book.chapterTitles;
        String[] items = titles.toArray(new String[0]);
        Ui.dialog(this, settings.theme())
                .setTitle("目录")
                .setItems(items, (dialog, which) -> openChapter(which, 0, true))
                .setNegativeButton("取消", null)
                .show();
    }

    private void applyTheme() {
        palette = Ui.palette(settings.theme());
        View content = findViewById(android.R.id.content);
        if (content instanceof android.view.ViewGroup) {
            android.view.ViewGroup wrapper = (android.view.ViewGroup) content;
            View rootView = wrapper.getChildAt(0);
            rootView.setBackgroundColor(palette.bg);
            if (rootView instanceof android.view.ViewGroup) {
                setThemeColors((android.view.ViewGroup) rootView);
            }
        }
        pageView.setStyle(settings.fontSize(), settings.lineSpacing(), palette.ink);
        titleView.setTextColor(palette.ink);
        progressView.setTextColor(palette.muted);
        applyWindowBackground();
    }

    private void setThemeColors(android.view.ViewGroup root) {
        for (int i = 0; i < root.getChildCount(); i++) {
            View child = root.getChildAt(i);
            if ("bar".equals(child.getTag())) {
                child.setBackgroundColor(palette.panel);
            }
        }
    }

    @Override
    public boolean onKeyDown(int keyCode, KeyEvent event) {
        if (keyCode == KeyEvent.KEYCODE_VOLUME_DOWN) {
            pageView.nextPage();
            return true;
        }
        if (keyCode == KeyEvent.KEYCODE_VOLUME_UP) {
            pageView.previousPage();
            return true;
        }
        return super.onKeyDown(keyCode, event);
    }

    @Override
    protected void onResume() {
        super.onResume();
        // 从设置页等返回后恢复沉浸状态
        setChromeVisible(chromeVisible);
        applySyncedPosition();
    }

    @Override
    protected void onPause() {
        super.onPause();
        saveDirtyProgress();
    }

    @Override
    protected void onStop() {
        super.onStop();
        // 离开阅读页时静默上传进度；30 秒内不重复触发
        if (ready) {
            saveDirtyProgress();
            SyncManager.autoSync(this, 30_000, (message, ok) -> {
                if (ok) {
                    applySyncedPosition();
                }
            });
        }
    }

    private int dp(int value) {
        return Ui.dp(this, value);
    }
}
