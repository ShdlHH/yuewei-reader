package com.yuewei.reader.mobile;

import android.app.Activity;
import android.content.Intent;
import android.os.Bundle;
import android.text.InputType;
import android.view.Gravity;
import android.view.View;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ProgressBar;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import com.yuewei.reader.domain.BookDetail;

import java.util.List;

/** 在线搜索：从已导入书源搜索，选中后抓目录入架并打开阅读页。 */
public class SearchActivity extends Activity {

    private Library library;
    private SourceStore sources;
    private OnlineService online;
    private Ui.Palette palette;
    private EditText keyword;
    private LinearLayout results;
    private TextView searchButton;
    private TextView sourceCount;
    private LinearLayout progressBox;
    private TextView progressNote;
    private List<OnlineService.Hit> lastHits;
    private boolean fetching;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        library = new Library(this);
        sources = new SourceStore(this);
        online = new OnlineService();
        palette = Ui.palette(new AppSettings(this).theme());

        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setBackgroundColor(palette.bg);
        setContentView(root);
        Ui.systemBars(this, palette);

        sourceCount = new TextView(this);
        sourceCount.setTextSize(10.5f);
        sourceCount.setTextColor(palette.muted);
        sourceCount.setLetterSpacing(0.04f);

        LinearLayout header = Ui.pageHeader(this, palette, "在线搜索", null,
                sourceCount, this::finish);
        root.addView(header);

        LinearLayout bar = new LinearLayout(this);
        bar.setOrientation(LinearLayout.HORIZONTAL);
        bar.setGravity(Gravity.CENTER_VERTICAL);
        bar.setPadding(dp(Ui.SPACE_PAGE), dp(2), dp(Ui.SPACE_PAGE), dp(6));

        keyword = Ui.input(this, palette, "书名或作者", InputType.TYPE_CLASS_TEXT);
        bar.addView(keyword, new LinearLayout.LayoutParams(0,
                LinearLayout.LayoutParams.WRAP_CONTENT, 1f));

        searchButton = Ui.pill(this, "搜索", true, palette);
        LinearLayout.LayoutParams buttonParams = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        buttonParams.setMargins(dp(8), 0, 0, 0);
        searchButton.setOnClickListener(v -> doSearch());
        bar.addView(searchButton, buttonParams);
        root.addView(bar);

        progressBox = new LinearLayout(this);
        progressBox.setOrientation(LinearLayout.VERTICAL);
        progressBox.setPadding(dp(Ui.SPACE_PAGE), 0, dp(Ui.SPACE_PAGE), dp(6));
        progressBox.setVisibility(View.GONE);

        ProgressBar progress = new ProgressBar(this, null,
                android.R.attr.progressBarStyleHorizontal);
        progress.setIndeterminate(true);
        progress.setIndeterminateTintList(android.content.res.ColorStateList.valueOf(palette.accent));
        progressBox.addView(progress, new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, dp(2)));

        progressNote = new TextView(this);
        progressNote.setTextSize(10.5f);
        progressNote.setTextColor(palette.muted);
        progressNote.setPadding(0, dp(5), 0, 0);
        progressBox.addView(progressNote);
        root.addView(progressBox);

        ScrollView scroll = new ScrollView(this);
        scroll.setClipToPadding(false);
        results = new LinearLayout(this);
        results.setOrientation(LinearLayout.VERTICAL);
        results.setPadding(dp(Ui.SPACE_PAGE), dp(4), dp(Ui.SPACE_PAGE), dp(24));
        scroll.addView(results);
        root.addView(scroll, new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f));

        keyword.setOnEditorActionListener((v, actionId, event) -> {
            doSearch();
            return true;
        });
        updateSourceCount();
        showInitialHint();
    }

    @Override
    protected void onResume() {
        super.onResume();
        sources.reload();
        updateSourceCount();
        if (lastHits != null) {
            showHits(lastHits);
        }
    }

    private void updateSourceCount() {
        if (sourceCount != null) {
            sourceCount.setText(sources.sources.size() + " 个书源");
        }
    }

    private void showInitialHint() {
        results.removeAllViews();
        LinearLayout box = new LinearLayout(this);
        box.setOrientation(LinearLayout.VERTICAL);
        box.setGravity(Gravity.CENTER_HORIZONTAL);
        box.setPadding(0, dp(48), 0, 0);

        TextView title = new TextView(this);
        title.setText("输入书名或作者");
        title.setTextSize(Ui.TEXT_BOOK);
        title.setTypeface(Ui.SERIF_BOLD);
        title.setTextColor(palette.ink);
        box.addView(title);

        TextView hint = new TextView(this);
        hint.setText("从已导入的书源里搜索，选中后加入书架。");
        hint.setTextSize(Ui.TEXT_HINT);
        hint.setTextColor(palette.muted);
        hint.setGravity(Gravity.CENTER);
        hint.setPadding(0, dp(8), 0, 0);
        box.addView(hint);
        results.addView(box);
    }

    private void doSearch() {
        String text = keyword.getText().toString().trim();
        if (text.isEmpty()) {
            Toast.makeText(this, "请输入书名或作者", Toast.LENGTH_SHORT).show();
            return;
        }
        sources.reload();
        updateSourceCount();
        if (sources.sources.isEmpty()) {
            showEmpty("还没有书源", "导入 JSON 后即可开始搜索。", "去书源页",
                    () -> startActivity(new Intent(this, SourcesActivity.class)));
            return;
        }
        searchButton.setEnabled(false);
        searchButton.setText("搜索中");
        results.removeAllViews();
        progressBox.setVisibility(View.VISIBLE);
        progressNote.setText("正在搜索 " + sources.sources.size() + " 个书源…");
        new Thread(() -> {
            try {
                List<OnlineService.Hit> hits = online.search(sources.sources, text);
                runOnUiThread(() -> {
                    searchButton.setEnabled(true);
                    searchButton.setText("搜索");
                    progressBox.setVisibility(View.GONE);
                    lastHits = hits;
                    showHits(hits);
                });
            } catch (Exception e) {
                runOnUiThread(() -> {
                    searchButton.setEnabled(true);
                    searchButton.setText("搜索");
                    progressBox.setVisibility(View.GONE);
                    lastHits = null;
                    showEmpty("搜索失败", e.getMessage(), "重试", this::doSearch);
                });
            }
        }, "yuewei-search").start();
    }

    private void showHits(List<OnlineService.Hit> hits) {
        results.removeAllViews();
        if (hits.isEmpty()) {
            String book = keyword.getText().toString().trim();
            showEmpty("没有找到《" + book + "》", "换一个关键词，或确认书源可用。",
                    "换关键词", () -> {
                        keyword.setText("");
                        keyword.requestFocus();
                        showInitialHint();
                    });
            return;
        }
        for (OnlineService.Hit hit : hits) {
            Library.Book existing = findExisting(hit);
            LinearLayout row = new LinearLayout(this);
            row.setOrientation(LinearLayout.HORIZONTAL);
            row.setGravity(Gravity.CENTER_VERTICAL);
            row.setPadding(0, dp(10), 0, dp(10));

            TextView spine = Ui.coverBadge(this, palette, hit.result.title, 30, 42);
            LinearLayout.LayoutParams spineParams = new LinearLayout.LayoutParams(dp(30), dp(42));
            spineParams.setMargins(0, 0, dp(9), 0);
            row.addView(spine, spineParams);

            LinearLayout info = new LinearLayout(this);
            info.setOrientation(LinearLayout.VERTICAL);

            TextView name = new TextView(this);
            name.setText(hit.result.title);
            name.setTextSize(Ui.TEXT_ROW);
            name.setTextColor(palette.ink);
            name.setSingleLine(true);
            name.setEllipsize(android.text.TextUtils.TruncateAt.END);
            info.addView(name);

            TextView meta = new TextView(this);
            meta.setText((hit.result.author == null ? "未知" : hit.result.author)
                    + " · " + Ui.clean(hit.source.name)
                    + (hit.result.lastChapter == null ? "" : " · " + hit.result.lastChapter));
            meta.setTextSize(Ui.TEXT_META);
            meta.setTextColor(palette.muted);
            meta.setPadding(0, dp(2), 0, 0);
            meta.setSingleLine(true);
            meta.setEllipsize(android.text.TextUtils.TruncateAt.END);
            info.addView(meta);

            row.addView(info, new LinearLayout.LayoutParams(
                    0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f));

            if (existing != null) {
                TextView added = new TextView(this);
                added.setText("已加入");
                added.setTextSize(10.5f);
                added.setTextColor(Ui.blend(palette.muted, palette.line, 0.4f));
                added.setPadding(dp(8), 0, 0, 0);
                row.addView(added);
                row.setOnClickListener(v -> openReader(existing.id));
            } else {
                TextView add = Ui.pillCompact(this, "加入", false, palette);
                add.setOnClickListener(v -> openHit(hit, add));
                row.addView(add);
                row.setOnClickListener(v -> openHit(hit, add));
            }

            results.addView(row);
            View divider = new View(this);
            divider.setBackgroundColor(palette.line);
            LinearLayout.LayoutParams dividerParams = new LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT, dp(1));
            dividerParams.setMargins(dp(39), 0, 0, 0);
            divider.setLayoutParams(dividerParams);
            results.addView(divider);
        }
    }

    private void showEmpty(String titleText, String hintText, String actionText, Runnable action) {
        results.removeAllViews();
        LinearLayout box = new LinearLayout(this);
        box.setOrientation(LinearLayout.VERTICAL);
        box.setGravity(Gravity.CENTER_HORIZONTAL);
        box.setPadding(dp(10), dp(52), dp(10), dp(20));

        TextView title = new TextView(this);
        title.setText(titleText);
        title.setTextSize(Ui.TEXT_BOOK);
        title.setTypeface(Ui.SERIF_BOLD);
        title.setTextColor(palette.ink);
        title.setGravity(Gravity.CENTER);
        box.addView(title);

        TextView hint = new TextView(this);
        hint.setText(hintText == null ? "" : hintText);
        hint.setTextSize(Ui.TEXT_HINT);
        hint.setTextColor(palette.muted);
        hint.setGravity(Gravity.CENTER);
        hint.setLineSpacing(dp(4), 1f);
        hint.setPadding(0, dp(8), 0, 0);
        box.addView(hint);

        if (action != null && actionText != null) {
            TextView button = Ui.pill(this, actionText, false, palette);
            LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT);
            params.setMargins(0, dp(16), 0, 0);
            button.setOnClickListener(v -> action.run());
            box.addView(button, params);
        }
        results.addView(box);
    }

    private void openHit(OnlineService.Hit hit, TextView actionView) {
        if (fetching) {
            return;
        }
        Library.Book existing = findExisting(hit);
        if (existing != null) {
            openReader(existing.id);
            return;
        }
        fetching = true;
        actionView.setEnabled(false);
        actionView.setText("获取中…");
        Toast.makeText(this, "正在获取目录…", Toast.LENGTH_SHORT).show();
        new Thread(() -> {
            try {
                BookDetail detail = online.detail(hit.source, hit.result);
                Library.Book book = library.addOnline(hit.source, detail);
                runOnUiThread(() -> {
                    fetching = false;
                    Toast.makeText(this, "已加入书架", Toast.LENGTH_SHORT).show();
                    openReader(book.id);
                });
            } catch (Exception e) {
                runOnUiThread(() -> {
                    fetching = false;
                    actionView.setEnabled(true);
                    actionView.setText("加入");
                    Toast.makeText(this,
                            "获取目录失败：" + e.getMessage(), Toast.LENGTH_LONG).show();
                });
            }
        }, "yuewei-detail").start();
    }

    private Library.Book findExisting(OnlineService.Hit hit) {
        for (Library.Book book : library.books) {
            if (!book.deleted && hit.source.url.equals(book.sourceUrl)
                    && hit.result.bookUrl != null && hit.result.bookUrl.equals(book.bookUrl)) {
                return book;
            }
        }
        return null;
    }

    private void openReader(String bookId) {
        Intent intent = new Intent(this, ReaderActivity.class);
        intent.putExtra(ReaderActivity.EXTRA_BOOK_ID, bookId);
        startActivity(intent);
    }

    private int dp(int value) {
        return Ui.dp(this, value);
    }
}
