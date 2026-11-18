package com.yuewei.reader.mobile;

import android.app.Activity;
import android.content.Intent;
import android.os.Bundle;
import android.text.InputType;
import android.view.Gravity;
import android.view.View;
import android.widget.EditText;
import android.widget.LinearLayout;
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

        LinearLayout header = new LinearLayout(this);
        header.setOrientation(LinearLayout.HORIZONTAL);
        header.setGravity(Gravity.CENTER_VERTICAL);
        header.setPadding(dp(18), dp(14), dp(18), dp(8));

        TextView back = Ui.pill(this, "‹ 书架", false, palette);
        back.setOnClickListener(v -> finish());
        header.addView(back);

        TextView title = new TextView(this);
        title.setText("在线搜索");
        title.setTextSize(20);
        title.setTypeface(Ui.SERIF_BOLD);
        title.setTextColor(palette.ink);
        title.setPadding(dp(14), 0, 0, 0);
        header.addView(title, new LinearLayout.LayoutParams(0,
                LinearLayout.LayoutParams.WRAP_CONTENT, 1f));
        root.addView(header);

        LinearLayout bar = new LinearLayout(this);
        bar.setOrientation(LinearLayout.HORIZONTAL);
        bar.setGravity(Gravity.CENTER_VERTICAL);
        bar.setPadding(dp(18), dp(4), dp(18), dp(8));

        keyword = new EditText(this);
        keyword.setHint("书名或作者");
        keyword.setHintTextColor(palette.muted);
        keyword.setTextSize(14);
        keyword.setTextColor(palette.ink);
        keyword.setInputType(InputType.TYPE_CLASS_TEXT);
        keyword.setSingleLine(true);
        keyword.setPadding(dp(12), dp(9), dp(12), dp(9));
        keyword.setBackground(Ui.roundStroke(this, palette.card, palette.line, 10));
        bar.addView(keyword, new LinearLayout.LayoutParams(0,
                LinearLayout.LayoutParams.WRAP_CONTENT, 1f));

        searchButton = Ui.pill(this, "搜索", true, palette);
        LinearLayout.LayoutParams buttonParams = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        buttonParams.setMargins(dp(10), 0, 0, 0);
        searchButton.setOnClickListener(v -> doSearch());
        bar.addView(searchButton, buttonParams);
        root.addView(bar);

        ScrollView scroll = new ScrollView(this);
        scroll.setClipToPadding(false);
        results = new LinearLayout(this);
        results.setOrientation(LinearLayout.VERTICAL);
        results.setPadding(dp(16), dp(4), dp(16), dp(24));
        scroll.addView(results);
        root.addView(scroll, new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f));
    }

    private void doSearch() {
        String text = keyword.getText().toString().trim();
        if (text.isEmpty()) {
            Toast.makeText(this, "请输入书名或作者", Toast.LENGTH_SHORT).show();
            return;
        }
        if (sources.sources.isEmpty()) {
            Toast.makeText(this, "请先在「书源」里导入 JSON", Toast.LENGTH_LONG).show();
            return;
        }
        searchButton.setEnabled(false);
        results.removeAllViews();
        Toast.makeText(this, "正在搜索…", Toast.LENGTH_SHORT).show();
        new Thread(() -> {
            try {
                List<OnlineService.Hit> hits = online.search(sources.sources, text);
                runOnUiThread(() -> {
                    searchButton.setEnabled(true);
                    showHits(hits);
                });
            } catch (Exception e) {
                runOnUiThread(() -> {
                    searchButton.setEnabled(true);
                    Toast.makeText(this, "搜索失败：" + e.getMessage(), Toast.LENGTH_LONG).show();
                });
            }
        }, "yuewei-search").start();
    }

    private void showHits(List<OnlineService.Hit> hits) {
        results.removeAllViews();
        if (hits.isEmpty()) {
            TextView empty = new TextView(this);
            empty.setText("没有搜索到结果，换一个关键词或书源试试。");
            empty.setTextSize(14);
            empty.setTextColor(palette.muted);
            empty.setPadding(dp(8), dp(28), dp(8), dp(20));
            results.addView(empty);
            return;
        }
        for (OnlineService.Hit hit : hits) {
            LinearLayout row = new LinearLayout(this);
            row.setOrientation(LinearLayout.VERTICAL);
            row.setPadding(dp(16), dp(12), dp(16), dp(12));
            row.setBackground(Ui.ripple(this, Ui.round(this, palette.card, 12), 0x14000000));
            LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
            params.setMargins(0, dp(5), 0, dp(5));
            row.setLayoutParams(params);

            TextView name = new TextView(this);
            name.setText(hit.result.title);
            name.setTextSize(16);
            name.setTextColor(palette.ink);
            row.addView(name);

            TextView meta = new TextView(this);
            meta.setText((hit.result.author == null ? "未知" : hit.result.author)
                    + " · " + hit.source.name
                    + (hit.result.lastChapter == null ? "" : " · " + hit.result.lastChapter));
            meta.setTextSize(11);
            meta.setTextColor(palette.muted);
            row.addView(meta);

            row.setOnClickListener(v -> openHit(hit));
            results.addView(row);
        }
    }

    private void openHit(OnlineService.Hit hit) {
        Library.Book existing = findExisting(hit);
        if (existing != null) {
            openReader(existing.id);
            return;
        }
        Toast.makeText(this, "正在获取目录…", Toast.LENGTH_SHORT).show();
        new Thread(() -> {
            try {
                BookDetail detail = online.detail(hit.source, hit.result);
                Library.Book book = library.addOnline(hit.source, detail);
                runOnUiThread(() -> {
                    Toast.makeText(this, "已加入书架", Toast.LENGTH_SHORT).show();
                    openReader(book.id);
                });
            } catch (Exception e) {
                runOnUiThread(() -> Toast.makeText(this,
                        "获取目录失败：" + e.getMessage(), Toast.LENGTH_LONG).show());
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
