package com.yuewei.reader.mobile;

import android.app.Activity;
import android.app.AlertDialog;
import android.os.Bundle;
import android.view.Gravity;
import android.view.KeyEvent;
import android.view.View;
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
    private TextView titleView;
    private TextView progressView;
    private boolean ready;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
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
        openChapter(book.chapterIndex, book.percent);
        ready = true;
    }

    private void buildViews() {
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setId(View.generateViewId());
        setContentView(root);

        LinearLayout top = new LinearLayout(this);
        top.setOrientation(LinearLayout.HORIZONTAL);
        top.setGravity(Gravity.CENTER_VERTICAL);
        top.setPadding(dp(14), dp(10), dp(14), dp(8));
        top.setTag("bar");

        TextView back = Ui.pill(this, "‹ 书架", false, palette);
        back.setOnClickListener(v -> finish());
        top.addView(back);

        titleView = new TextView(this);
        titleView.setTextSize(15);
        titleView.setSingleLine(true);
        titleView.setEllipsize(android.text.TextUtils.TruncateAt.END);
        titleView.setPadding(dp(12), 0, dp(12), 0);
        top.addView(titleView, new LinearLayout.LayoutParams(
                0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f));

        TextView toc = Ui.pill(this, "目录", false, palette);
        toc.setOnClickListener(v -> showToc());
        top.addView(toc);

        TextView sync = Ui.pill(this, "同步", false, palette);
        sync.setOnClickListener(v -> SyncManager.sync(this, (message, ok) -> {
            Toast.makeText(this, message, Toast.LENGTH_LONG).show();
            if (ok) {
                library.reload();
                Library.Book refreshed = library.find(current[0].id);
                if (refreshed == null || refreshed.deleted) {
                    Toast.makeText(this, "这本书已在其他设备删除", Toast.LENGTH_LONG).show();
                    finish();
                    return;
                }
                current[0] = refreshed;
                applyTheme();
                openChapter(refreshed.chapterIndex, refreshed.percent);
            }
        }));
        top.addView(sync);

        root.addView(top, new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT));

        pageView = new PageView(this);
        pageView.setListener(new PageView.Listener() {
            @Override
            public void onPageChanged(int page, int pages, int percent) {
                progressView.setText((current[0].chapterIndex + 1) + "/" + current[0].chapterTitles.size()
                        + " 章 · " + page + "/" + pages + " 页");
                if (ready) {
                    library.touchProgress(current[0], current[0].chapterIndex, percent);
                }
            }

            @Override
            public boolean onNextChapter() {
                return openChapter(current[0].chapterIndex + 1, 0);
            }

            @Override
            public boolean onPreviousChapter() {
                return openChapter(current[0].chapterIndex - 1, 10000);
            }
        });
        LinearLayout.LayoutParams pageParams = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f);
        root.addView(pageView, pageParams);

        LinearLayout bottom = new LinearLayout(this);
        bottom.setOrientation(LinearLayout.HORIZONTAL);
        bottom.setGravity(Gravity.CENTER_VERTICAL);
        bottom.setPadding(dp(14), dp(8), dp(14), dp(10));
        bottom.setTag("bar");

        TextView prev = Ui.pill(this, "上一页", false, palette);
        prev.setOnClickListener(v -> pageView.previousPage());
        bottom.addView(prev);

        TextView next = Ui.pill(this, "下一页", false, palette);
        next.setOnClickListener(v -> pageView.nextPage());
        LinearLayout.LayoutParams nextParams = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        nextParams.setMargins(dp(8), 0, 0, 0);
        bottom.addView(next, nextParams);

        progressView = new TextView(this);
        progressView.setTextSize(12);
        progressView.setGravity(Gravity.END);
        bottom.addView(progressView, new LinearLayout.LayoutParams(
                0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f));

        root.addView(bottom, new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT));
    }

    private int loadToken;

    private boolean openChapter(int index, int percent) {
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
        // 切章时落盘一次，避免进程被系统回收时丢失整章进度
        library.setProgress(book, index, percent);
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

    private void showToc() {
        Library.Book book = current[0];
        List<String> titles = book.chapterTitles;
        String[] items = titles.toArray(new String[0]);
        Ui.dialog(this, settings.theme())
                .setTitle("目录")
                .setItems(items, (dialog, which) -> openChapter(which, 0))
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
    protected void onPause() {
        super.onPause();
        if (current[0] != null) {
            library.setProgress(current[0], current[0].chapterIndex, pageView.currentPercent());
        }
    }

    @Override
    protected void onStop() {
        super.onStop();
        // 离开阅读页时静默上传进度；30 秒内不重复触发
        if (ready) {
            SyncManager.autoSync(this, 30_000, null);
        }
    }

    private int dp(int value) {
        return Ui.dp(this, value);
    }
}
