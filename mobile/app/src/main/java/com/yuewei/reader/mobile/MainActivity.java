package com.yuewei.reader.mobile;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.Intent;
import android.database.Cursor;
import android.net.Uri;
import android.os.Bundle;
import android.provider.OpenableColumns;
import android.view.Gravity;
import android.view.View;
import android.widget.LinearLayout;
import android.widget.ProgressBar;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.util.List;

/** 书架：自动同步、TXT 导入、在线搜索与删除。 */
public class MainActivity extends Activity {

    private static final int REQUEST_OPEN_TXT = 1001;

    private Library library;
    private AppSettings settings;
    private Ui.Palette palette;
    private String themeKey;
    private LinearLayout bookList;
    private TextView emptyHint;
    private TextView syncStatus;
    private TextView syncButton;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        library = new Library(this);
        settings = new AppSettings(this);
        themeKey = settings.theme();
        palette = Ui.palette(settings.theme());
        buildViews();
        Ui.systemBars(this, palette);
        refreshShelf();
        updateSyncStatus();
    }

    @Override
    protected void onResume() {
        super.onResume();
        settings = new AppSettings(this);
        if (!settings.theme().equals(themeKey)) {
            recreate();
            return;
        }
        library.reload();
        refreshShelf();
        updateSyncStatus();
        autoSync(60_000);
    }

    private void buildViews() {
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setBackgroundColor(palette.bg);
        setContentView(root);

        LinearLayout header = new LinearLayout(this);
        header.setOrientation(LinearLayout.HORIZONTAL);
        header.setGravity(Gravity.CENTER_VERTICAL);
        header.setPadding(dp(20), dp(16), dp(20), dp(8));
        root.addView(header);

        LinearLayout titles = new LinearLayout(this);
        titles.setOrientation(LinearLayout.VERTICAL);
        header.addView(titles, new LinearLayout.LayoutParams(0,
                LinearLayout.LayoutParams.WRAP_CONTENT, 1f));

        TextView title = new TextView(this);
        title.setText("阅微");
        title.setTextSize(26);
        title.setTypeface(Ui.SERIF_BOLD);
        title.setTextColor(palette.ink);
        titles.addView(title);

        syncStatus = new TextView(this);
        syncStatus.setTextSize(12);
        syncStatus.setTextColor(palette.muted);
        syncStatus.setPadding(0, dp(2), 0, 0);
        titles.addView(syncStatus);

        TextView settingsButton = Ui.pill(this, "设置", false, palette);
        settingsButton.setOnClickListener(v -> startActivity(new Intent(this, SettingsActivity.class)));
        header.addView(settingsButton);

        LinearLayout actions = new LinearLayout(this);
        actions.setOrientation(LinearLayout.HORIZONTAL);
        actions.setPadding(dp(16), dp(6), dp(16), dp(6));
        root.addView(actions);

        TextView importButton = actionButton("导入 TXT", true);
        importButton.setOnClickListener(v -> openTxt());
        actions.addView(importButton);

        TextView sources = actionButton("书源", false);
        sources.setOnClickListener(v -> startActivity(new Intent(this, SourcesActivity.class)));
        actions.addView(sources);

        TextView search = actionButton("搜索", false);
        search.setOnClickListener(v -> startActivity(new Intent(this, SearchActivity.class)));
        actions.addView(search);

        syncButton = actionButton("同步", false);
        syncButton.setOnClickListener(v -> manualSync());
        actions.addView(syncButton);

        emptyHint = new TextView(this);
        emptyHint.setText("书架还是空的\n点「导入 TXT」导入本地文件，或到「搜索」里找一本在线书。");
        emptyHint.setTextSize(14);
        emptyHint.setLineSpacing(dp(4), 1f);
        emptyHint.setTextColor(palette.muted);
        emptyHint.setPadding(dp(24), dp(36), dp(24), dp(12));
        emptyHint.setGravity(Gravity.CENTER_HORIZONTAL);
        root.addView(emptyHint);

        ScrollView scroll = new ScrollView(this);
        scroll.setClipToPadding(false);
        bookList = new LinearLayout(this);
        bookList.setOrientation(LinearLayout.VERTICAL);
        bookList.setPadding(dp(16), dp(4), dp(16), dp(24));
        scroll.addView(bookList);
        root.addView(scroll, new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f));
    }

    private TextView actionButton(String text, boolean primary) {
        TextView view = Ui.pill(this, text, primary, palette);
        view.setTextSize(13);
        view.setPadding(0, dp(9), 0, dp(9));
        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(
                0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f);
        params.setMargins(dp(4), 0, dp(4), 0);
        view.setLayoutParams(params);
        return view;
    }

    private void refreshShelf() {
        bookList.removeAllViews();
        List<Library.Book> visible = SyncManager.sortedBooks(library);
        emptyHint.setVisibility(visible.isEmpty() ? View.VISIBLE : View.GONE);
        for (Library.Book book : visible) {
            bookList.addView(bookCard(book));
        }
    }

    private View bookCard(Library.Book book) {
        LinearLayout card = new LinearLayout(this);
        card.setOrientation(LinearLayout.VERTICAL);
        card.setPadding(dp(16), dp(14), dp(16), dp(14));
        card.setBackground(Ui.ripple(this,
                Ui.round(this, palette.card, 14), 0x14000000));
        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        params.setMargins(0, dp(5), 0, dp(5));
        card.setLayoutParams(params);

        TextView title = new TextView(this);
        title.setText(book.title);
        title.setTextSize(18);
        title.setTypeface(Ui.SERIF_BOLD);
        title.setTextColor(palette.ink);
        title.setSingleLine(true);
        title.setEllipsize(android.text.TextUtils.TruncateAt.END);
        card.addView(title);

        String chapter = book.chapterTitles.isEmpty() ? "无章节"
                : book.chapterTitles.get(Math.min(book.chapterIndex, book.chapterTitles.size() - 1));
        String origin = "ONLINE".equals(book.sourceType) && !book.sourceName.isEmpty()
                ? " · " + book.sourceName : "";
        TextView meta = new TextView(this);
        meta.setText(book.author + origin + " · " + chapter + " · " + SyncManager.percentText(book));
        meta.setTextSize(12);
        meta.setTextColor(palette.muted);
        meta.setPadding(0, dp(6), 0, 0);
        meta.setSingleLine(true);
        meta.setEllipsize(android.text.TextUtils.TruncateAt.END);
        card.addView(meta);

        if (book.percent > 0) {
            ProgressBar bar = new ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal);
            bar.setMax(10000);
            bar.setProgress(Math.max(0, Math.min(10000, book.percent)));
            bar.setProgressTintList(android.content.res.ColorStateList.valueOf(palette.accent));
            bar.setBackgroundTintList(android.content.res.ColorStateList.valueOf(palette.line));
            LinearLayout.LayoutParams barParams = new LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT, dp(3));
            barParams.setMargins(0, dp(10), 0, 0);
            card.addView(bar, barParams);
        }

        card.setOnClickListener(v -> {
            Intent intent = new Intent(this, ReaderActivity.class);
            intent.putExtra(ReaderActivity.EXTRA_BOOK_ID, book.id);
            startActivity(intent);
        });
        card.setOnLongClickListener(v -> {
            Ui.dialog(this, settings.theme())
                    .setTitle("移出书架")
                    .setMessage("删除《" + book.title + "》？其他设备同步后也会删除。")
                    .setPositiveButton("删除", (dialog, which) -> {
                        library.markDeleted(book);
                        refreshShelf();
                        boolean started = SyncManager.autoSync(this, 0, (message, ok) -> {
                            if (ok) {
                                updateSyncStatus();
                            } else if (message != null && !message.isEmpty()) {
                                Toast.makeText(this, message, Toast.LENGTH_LONG).show();
                            }
                        });
                        if (!started) {
                            Toast.makeText(this, "已移出书架，点「同步」同步删除",
                                    Toast.LENGTH_LONG).show();
                        } else {
                            updateSyncStatus();
                        }
                    })
                    .setNegativeButton("取消", null)
                    .show();
            return true;
        });
        return card;
    }

    private void openTxt() {
        Intent intent = new Intent(Intent.ACTION_OPEN_DOCUMENT);
        intent.addCategory(Intent.CATEGORY_OPENABLE);
        intent.setType("text/*");
        startActivityForResult(intent, REQUEST_OPEN_TXT);
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (requestCode != REQUEST_OPEN_TXT || resultCode != RESULT_OK || data == null) {
            return;
        }
        Uri uri = data.getData();
        if (uri == null) {
            return;
        }
        String name = displayName(uri);
        Toast.makeText(this, "正在导入…", Toast.LENGTH_SHORT).show();
        new Thread(() -> {
            try (InputStream in = getContentResolver().openInputStream(uri)) {
                if (in == null) {
                    throw new IllegalStateException("无法读取文件");
                }
                ByteArrayOutputStream out = new ByteArrayOutputStream();
                byte[] buffer = new byte[16384];
                int read;
                while ((read = in.read(buffer)) >= 0) {
                    out.write(buffer, 0, read);
                }
                Library.Book book = TxtImporter.importBook(library, name, out.toByteArray());
                runOnUiThread(() -> {
                    refreshShelf();
                    Toast.makeText(this, "已导入《" + book.title + "》", Toast.LENGTH_SHORT).show();
                    autoSync(0);
                });
            } catch (Exception e) {
                runOnUiThread(() -> Toast.makeText(this,
                        "导入失败：" + e.getMessage(), Toast.LENGTH_LONG).show());
            }
        }, "yuewei-import").start();
    }

    /** 手动同步：按钮进入“同步中”，结束后刷新书架与状态。 */
    private void manualSync() {
        if (SyncManager.running()) {
            Toast.makeText(this, "正在同步，请稍候", Toast.LENGTH_SHORT).show();
            return;
        }
        syncButton.setEnabled(false);
        syncButton.setText("同步中…");
        updateSyncStatus();
        SyncManager.sync(this, (message, ok) -> {
            syncButton.setEnabled(true);
            syncButton.setText("同步");
            library.reload();
            refreshShelf();
            updateSyncStatus();
            Toast.makeText(this, message, Toast.LENGTH_LONG).show();
        });
    }

    /** 自动同步不打断阅读：成功只更新顶部时间，失败才提示。 */
    private void autoSync(long minIntervalMs) {
        boolean started = SyncManager.autoSync(this, minIntervalMs, (message, ok) -> {
            if (ok) {
                library.reload();
                refreshShelf();
                updateSyncStatus();
            } else if (message != null && !message.isEmpty()) {
                Toast.makeText(this, message, Toast.LENGTH_LONG).show();
                updateSyncStatus();
            }
        });
        if (started) {
            updateSyncStatus();
        }
    }

    private void updateSyncStatus() {
        if (syncStatus == null) {
            return;
        }
        syncStatus.setText(SyncManager.running()
                ? "正在同步…" : SyncManager.lastSyncText(this));
    }

    private String displayName(Uri uri) {
        try (Cursor cursor = getContentResolver().query(uri, null, null, null, null)) {
            if (cursor != null && cursor.moveToFirst()) {
                int index = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME);
                if (index >= 0) {
                    return cursor.getString(index);
                }
            }
        } catch (RuntimeException ignored) {
            // 取不到名字时用固定标题
        }
        return "未命名.txt";
    }

    private int dp(int value) {
        return Ui.dp(this, value);
    }
}
