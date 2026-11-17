package com.yuewei.reader.mobile;

import android.app.Activity;
import android.content.Intent;
import android.graphics.Color;
import android.os.Bundle;
import android.view.Gravity;
import android.view.View;
import android.widget.Button;
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
    private EditText keyword;
    private LinearLayout results;
    private Button searchButton;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        library = new Library(this);
        sources = new SourceStore(this);
        online = new OnlineService();

        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setBackgroundColor(Color.parseColor("#F6F1E7"));
        setContentView(root);

        LinearLayout header = new LinearLayout(this);
        header.setOrientation(LinearLayout.HORIZONTAL);
        header.setGravity(Gravity.CENTER_VERTICAL);
        header.setPadding(dp(16), dp(12), dp(16), dp(8));
        header.setBackgroundColor(Color.parseColor("#ECE4D4"));

        Button back = new Button(this);
        back.setText("← 书架");
        back.setAllCaps(false);
        back.setOnClickListener(v -> finish());
        header.addView(back);

        TextView title = new TextView(this);
        title.setText("在线搜索");
        title.setTextSize(18);
        title.setTextColor(Color.parseColor("#2F2A24"));
        title.setPadding(dp(12), 0, 0, 0);
        header.addView(title, new LinearLayout.LayoutParams(0,
                LinearLayout.LayoutParams.WRAP_CONTENT, 1f));
        root.addView(header);

        LinearLayout bar = new LinearLayout(this);
        bar.setOrientation(LinearLayout.HORIZONTAL);
        bar.setPadding(dp(16), dp(10), dp(16), dp(6));

        keyword = new EditText(this);
        keyword.setHint("书名或作者");
        keyword.setSingleLine(true);
        bar.addView(keyword, new LinearLayout.LayoutParams(0,
                LinearLayout.LayoutParams.WRAP_CONTENT, 1f));

        searchButton = new Button(this);
        searchButton.setText("搜索");
        searchButton.setAllCaps(false);
        searchButton.setOnClickListener(v -> doSearch());
        bar.addView(searchButton);
        root.addView(bar);

        ScrollView scroll = new ScrollView(this);
        results = new LinearLayout(this);
        results.setOrientation(LinearLayout.VERTICAL);
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
            empty.setTextColor(Color.parseColor("#6B6257"));
            empty.setPadding(dp(20), dp(20), dp(20), dp(20));
            results.addView(empty);
            return;
        }
        for (OnlineService.Hit hit : hits) {
            LinearLayout row = new LinearLayout(this);
            row.setOrientation(LinearLayout.VERTICAL);
            row.setPadding(dp(20), dp(12), dp(20), dp(12));
            row.setBackgroundColor(Color.parseColor("#FBF7EF"));

            TextView name = new TextView(this);
            name.setText(hit.result.title);
            name.setTextSize(16);
            name.setTextColor(Color.parseColor("#2F2A24"));
            row.addView(name);

            TextView meta = new TextView(this);
            meta.setText((hit.result.author == null ? "未知" : hit.result.author)
                    + " · " + hit.source.name
                    + (hit.result.lastChapter == null ? "" : " · " + hit.result.lastChapter));
            meta.setTextSize(11);
            meta.setTextColor(Color.parseColor("#6B6257"));
            row.addView(meta);

            View separator = new View(this);
            separator.setBackgroundColor(Color.parseColor("#E5DCCB"));
            row.addView(separator, new LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT, dp(1)));

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
        return Math.round(value * getResources().getDisplayMetrics().density);
    }
}
