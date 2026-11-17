package com.yuewei.reader.mobile;

import android.app.Activity;
import android.content.Intent;
import android.database.Cursor;
import android.graphics.Color;
import android.net.Uri;
import android.os.Bundle;
import android.provider.OpenableColumns;
import android.view.Gravity;
import android.view.View;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;

/** 书架：导入 TXT、同步、进入阅读。 */
public class MainActivity extends Activity {

    private static final int REQUEST_OPEN_TXT = 1001;

    private Library library;
    private LinearLayout bookList;
    private TextView emptyHint;
    private Button syncButton;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        library = new Library(this);
        buildViews();
        refreshShelf();
    }

    @Override
    protected void onResume() {
        super.onResume();
        library.reload();
        refreshShelf();
    }

    private void buildViews() {
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setBackgroundColor(Color.parseColor("#F6F1E7"));
        setContentView(root);

        LinearLayout header = new LinearLayout(this);
        header.setOrientation(LinearLayout.HORIZONTAL);
        header.setGravity(Gravity.CENTER_VERTICAL);
        header.setPadding(dp(18), dp(14), dp(18), dp(10));
        header.setBackgroundColor(Color.parseColor("#ECE4D4"));

        TextView title = new TextView(this);
        title.setText("阅微");
        title.setTextSize(22);
        title.setTextColor(Color.parseColor("#2F2A24"));
        header.addView(title, new LinearLayout.LayoutParams(0,
                LinearLayout.LayoutParams.WRAP_CONTENT, 1f));

        Button settings = new Button(this);
        settings.setText("设置");
        settings.setAllCaps(false);
        settings.setOnClickListener(v -> startActivity(new Intent(this, SettingsActivity.class)));
        header.addView(settings);

        root.addView(header);

        LinearLayout actions = new LinearLayout(this);
        actions.setOrientation(LinearLayout.HORIZONTAL);
        actions.setPadding(dp(18), dp(10), dp(18), dp(6));

        Button importButton = new Button(this);
        importButton.setText("导入 TXT");
        importButton.setAllCaps(false);
        importButton.setOnClickListener(v -> openTxt());
        actions.addView(importButton);

        Button sources = new Button(this);
        sources.setText("书源");
        sources.setAllCaps(false);
        sources.setOnClickListener(v -> startActivity(new Intent(this, SourcesActivity.class)));
        actions.addView(sources);

        Button search = new Button(this);
        search.setText("搜索");
        search.setAllCaps(false);
        search.setOnClickListener(v -> startActivity(new Intent(this, SearchActivity.class)));
        actions.addView(search);

        syncButton = new Button(this);
        syncButton.setText("同步");
        syncButton.setAllCaps(false);
        syncButton.setOnClickListener(v -> startSync());
        actions.addView(syncButton);
        root.addView(actions);

        emptyHint = new TextView(this);
        emptyHint.setText("书架还是空的，点「导入 TXT」开始阅读。");
        emptyHint.setTextColor(Color.parseColor("#6B6257"));
        emptyHint.setPadding(dp(20), dp(24), dp(20), dp(12));
        root.addView(emptyHint);

        ScrollView scroll = new ScrollView(this);
        bookList = new LinearLayout(this);
        bookList.setOrientation(LinearLayout.VERTICAL);
        scroll.addView(bookList);
        root.addView(scroll, new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f));
    }

    private void refreshShelf() {
        bookList.removeAllViews();
        java.util.List<Library.Book> visible = SyncManager.sortedBooks(library);
        emptyHint.setVisibility(visible.isEmpty() ? View.VISIBLE : View.GONE);
        for (Library.Book book : visible) {
            bookList.addView(bookRow(book));
        }
    }

    private View bookRow(Library.Book book) {
        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.VERTICAL);
        row.setPadding(dp(20), dp(14), dp(20), dp(14));
        row.setBackgroundColor(Color.parseColor("#FBF7EF"));

        TextView title = new TextView(this);
        title.setText(book.title);
        title.setTextSize(18);
        title.setTextColor(Color.parseColor("#2F2A24"));
        row.addView(title);

        TextView meta = new TextView(this);
        String chapter = book.chapterTitles.isEmpty() ? "无章节"
                : book.chapterTitles.get(Math.min(book.chapterIndex, book.chapterTitles.size() - 1));
        String origin = "ONLINE".equals(book.sourceType) && !book.sourceName.isEmpty()
                ? " · " + book.sourceName : "";
        meta.setText(book.author + origin + " · " + chapter + " · " + SyncManager.percentText(book));
        meta.setTextSize(12);
        meta.setTextColor(Color.parseColor("#6B6257"));
        meta.setPadding(0, dp(4), 0, 0);
        row.addView(meta);

        row.setOnClickListener(v -> {
            Intent intent = new Intent(this, ReaderActivity.class);
            intent.putExtra(ReaderActivity.EXTRA_BOOK_ID, book.id);
            startActivity(intent);
        });
        row.setOnLongClickListener(v -> {
            new android.app.AlertDialog.Builder(this)
                    .setTitle("移出书架")
                    .setMessage("删除《" + book.title + "》？其他设备同步后也会删除。")
                    .setPositiveButton("删除", (dialog, which) -> {
                        library.markDeleted(book);
                        refreshShelf();
                    })
                    .setNegativeButton("取消", null)
                    .show();
            return true;
        });

        LinearLayout wrapper = new LinearLayout(this);
        wrapper.setOrientation(LinearLayout.VERTICAL);
        wrapper.setPadding(0, 0, 0, dp(1));
        wrapper.setBackgroundColor(Color.parseColor("#E5DCCB"));
        wrapper.addView(row);
        return wrapper;
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
                });
            } catch (Exception e) {
                runOnUiThread(() -> Toast.makeText(this,
                        "导入失败：" + e.getMessage(), Toast.LENGTH_LONG).show());
            }
        }, "yuewei-import").start();
    }

    private void startSync() {
        syncButton.setEnabled(false);
        SyncManager.sync(this, (message, ok) -> {
            syncButton.setEnabled(true);
            library.reload();
            refreshShelf();
            Toast.makeText(this, message, Toast.LENGTH_LONG).show();
        });
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
        return Math.round(value * getResources().getDisplayMetrics().density);
    }
}
