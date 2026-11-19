package com.yuewei.reader.mobile;

import android.animation.ValueAnimator;
import android.app.Activity;
import android.app.AlertDialog;
import android.app.Dialog;
import android.content.Intent;
import android.content.SharedPreferences;
import android.graphics.Insets;
import android.graphics.drawable.ClipDrawable;
import android.graphics.drawable.ColorDrawable;
import android.graphics.drawable.Drawable;
import android.graphics.drawable.GradientDrawable;
import android.graphics.drawable.LayerDrawable;
import android.os.Build;
import android.os.Bundle;
import android.text.Editable;
import android.text.InputType;
import android.text.TextWatcher;
import android.view.DisplayCutout;
import android.view.Gravity;
import android.view.KeyEvent;
import android.view.View;
import android.view.ViewGroup;
import android.view.WindowInsets;
import android.view.WindowManager;
import android.view.animation.PathInterpolator;
import android.widget.ArrayAdapter;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.ListView;
import android.widget.SeekBar;
import android.widget.Switch;
import android.widget.TextView;
import android.widget.Toast;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/** 阅读页：按页排版，点击左右、滑动、音量键与按钮都能翻页。 */
public class ReaderActivity extends Activity {

    public static final String EXTRA_BOOK_ID = "bookId";
    private static final int REQUEST_IMPORT_FONT = 3001;

    private final Library.Book[] current = new Library.Book[1];
    private Library library;
    private AppSettings settings;
    private Ui.Palette palette;
    private PageView pageView;
    private FrameLayout rootView;
    private LinearLayout topBar;
    private LinearLayout bottomArea;
    private LinearLayout bottomBar;
    private LinearLayout footer;
    private TextView titleView;
    private TextView titleChapter;
    private TextView typePreview;
    private TextView footerChapter;
    private TextView footerPage;
    private TextView chapterMeta;
    private SeekBar chapterBar;
    /** 底部面板的符号与文字，主题切换时统一重新着色。 */
    private final List<TextView> panelIcons = new ArrayList<>();
    private final List<TextView> panelLabels = new ArrayList<>();
    /** 屏幕开孔与手势区，工具栏显隐时据此重新计算底部留白。 */
    private int cutoutTop;
    private int cutoutBottom;
    private int gestureBottom;
    private int statusTop;
    /** 工具栏当前是否显示；隐藏时正文全屏且系统栏一并隐藏。 */
    private boolean chromeVisible;
    /** 页脚预留高度：只测量一次，呼出面板时正文不会重排。 */
    private int footerReservedHeight;
    private boolean ready;
    /** 正在后台预加载的下一章序号，-1 表示没有；只在阅读线程与界面线程间传值。 */
    private volatile int prefetchingChapter = -1;
    /** 阅读页打开时书籍的进度时间；用于判断同步回来的进度是否比当前页更新。 */
    private long loadedProgressAt;
    /** 用户是否真的翻过页/切过章；只打开书不应写进度，否则会覆盖另一台设备的章节。 */
    private boolean progressDirty;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        // 阅读期间保持屏幕常亮，离开页面时在 onDestroy 清除
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        // 允许窗口画进屏幕开孔区，隐藏状态栏时顶部才不会出现黑边
        Ui.allowDisplayCutout(this);
        // 边到边：工具栏显隐不改变正文区域，面板保持悬浮
        Ui.edgeToEdge(this);
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
        maybeShowChromeHint();
    }

    private void buildViews() {
        FrameLayout root = new FrameLayout(this);
        root.setId(View.generateViewId());
        rootView = root;
        setContentView(root);

        // 顶部工具栏：返回 + 书名 / 章节 + 目录 / Aa，默认隐藏，点屏幕中间呼出
        topBar = new LinearLayout(this);
        topBar.setOrientation(LinearLayout.HORIZONTAL);
        topBar.setGravity(Gravity.CENTER_VERTICAL);
        topBar.setPadding(dp(14), dp(10), dp(14), dp(9));
        topBar.setBackground(chromeBackground(false));
        topBar.setTag("bar");

        TextView back = Ui.circleButton(this, palette, "←");
        back.setOnClickListener(v -> finish());
        topBar.addView(back);

        LinearLayout titleStack = new LinearLayout(this);
        titleStack.setOrientation(LinearLayout.VERTICAL);

        titleView = new TextView(this);
        titleView.setTextSize(15);
        titleView.setTypeface(Ui.SERIF_BOLD);
        titleView.setLetterSpacing(0.05f);
        titleView.setTextColor(palette.ink);
        titleView.setSingleLine(true);
        titleView.setEllipsize(android.text.TextUtils.TruncateAt.END);
        titleView.setText(current[0].title);
        titleStack.addView(titleView);

        titleChapter = new TextView(this);
        titleChapter.setTextSize(10.5f);
        titleChapter.setLetterSpacing(0.04f);
        titleChapter.setTextColor(palette.muted);
        titleChapter.setSingleLine(true);
        titleChapter.setEllipsize(android.text.TextUtils.TruncateAt.END);
        titleChapter.setPadding(0, dp(2), 0, 0);
        titleStack.addView(titleChapter);

        LinearLayout.LayoutParams titleParams = new LinearLayout.LayoutParams(
                0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f);
        titleParams.setMargins(dp(10), 0, dp(8), 0);
        topBar.addView(titleStack, titleParams);

        TextView toc = Ui.pillCompact(this, "目录", false, palette);
        toc.setOnClickListener(v -> showToc());
        topBar.addView(toc);

        TextView type = Ui.pillCompact(this, "Aa", false, palette);
        type.setOnClickListener(v -> showTypePanel());
        LinearLayout.LayoutParams typeParams = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        typeParams.setMargins(dp(4), 0, 0, 0);
        topBar.addView(type, typeParams);

        // 常驻页脚：章节名 + 页码/整书进度
        footer = new LinearLayout(this);
        footer.setOrientation(LinearLayout.HORIZONTAL);
        footer.setGravity(Gravity.CENTER_VERTICAL);
        footer.setPadding(dp(24), dp(7), dp(24), dp(6));
        footer.setBackgroundColor(palette.bg);

        footerChapter = new TextView(this);
        footerChapter.setTextSize(11);
        footerChapter.setLetterSpacing(0.06f);
        footerChapter.setSingleLine(true);
        footerChapter.setEllipsize(android.text.TextUtils.TruncateAt.END);
        footerChapter.setTextColor(palette.muted);
        footer.addView(footerChapter, new LinearLayout.LayoutParams(
                0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f));

        footerPage = new TextView(this);
        footerPage.setTextSize(11);
        footerPage.setTypeface(android.graphics.Typeface.MONOSPACE);
        footerPage.setLetterSpacing(0.04f);
        footerPage.setTextColor(palette.muted);
        footerPage.setGravity(Gravity.END);
        footerPage.setPadding(dp(12), 0, 0, 0);
        footer.addView(footerPage, new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT));

        // 底部面板：章节信息 + 滑杆 + 目录/界面/同步/设置
        bottomBar = new LinearLayout(this);
        bottomBar.setOrientation(LinearLayout.VERTICAL);
        bottomBar.setPadding(dp(16), dp(9), dp(16), dp(10));
        bottomBar.setBackground(chromeBackground(true));
        bottomBar.setTag("panel");

        chapterMeta = new TextView(this);
        chapterMeta.setTextSize(10);
        chapterMeta.setLetterSpacing(0.08f);
        chapterMeta.setTextColor(palette.muted);
        chapterMeta.setPadding(dp(1), 0, dp(1), dp(7));
        bottomBar.addView(chapterMeta);

        LinearLayout chapterRow = new LinearLayout(this);
        chapterRow.setOrientation(LinearLayout.HORIZONTAL);
        chapterRow.setGravity(Gravity.CENTER_VERTICAL);
        chapterRow.setPadding(0, 0, 0, dp(8));

        TextView prevChapter = chapterButton("上一章");
        prevChapter.setOnClickListener(v -> openChapter(current[0].chapterIndex - 1, 0, true));
        chapterRow.addView(prevChapter);

        chapterBar = new SeekBar(this);
        chapterBar.setMax(Math.max(0, current[0].chapterTitles.size() - 1));
        chapterBar.setProgress(Math.min(current[0].chapterIndex, chapterBar.getMax()));
        chapterBar.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            @Override
            public void onProgressChanged(SeekBar seekBar, int progress, boolean fromUser) {
                if (fromUser) {
                    openChapter(progress, 0, true);
                }
            }

            @Override
            public void onStartTrackingTouch(SeekBar seekBar) {
            }

            @Override
            public void onStopTrackingTouch(SeekBar seekBar) {
            }
        });
        LinearLayout.LayoutParams sliderParams = new LinearLayout.LayoutParams(
                0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f);
        sliderParams.setMargins(dp(8), 0, dp(8), 0);
        chapterRow.addView(chapterBar, sliderParams);

        TextView nextChapter = chapterButton("下一章");
        nextChapter.setOnClickListener(v -> openChapter(current[0].chapterIndex + 1, 0, true));
        chapterRow.addView(nextChapter);

        bottomBar.addView(chapterRow, new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT));

        LinearLayout items = new LinearLayout(this);
        items.setOrientation(LinearLayout.HORIZONTAL);
        addPanelItem(items, "☰", "目录", this::showToc);
        addPanelItem(items, "Aa", "界面", this::showTypePanel);
        addPanelItem(items, "↻", "同步", this::manualSync);
        addPanelItem(items, "⚙", "设置",
                () -> startActivity(new Intent(this, SettingsActivity.class)));
        bottomBar.addView(items, new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT));

        bottomArea = new LinearLayout(this);
        bottomArea.setOrientation(LinearLayout.VERTICAL);
        bottomArea.addView(footer, new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT));
        bottomArea.addView(bottomBar, new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT));

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
        root.addView(bottomArea, bottomParams);

        pageView.setListener(new PageView.Listener() {
            @Override
            public void onPageChanged(int page, int pages, int percent, boolean userTurn) {
                footerPage.setText(page + "/" + pages + " · " + overallPercent(page, pages));
                updateChapterMeta(page, pages);
                if (ready && userTurn && percent != current[0].percent) {
                    progressDirty = true;
                    library.touchProgress(current[0], current[0].chapterIndex, percent);
                }
                if (userTurn && page >= pages - 1) {
                    maybePrefetchNext();
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
                animateChrome(!chromeVisible);
            }
        });

        // 隐藏系统栏后正文会画进屏幕开孔区域，按开孔与手势安全区给正文留白
        root.setOnApplyWindowInsetsListener((view, insets) -> {
            applyCutoutInsets(insets);
            return insets;
        });
    }

    /** 阅读页工具栏上的纯文字按钮。 */
    private TextView chromeText(String text, float sizeSp) {
        TextView view = new TextView(this);
        view.setText(text);
        view.setTextSize(sizeSp);
        view.setTextColor(palette.ink);
        view.setGravity(Gravity.CENTER);
        view.setPadding(dp(8), dp(9), dp(8), dp(9));
        view.setClickable(true);
        return view;
    }

    /** 底部面板的功能入口：上方符号、下方文字。 */
    private void addPanelItem(LinearLayout parent, String glyph, String label, Runnable action) {
        LinearLayout item = new LinearLayout(this);
        item.setOrientation(LinearLayout.VERTICAL);
        item.setGravity(Gravity.CENTER);
        item.setPadding(0, dp(8), 0, dp(2));
        item.setMinimumHeight(dp(44));
        item.setClickable(true);

        TextView icon = new TextView(this);
        icon.setText(glyph);
        icon.setTextSize(19);
        icon.setGravity(Gravity.CENTER);
        icon.setTextColor(palette.ink);
        item.addView(icon);

        TextView text = new TextView(this);
        text.setText(label);
        text.setTextSize(10);
        text.setLetterSpacing(0.08f);
        text.setTextColor(palette.muted);
        text.setGravity(Gravity.CENTER);
        text.setPadding(0, dp(2), 0, 0);
        item.addView(text);

        item.setOnClickListener(v -> action.run());
        panelIcons.add(icon);
        panelLabels.add(text);
        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(
                0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f);
        params.setMargins(dp(3), 0, dp(3), 0);
        parent.addView(item, params);
    }

    /** 底栏上一章 / 下一章：小方块热区，保留文字便于识别。 */
    private TextView chapterButton(String text) {
        TextView view = new TextView(this);
        view.setText(text);
        view.setTextSize(9.5f);
        view.setTextColor(Ui.blend(palette.ink, palette.muted, 0.35f));
        view.setGravity(Gravity.CENTER);
        view.setMinWidth(dp(46));
        view.setMinHeight(dp(34));
        view.setPadding(dp(6), 0, dp(6), 0);
        view.setClickable(true);
        view.setBackground(Ui.ripple(this,
                Ui.roundStroke(this, palette.card, palette.line, 8), 0x1F000000));
        return view;
    }

    private void updateChapterMeta(int page, int pages) {
        if (chapterMeta == null) {
            return;
        }
        int total = Math.max(1, current[0].chapterTitles.size());
        chapterMeta.setText("第 " + (current[0].chapterIndex + 1) + " / " + total
                + " 章 · " + overallPercent(page, pages));
    }

    /** 首次进入阅读页时提示一次：轻点中间呼出工具栏。 */
    private void maybeShowChromeHint() {
        SharedPreferences prefs = getSharedPreferences("yuewei-ui", MODE_PRIVATE);
        if (prefs.getBoolean("chromeHintShown", false)) {
            return;
        }
        prefs.edit().putBoolean("chromeHintShown", true).apply();
        if (rootView == null) {
            return;
        }
        TextView hint = new TextView(this);
        hint.setText("轻点中间 · 呼出工具栏");
        hint.setTextSize(11);
        hint.setLetterSpacing(0.06f);
        hint.setTextColor(palette.ink);
        hint.setPadding(dp(12), dp(7), dp(12), dp(7));
        hint.setBackground(Ui.roundStroke(this, palette.card, palette.line, 999));
        hint.setAlpha(0f);
        hint.setTranslationY(dp(6));
        FrameLayout.LayoutParams params = new FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.WRAP_CONTENT, FrameLayout.LayoutParams.WRAP_CONTENT);
        params.gravity = Gravity.BOTTOM | Gravity.CENTER_HORIZONTAL;
        params.bottomMargin = dp(46);
        rootView.addView(hint, params);
        hint.animate().alpha(1f).translationY(0f).setStartDelay(800).setDuration(260)
                .withEndAction(() -> hint.animate().alpha(0f).setStartDelay(2400).setDuration(300)
                        .withEndAction(() -> rootView.removeView(hint)).start())
                .start();
    }

    /** 整书进度：当前章 + 本章页码折算。 */
    private String overallPercent(int page, int pages) {
        int total = current[0].chapterTitles.size();
        if (total <= 0) {
            return "0.0%";
        }
        double inChapter = pages <= 1 ? 0 : (page - 1) / (double) pages;
        double value = (current[0].chapterIndex + inChapter) / total;
        return String.format(Locale.CHINA, "%.1f%%", Math.min(1, value) * 100);
    }

    private void applyCutoutInsets(WindowInsets insets) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            Insets cutout = insets.getInsets(WindowInsets.Type.displayCutout());
            cutoutTop = cutout.top;
            cutoutBottom = cutout.bottom;
            // decor 会把系统栏 inset 消费成 0，这里取窗口级 inset 才能拿到状态栏与手势区高度
            WindowInsets windowInsets = getWindowManager().getCurrentWindowMetrics().getWindowInsets();
            statusTop = windowInsets.getInsetsIgnoringVisibility(
                    WindowInsets.Type.statusBars()).top;
            Insets gestures = windowInsets.getInsetsIgnoringVisibility(
                    WindowInsets.Type.mandatorySystemGestures());
            Insets nav = windowInsets.getInsetsIgnoringVisibility(
                    WindowInsets.Type.navigationBars());
            gestureBottom = Math.max(gestures.bottom, nav.bottom);
        } else if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            DisplayCutout cutout = insets.getDisplayCutout();
            cutoutTop = cutout == null ? 0 : cutout.getSafeInsetTop();
            cutoutBottom = cutout == null ? 0 : cutout.getSafeInsetBottom();
            gestureBottom = insets.getStableInsetBottom();
            statusTop = insets.getStableInsetTop();
        } else {
            cutoutTop = 0;
            cutoutBottom = 0;
            gestureBottom = 0;
            statusTop = 0;
        }
        applyBottomSpacing();
    }

    /**
     * 正文区域只按开孔与手势区固定留白；工具栏是悬浮层，不参与正文排版，
     * 因此呼出/收起面板时正文不会重排。
     */
    private void applyBottomSpacing() {
        if (pageView == null) {
            return;
        }
        int top = Math.max(cutoutTop, statusTop);
        // 手势区 inset 在部分机型上会返回 0，这里至少保留 20dp 安全边距
        int bottom = Math.max(Math.max(cutoutBottom, gestureBottom), dp(20)) + dp(6);
        if (footerReservedHeight <= 0) {
            footerReservedHeight = barHeight(footer);
        }
        pageView.setInsets(top, bottom, 0, footerReservedHeight);
        if (bottomArea != null) {
            bottomArea.setPadding(0, 0, 0, bottom);
        }
        if (topBar != null) {
            // 始终预留状态栏高度，避免呼出/收起时工具栏高度变化造成抖动
            topBar.setPadding(dp(14), dp(10) + statusTop, dp(14), dp(9));
        }
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
            topBar.animate().cancel();
            topBar.setVisibility(visibility);
            topBar.setTranslationY(0f);
            topBar.setAlpha(1f);
        }
        if (bottomBar != null) {
            bottomBar.animate().cancel();
            bottomBar.setVisibility(visibility);
            bottomBar.setTranslationY(0f);
            bottomBar.setAlpha(1f);
        }
        if (footer != null) {
            footer.animate().cancel();
            // 面板展开时由章节滑杆承担信息，页脚收起
            footer.setVisibility(visible ? View.GONE : View.VISIBLE);
            footer.setAlpha(1f);
            footer.setTranslationY(0f);
        }
        applyBottomSpacing();
        Ui.immersive(this, !visible);
        applyWindowBackground();
    }

    /**
     * 浮层显隐：按提案做纯位移动画，顶部下滑 380ms、底部上滑 420ms，
     * 曲线 cubic-bezier(.22,.8,.24,1)；页脚 200ms 淡出，全程不重排正文。
     */
    private void animateChrome(boolean visible) {
        if (topBar == null || bottomBar == null || footer == null
                || !ValueAnimator.areAnimatorsEnabled()) {
            setChromeVisible(visible);
            return;
        }
        topBar.animate().cancel();
        bottomBar.animate().cancel();
        footer.animate().cancel();
        chromeVisible = visible;
        // 先更新留白再量高度，避免顶部栏从错误位置起滑
        applyBottomSpacing();

        int topOffset = Math.max(dp(56), barHeight(topBar));
        int panelShift = Math.max(0, barHeight(bottomBar));
        PathInterpolator easing = new PathInterpolator(0.22f, 0.8f, 0.24f, 1f);
        topBar.setAlpha(1f);
        bottomBar.setAlpha(1f);

        if (visible) {
            topBar.setVisibility(View.VISIBLE);
            bottomBar.setVisibility(View.VISIBLE);
            topBar.setTranslationY(-topOffset);
            bottomBar.setTranslationY(panelShift);
            topBar.animate().translationY(0f).setDuration(380)
                    .setInterpolator(easing).start();
            bottomBar.animate().translationY(0f).setDuration(420)
                    .setInterpolator(easing).withEndAction(() -> {
                        // 全部落位后再显示系统栏并切换窗口底色，滑动中不出现颜色跳变
                        Ui.immersive(this, false);
                        applyWindowBackground();
                    }).start();
            // 面板出现会把页脚顶高：保持向下偏移，淡出结束后再复位
            footer.setTranslationY(panelShift);
            footer.animate().alpha(0f).setDuration(200)
                    .withEndAction(() -> {
                        footer.setAlpha(1f);
                        footer.setTranslationY(0f);
                        footer.setVisibility(View.GONE);
                    }).start();
        } else {
            topBar.setVisibility(View.VISIBLE);
            bottomBar.setVisibility(View.VISIBLE);
            footer.setVisibility(View.VISIBLE);
            // 面板仍在布局里占位，页脚保持下移量；等面板完全移出后再复位，避免中途跳动
            footer.setTranslationY(panelShift);
            footer.setAlpha(0f);
            footer.animate().alpha(1f).setDuration(200).start();
            topBar.animate().translationY(-topOffset).setDuration(380)
                    .setInterpolator(easing).withEndAction(() -> {
                        topBar.setTranslationY(0f);
                        topBar.setVisibility(View.GONE);
                    }).start();
            bottomBar.animate().translationY(panelShift).setDuration(420)
                    .setInterpolator(easing).withEndAction(() -> {
                        bottomBar.setTranslationY(0f);
                        bottomBar.setVisibility(View.GONE);
                        footer.setTranslationY(0f);
                        // 面板完全移出后再隐藏系统栏并恢复纸色窗口背景
                        Ui.immersive(this, true);
                        applyWindowBackground();
                    }).start();
        }
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

    private int footerHeight() {
        return barHeight(footer);
    }

    /** 手动同步：先落盘本页进度，再上传/合并。 */
    private void manualSync() {
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
            // 切章后立即尝试同步，5 秒节流；失败静默，由下次触发重试
            SyncManager.autoSync(this, 5_000, null);
        }
        loadedProgressAt = book.progressAt;
        progressDirty = false;
        footerChapter.setText(book.chapterTitles.get(index));
        if (titleChapter != null) {
            titleChapter.setText(book.chapterTitles.get(index));
        }
        if (chapterMeta != null) {
            int total = Math.max(1, book.chapterTitles.size());
            chapterMeta.setText("第 " + (index + 1) + " / " + total + " 章 · "
                    + String.format(Locale.CHINA, "%.1f%%", book.percent / 100.0));
        }
        if (chapterBar != null) {
            chapterBar.setProgress(Math.min(index, chapterBar.getMax()));
        }
        int token = ++loadToken;
        if (library.chapterFile(book.id, index).isFile()) {
            try {
                applyText(library.chapterText(book, index), percent);
                maybePrefetchNext();
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
                CacheManager.pruneOnline(library,
                        settings.cacheLimitMb() * 1024L * 1024L, book.id);
                runOnUiThread(() -> {
                    if (token == loadToken) {
                        applyText(content, percent);
                        maybePrefetchNext();
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

    /** 目录：底部 sheet，支持章节搜索，高亮当前章并在打开时定位。 */
    private void showToc() {
        final Library.Book book = current[0];
        LinearLayout container = new LinearLayout(this);
        container.setOrientation(LinearLayout.VERTICAL);
        container.setPadding(0, 0, 0, dp(8));
        container.addView(Ui.grabber(this, palette));

        LinearLayout head = new LinearLayout(this);
        head.setOrientation(LinearLayout.HORIZONTAL);
        head.setGravity(Gravity.CENTER_VERTICAL);
        head.setPadding(dp(Ui.SPACE_PAGE), 0, dp(Ui.SPACE_PAGE), dp(8));

        TextView title = new TextView(this);
        title.setText("目录");
        title.setTextSize(18);
        title.setTypeface(Ui.SERIF_BOLD);
        title.setLetterSpacing(0.08f);
        title.setTextColor(palette.ink);
        head.addView(title, new LinearLayout.LayoutParams(
                0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f));

        TextView count = new TextView(this);
        count.setText((book.chapterIndex + 1) + " / " + book.chapterTitles.size());
        count.setTextSize(10.5f);
        count.setLetterSpacing(0.06f);
        count.setTextColor(palette.muted);
        head.addView(count);
        container.addView(head);

        EditText search = Ui.input(this, palette, "搜索章节", InputType.TYPE_CLASS_TEXT);
        LinearLayout.LayoutParams searchParams = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        searchParams.setMargins(dp(Ui.SPACE_PAGE), 0, dp(Ui.SPACE_PAGE), dp(8));
        container.addView(search, searchParams);

        final List<Integer> filtered = new ArrayList<>();
        final List<String> shown = new ArrayList<>();
        ArrayAdapter<String> adapter = new ArrayAdapter<String>(this,
                android.R.layout.simple_list_item_1, shown) {
            @Override
            public View getView(int position, View convertView, ViewGroup parent) {
                boolean active = filtered.get(position) == book.chapterIndex;

                LinearLayout row = new LinearLayout(ReaderActivity.this);
                row.setOrientation(LinearLayout.HORIZONTAL);
                row.setGravity(Gravity.CENTER_VERTICAL);
                row.setMinimumHeight(dp(48));
                row.setPadding(active ? dp(18) : dp(20), 0, dp(14), 0);
                row.setBackground(active
                        ? Ui.round(ReaderActivity.this, palette.card, 10)
                        : null);

                View bar = new View(ReaderActivity.this);
                bar.setBackgroundColor(palette.accent);
                LinearLayout.LayoutParams barParams = new LinearLayout.LayoutParams(
                        dp(2), dp(26));
                barParams.setMargins(0, 0, dp(10), 0);
                bar.setLayoutParams(barParams);
                bar.setVisibility(active ? View.VISIBLE : View.INVISIBLE);
                row.addView(bar);

                TextView number = new TextView(ReaderActivity.this);
                number.setText(String.valueOf(filtered.get(position) + 1));
                number.setTextSize(10.5f);
                number.setTypeface(android.graphics.Typeface.MONOSPACE);
                number.setTextColor(active ? palette.accent
                        : Ui.blend(palette.muted, palette.line, 0.55f));
                number.setGravity(Gravity.CENTER_VERTICAL);
                row.addView(number, new LinearLayout.LayoutParams(dp(32),
                        LinearLayout.LayoutParams.WRAP_CONTENT));

                TextView name = new TextView(ReaderActivity.this);
                name.setText(book.chapterTitles.get(filtered.get(position)));
                name.setTextSize(15);
                name.setSingleLine(true);
                name.setEllipsize(android.text.TextUtils.TruncateAt.END);
                name.setTextColor(active ? palette.accent : palette.ink);
                name.setTypeface(active ? Ui.SERIF_BOLD : Ui.SERIF);
                row.addView(name, new LinearLayout.LayoutParams(
                        0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f));

                if (active) {
                    TextView now = new TextView(ReaderActivity.this);
                    now.setText("在读");
                    now.setTextSize(9);
                    now.setTextColor(palette.accent);
                    now.setPadding(dp(8), dp(2), dp(8), dp(2));
                    now.setBackground(Ui.roundStroke(ReaderActivity.this, palette.card,
                            Ui.blend(palette.accent, palette.line, 0.55f), 999));
                    row.addView(now);
                }
                return row;
            }
        };
        ListView list = new ListView(this);
        list.setDivider(null);
        list.setDividerHeight(0);
        list.setClipToPadding(false);
        list.setPadding(0, 0, 0, dp(8));
        list.setAdapter(adapter);

        TextView empty = Ui.empty(this, palette, "没有匹配的章节");
        empty.setVisibility(View.GONE);

        final Runnable refresh = () -> {
            filtered.clear();
            filtered.addAll(filterChapters(book.chapterTitles, search.getText().toString()));
            shown.clear();
            for (int index : filtered) {
                shown.add(book.chapterTitles.get(index));
            }
            adapter.notifyDataSetChanged();
            boolean isEmpty = shown.isEmpty();
            empty.setVisibility(isEmpty ? View.VISIBLE : View.GONE);
            list.setVisibility(isEmpty ? View.GONE : View.VISIBLE);
        };
        search.addTextChangedListener(new TextWatcher() {
            @Override
            public void beforeTextChanged(CharSequence s, int start, int count, int after) {
            }

            @Override
            public void onTextChanged(CharSequence s, int start, int before, int count) {
            }

            @Override
            public void afterTextChanged(Editable s) {
                refresh.run();
            }
        });
        refresh.run();

        container.addView(list, new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f));
        container.addView(empty, new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f));

        Dialog dialog = Ui.sheet(this, palette, container, 0.78f);
        list.setOnItemClickListener((parent, view, position, id) -> {
            if (position < filtered.size()) {
                openChapter(filtered.get(position), 0, true);
                dialog.dismiss();
            }
        });
        dialog.setOnShowListener(d -> {
            if (dialog.getWindow() != null) {
                // 先别弹键盘，避免目录一打开就被输入法遮住
                dialog.getWindow().setSoftInputMode(
                        WindowManager.LayoutParams.SOFT_INPUT_STATE_HIDDEN);
            }
            int active = filtered.indexOf(book.chapterIndex);
            if (active >= 0) {
                list.setSelectionFromTop(active, dp(8));
            }
        });
        dialog.show();
    }

    /** 目录过滤：空查询返回全部，其余按章节名包含匹配（忽略大小写）。 */
    static List<Integer> filterChapters(List<String> titles, String query) {
        List<Integer> result = new ArrayList<>();
        String keyword = query == null ? "" : query.trim().toLowerCase(Locale.ROOT);
        for (int i = 0; i < titles.size(); i++) {
            if (keyword.isEmpty()
                    || titles.get(i).toLowerCase(Locale.ROOT).contains(keyword)) {
                result.add(i);
            }
        }
        return result;
    }

    /** 快捷排版：底部 sheet + 内页实时预览。 */
    private void showTypePanel() {
        final Dialog[] holder = new Dialog[1];
        LinearLayout panel = new LinearLayout(this);
        panel.setOrientation(LinearLayout.VERTICAL);
        panel.setPadding(0, 0, 0, dp(12));
        panel.addView(Ui.grabber(this, palette));

        LinearLayout head = new LinearLayout(this);
        head.setOrientation(LinearLayout.HORIZONTAL);
        head.setGravity(Gravity.CENTER_VERTICAL);
        head.setPadding(dp(Ui.SPACE_PAGE), 0, dp(Ui.SPACE_PAGE), dp(6));

        TextView title = new TextView(this);
        title.setText("排版");
        title.setTextSize(18);
        title.setTypeface(Ui.SERIF_BOLD);
        title.setLetterSpacing(0.08f);
        title.setTextColor(palette.ink);
        head.addView(title, new LinearLayout.LayoutParams(
                0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f));

        TextView hint = new TextView(this);
        hint.setText("即时生效");
        hint.setTextSize(10.5f);
        hint.setTextColor(palette.muted);
        head.addView(hint);
        panel.addView(head);

        typePreview = new TextView(this);
        typePreview.setText("天兽岛外，海雾未散。\n“道友客气了。”陈平脚步不停，只在海面上留下一线浅浅的白痕。");
        typePreview.setTextColor(palette.ink);
        typePreview.setPadding(dp(14), dp(12), dp(14), dp(12));
        typePreview.setBackground(Ui.roundStroke(this, palette.card, palette.line, 12));
        updateTypePreview();
        LinearLayout.LayoutParams previewParams = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        previewParams.setMargins(dp(Ui.SPACE_PAGE), 0, dp(Ui.SPACE_PAGE), dp(8));
        panel.addView(typePreview, previewParams);

        LinearLayout sizeRow = new LinearLayout(this);
        sizeRow.setOrientation(LinearLayout.HORIZONTAL);
        sizeRow.setGravity(Gravity.CENTER_VERTICAL);
        sizeRow.setPadding(dp(Ui.SPACE_PAGE), dp(2), dp(Ui.SPACE_PAGE), 0);

        TextView sizeMinus = new TextView(this);
        sizeMinus.setText("A−");
        sizeMinus.setTextSize(12);
        sizeMinus.setTextColor(palette.muted);
        sizeRow.addView(sizeMinus);

        SeekBar sizeBar = Ui.accentSeek(this, palette);
        sizeBar.setMax(20);
        sizeBar.setProgress(settings.fontSize() - 12);
        sizeRow.addView(sizeBar, new LinearLayout.LayoutParams(
                0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f));

        TextView sizePlus = new TextView(this);
        sizePlus.setText("A+");
        sizePlus.setTextSize(12);
        sizePlus.setTextColor(palette.muted);
        sizeRow.addView(sizePlus);

        final TextView sizeValue = new TextView(this);
        sizeValue.setText(String.valueOf(settings.fontSize()));
        sizeValue.setTextSize(10.5f);
        sizeValue.setTypeface(android.graphics.Typeface.MONOSPACE);
        sizeValue.setTextColor(palette.muted);
        sizeValue.setGravity(Gravity.END);
        sizeValue.setMinWidth(dp(24));
        sizeRow.addView(sizeValue);
        panel.addView(sizeRow);

        sizeBar.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            @Override
            public void onProgressChanged(SeekBar seekBar, int progress, boolean fromUser) {
                if (!fromUser) {
                    return;
                }
                int size = 12 + progress;
                sizeValue.setText(String.valueOf(size));
                applyReaderStyle(size, settings.lineSpacing());
                updateTypePreview();
            }

            @Override
            public void onStartTrackingTouch(SeekBar seekBar) {
            }

            @Override
            public void onStopTrackingTouch(SeekBar seekBar) {
            }
        });

        LinearLayout spacingRow = new LinearLayout(this);
        spacingRow.setOrientation(LinearLayout.HORIZONTAL);
        spacingRow.setGravity(Gravity.CENTER_VERTICAL);
        spacingRow.setPadding(dp(Ui.SPACE_PAGE), dp(8), dp(Ui.SPACE_PAGE), 0);

        TextView spacingLabel = new TextView(this);
        spacingLabel.setText("行距");
        spacingLabel.setTextSize(10.5f);
        spacingLabel.setTextColor(palette.muted);
        spacingLabel.setMinWidth(dp(28));
        spacingRow.addView(spacingLabel);

        SeekBar spacingBar = Ui.accentSeek(this, palette);
        spacingBar.setMax(18);
        spacingBar.setProgress(Math.round((settings.lineSpacing() - 1.2f) * 10));
        spacingRow.addView(spacingBar, new LinearLayout.LayoutParams(
                0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f));

        final TextView spacingValue = new TextView(this);
        spacingValue.setText(String.format(Locale.CHINA, "%.1f", settings.lineSpacing()));
        spacingValue.setTextSize(10.5f);
        spacingValue.setTypeface(android.graphics.Typeface.MONOSPACE);
        spacingValue.setTextColor(palette.muted);
        spacingValue.setGravity(Gravity.END);
        spacingValue.setMinWidth(dp(24));
        spacingRow.addView(spacingValue);
        panel.addView(spacingRow);

        spacingBar.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            @Override
            public void onProgressChanged(SeekBar seekBar, int progress, boolean fromUser) {
                if (!fromUser) {
                    return;
                }
                float spacing = 1.2f + progress / 10f;
                spacingValue.setText(String.format(Locale.CHINA, "%.1f", spacing));
                applyReaderStyle(settings.fontSize(), spacing);
                updateTypePreview();
            }

            @Override
            public void onStartTrackingTouch(SeekBar seekBar) {
            }

            @Override
            public void onStopTrackingTouch(SeekBar seekBar) {
            }
        });

        LinearLayout themeSeg = Ui.segmented(this, palette,
                new String[]{"纸白", "夜间", "护眼"},
                new String[]{"paper", "night", "green"},
                settings.theme(), key -> {
                    if (key.equals(settings.theme())) {
                        return;
                    }
                    settings.saveReader(settings.fontSize(), settings.lineSpacing(),
                            key, settings.fontKey());
                    if (holder[0] != null) {
                        holder[0].dismiss();
                    }
                    // 重建页面，让顶栏、底栏与弹层一起换到新主题
                    recreate();
                });
        LinearLayout.LayoutParams segParams = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        segParams.setMargins(dp(Ui.SPACE_PAGE), dp(10), dp(Ui.SPACE_PAGE), 0);
        panel.addView(themeSeg, segParams);

        LinearLayout fontRow = new LinearLayout(this);
        fontRow.setOrientation(LinearLayout.HORIZONTAL);
        fontRow.setGravity(Gravity.CENTER_VERTICAL);
        fontRow.setPadding(dp(Ui.SPACE_PAGE), dp(10), dp(Ui.SPACE_PAGE), 0);

        final TextView fontLabel = Ui.label(this, palette,
                "字体：" + MobileFonts.displayName(this, settings.fontKey()));
        fontLabel.setTextSize(13);
        fontLabel.setPadding(dp(12), dp(10), dp(12), dp(10));
        fontLabel.setBackground(Ui.roundStroke(this, palette.bg, palette.line, Ui.RADIUS_INPUT));
        fontLabel.setOnClickListener(v -> showFontPicker(fontLabel, this::updateTypePreview));
        fontRow.addView(fontLabel, new LinearLayout.LayoutParams(
                0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f));

        TextView importFont = new TextView(this);
        importFont.setText("导入");
        importFont.setTextSize(12);
        importFont.setTextColor(palette.accent);
        importFont.setPadding(dp(12), dp(8), 0, dp(8));
        importFont.setClickable(true);
        importFont.setOnClickListener(v -> openFontFile());
        fontRow.addView(importFont);
        panel.addView(fontRow);

        LinearLayout punctRow = new LinearLayout(this);
        punctRow.setOrientation(LinearLayout.HORIZONTAL);
        punctRow.setGravity(Gravity.CENTER_VERTICAL);
        punctRow.setPadding(dp(Ui.SPACE_PAGE), dp(10), dp(Ui.SPACE_PAGE), dp(2));

        Switch punct = Ui.toggle(this, palette, settings.punctEnabled());
        punctRow.addView(punct);

        TextView punctLabel = new TextView(this);
        punctLabel.setText("标点着色");
        punctLabel.setTextSize(12);
        punctLabel.setTextColor(palette.ink);
        punctLabel.setPadding(dp(10), 0, 0, 0);
        punctRow.addView(punctLabel);

        TextView punctHint = new TextView(this);
        punctHint.setText("对话 · 句号 · 问号 · 叹号");
        punctHint.setTextSize(9.5f);
        punctHint.setTextColor(palette.muted);
        punctHint.setGravity(Gravity.END);
        punctRow.addView(punctHint, new LinearLayout.LayoutParams(
                0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f));
        panel.addView(punctRow);

        punct.setOnCheckedChangeListener((buttonView, checked) -> {
            settings.savePunctuation(checked, settings.punctDialogue(), settings.punctStop(),
                    settings.punctAsk(), settings.punctExcl(),
                    settings.punctEllipsis(), settings.punctParen());
            pageView.setPunctuation(checked, punctRules(), punctColors());
        });

        Dialog dialog = Ui.sheet(this, palette, panel, 0.64f);
        holder[0] = dialog;
        dialog.setOnShowListener(d -> {
            if (dialog.getWindow() != null) {
                dialog.getWindow().setSoftInputMode(
                        WindowManager.LayoutParams.SOFT_INPUT_STATE_HIDDEN);
            }
        });
        dialog.show();
    }

    private void updateTypePreview() {
        if (typePreview == null) {
            return;
        }
        typePreview.setTextSize(settings.fontSize());
        typePreview.setLineSpacing(0, settings.lineSpacing());
        typePreview.setTypeface(MobileFonts.resolve(this, settings.fontKey()));
    }

    /** 字体选择：内置字体与已导入字体。 */
    private void showFontPicker(TextView label, Runnable onChanged) {
        final List<MobileFonts.FontChoice> choices = MobileFonts.choices(this);
        String[] names = new String[choices.size()];
        for (int i = 0; i < choices.size(); i++) {
            names[i] = choices.get(i).name;
        }
        Ui.dialog(this, settings.theme())
                .setTitle("正文字体")
                .setItems(names, (dialog, which) -> {
                    MobileFonts.FontChoice choice = choices.get(which);
                    settings.saveReader(settings.fontSize(), settings.lineSpacing(),
                            settings.theme(), choice.key);
                    pageView.setStyle(settings.fontSize(), settings.lineSpacing(), palette.ink,
                            MobileFonts.resolve(this, choice.key));
                    label.setText("字体：" + choice.name);
                    if (onChanged != null) {
                        onChanged.run();
                    }
                })
                .setNegativeButton("取消", null)
                .show();
    }

    private void openFontFile() {
        Intent intent = new Intent(Intent.ACTION_OPEN_DOCUMENT);
        intent.addCategory(Intent.CATEGORY_OPENABLE);
        intent.setType("*/*");
        startActivityForResult(intent, REQUEST_IMPORT_FONT);
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (requestCode != REQUEST_IMPORT_FONT || resultCode != RESULT_OK
                || data == null || data.getData() == null) {
            return;
        }
        final android.net.Uri uri = data.getData();
        Toast.makeText(this, "正在导入字体…", Toast.LENGTH_SHORT).show();
        new Thread(() -> {
            try {
                String key = MobileFonts.importFont(this, uri);
                runOnUiThread(() -> {
                    settings.saveReader(settings.fontSize(), settings.lineSpacing(),
                            settings.theme(), key);
                    pageView.setStyle(settings.fontSize(), settings.lineSpacing(), palette.ink,
                            MobileFonts.resolve(this, key));
                    updateTypePreview();
                    Toast.makeText(this, "字体已导入", Toast.LENGTH_SHORT).show();
                });
            } catch (Exception e) {
                runOnUiThread(() -> Toast.makeText(this,
                        "导入失败：" + e.getMessage(), Toast.LENGTH_LONG).show());
            }
        }, "yuewei-font").start();
    }

    private void applyReaderStyle(int fontSize, float spacing) {
        settings.saveReader(fontSize, spacing, settings.theme());
        pageView.setStyle(fontSize, spacing, palette.ink,
                MobileFonts.resolve(this, settings.fontKey()));
    }

    private boolean[] punctRules() {
        return new boolean[]{settings.punctDialogue(), settings.punctStop(),
                settings.punctAsk(), settings.punctExcl(),
                settings.punctEllipsis(), settings.punctParen()};
    }

    private int[] punctColors() {
        return new int[]{palette.punctDialogue, palette.punctStop, palette.punctAsk,
                palette.punctExcl, palette.punctEllipsis, palette.punctParen};
    }

    /** 在线阅读时预取下一章，翻到章末再切章就不用等网络。 */
    private void maybePrefetchNext() {
        Library.Book book = current[0];
        if (book == null || book.deleted || !"ONLINE".equals(book.sourceType)) {
            return;
        }
        int next = book.chapterIndex + 1;
        if (next >= book.chapterTitles.size()
                || library.chapterFile(book.id, next).isFile()
                || prefetchingChapter == next) {
            return;
        }
        String url = next < book.chapterUrls.size() ? book.chapterUrls.get(next) : "";
        if (url == null || url.isEmpty()) {
            return;
        }
        SourceStore.Source source = new SourceStore(this).find(book.sourceUrl);
        if (source == null) {
            return;
        }
        prefetchingChapter = next;
        new Thread(() -> {
            try {
                String content = new OnlineService().content(source, url);
                Library.Book latest = library.find(book.id);
                if (latest == null || latest.deleted) {
                    return;
                }
                library.cacheChapter(book, next, content);
                CacheManager.pruneOnline(library,
                        settings.cacheLimitMb() * 1024L * 1024L, book.id);
            } catch (Exception ignored) {
                // 预加载失败不影响当前阅读，下次翻页会再试
            } finally {
                prefetchingChapter = -1;
            }
        }, "yuewei-prefetch").start();
    }

    private void applyTheme() {
        palette = Ui.palette(settings.theme());
        View content = findViewById(android.R.id.content);
        if (content instanceof ViewGroup) {
            ViewGroup wrapper = (ViewGroup) content;
            View rootView = wrapper.getChildAt(0);
            rootView.setBackgroundColor(palette.bg);
            if (rootView instanceof ViewGroup) {
                setThemeColors((ViewGroup) rootView);
            }
        }
        pageView.setPunctuation(settings.punctEnabled(), punctRules(), punctColors());
        pageView.setStyle(settings.fontSize(), settings.lineSpacing(), palette.ink,
                MobileFonts.resolve(this, settings.fontKey()));
        titleView.setTextColor(palette.ink);
        if (titleChapter != null) {
            titleChapter.setTextColor(palette.muted);
        }
        for (int i = 0; i < topBar.getChildCount(); i++) {
            View child = topBar.getChildAt(i);
            if (child instanceof TextView) {
                ((TextView) child).setTextColor(palette.ink);
            }
        }
        if (chapterMeta != null) {
            chapterMeta.setTextColor(palette.muted);
        }
        if (typePreview != null) {
            typePreview.setTextColor(palette.ink);
            typePreview.setBackground(Ui.roundStroke(this, palette.card, palette.line, 12));
        }
        footerChapter.setTextColor(palette.muted);
        footerPage.setTextColor(palette.muted);
        footer.setBackgroundColor(palette.bg);
        if (bottomBar != null) {
            bottomBar.setBackground(chromeBackground(true));
        }
        if (topBar != null) {
            topBar.setBackground(chromeBackground(false));
        }
        for (TextView icon : panelIcons) {
            icon.setTextColor(palette.ink);
        }
        for (TextView label : panelLabels) {
            label.setTextColor(palette.muted);
        }
        styleChapterBar();
        applyWindowBackground();
    }

    private void setThemeColors(android.view.ViewGroup root) {
        for (int i = 0; i < root.getChildCount(); i++) {
            View child = root.getChildAt(i);
            if ("bar".equals(child.getTag())) {
                child.setBackground(chromeBackground(false));
            }
        }
    }

    /** 浮层底色与发丝线：topLine=true 时线在顶部，否则在底部。 */
    private Drawable chromeBackground(boolean topLine) {
        GradientDrawable fill = new GradientDrawable();
        fill.setColor(palette.panel);
        GradientDrawable line = new GradientDrawable();
        line.setColor(palette.line);
        LayerDrawable layers = new LayerDrawable(new Drawable[]{fill, line});
        layers.setLayerGravity(0, Gravity.FILL);
        layers.setLayerHeight(1, dp(1));
        layers.setLayerGravity(1, topLine ? Gravity.TOP : Gravity.BOTTOM);
        return layers;
    }

    /** 章节滑杆按提案画成 2dp 细线 + 12dp 圆点（带 4dp 光晕），不再使用系统粗进度条。 */
    private void styleChapterBar() {
        if (chapterBar == null) {
            return;
        }
        GradientDrawable track = new GradientDrawable();
        track.setColor(palette.line);
        track.setCornerRadius(dp(1));
        GradientDrawable fill = new GradientDrawable();
        fill.setColor(palette.accent);
        fill.setCornerRadius(dp(1));
        ClipDrawable progress = new ClipDrawable(fill, Gravity.LEFT, ClipDrawable.HORIZONTAL);
        LayerDrawable trackLayers = new LayerDrawable(new Drawable[]{track, progress});
        trackLayers.setLayerHeight(0, dp(2));
        trackLayers.setLayerHeight(1, dp(2));
        trackLayers.setLayerGravity(0, Gravity.CENTER_VERTICAL);
        trackLayers.setLayerGravity(1, Gravity.CENTER_VERTICAL);

        int accent = palette.accent;
        GradientDrawable halo = new GradientDrawable();
        halo.setShape(GradientDrawable.OVAL);
        halo.setColor((accent & 0x00FFFFFF) | 0x2E000000);
        halo.setSize(dp(20), dp(20));
        GradientDrawable knob = new GradientDrawable();
        knob.setShape(GradientDrawable.OVAL);
        knob.setColor(accent);
        knob.setSize(dp(12), dp(12));
        LayerDrawable thumb = new LayerDrawable(new Drawable[]{halo, knob});
        thumb.setLayerInset(1, dp(4), dp(4), dp(4), dp(4));

        chapterBar.setProgressDrawable(trackLayers);
        chapterBar.setThumb(thumb);
        chapterBar.setThumbOffset(0);
        chapterBar.setSplitTrack(false);
        chapterBar.setPadding(dp(10), dp(8), dp(10), dp(8));
        chapterBar.setMinimumHeight(dp(20));
        chapterBar.setMaxHeight(dp(20));
        chapterBar.setBackground(null);
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
        // 离开阅读页时静默上传进度；5 秒内不重复触发
        if (ready) {
            saveDirtyProgress();
            SyncManager.autoSync(this, 5_000, (message, ok) -> {
                if (ok) {
                    applySyncedPosition();
                }
            });
        }
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        // 退出阅读页恢复系统息屏设置
        getWindow().clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
    }

    private int dp(int value) {
        return Ui.dp(this, value);
    }
}
