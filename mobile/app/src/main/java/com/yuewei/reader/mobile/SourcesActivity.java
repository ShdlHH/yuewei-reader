package com.yuewei.reader.mobile;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.Intent;
import android.graphics.Color;
import android.net.Uri;
import android.os.Bundle;
import android.view.Gravity;
import android.view.View;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;

/** 书源管理：导入 Legado 3.0 JSON，长按删除。 */
public class SourcesActivity extends Activity {

    private static final int REQUEST_IMPORT = 2001;

    private SourceStore store;
    private LinearLayout list;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        store = new SourceStore(this);

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
        title.setText("书源");
        title.setTextSize(18);
        title.setTextColor(Color.parseColor("#2F2A24"));
        title.setPadding(dp(12), 0, 0, 0);
        header.addView(title, new LinearLayout.LayoutParams(0,
                LinearLayout.LayoutParams.WRAP_CONTENT, 1f));

        Button importButton = new Button(this);
        importButton.setText("导入 JSON");
        importButton.setAllCaps(false);
        importButton.setOnClickListener(v -> pickJson());
        header.addView(importButton);
        root.addView(header);

        TextView hint = new TextView(this);
        hint.setText("支持 Legado 3.0 单个书源、数组或 {\"bookSources\":[...]}；长按书源可删除。");
        hint.setTextColor(Color.parseColor("#6B6257"));
        hint.setPadding(dp(18), dp(10), dp(18), dp(6));
        root.addView(hint);

        ScrollView scroll = new ScrollView(this);
        list = new LinearLayout(this);
        list.setOrientation(LinearLayout.VERTICAL);
        scroll.addView(list);
        root.addView(scroll, new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f));
        refresh();
    }

    private void refresh() {
        list.removeAllViews();
        if (store.sources.isEmpty()) {
            TextView empty = new TextView(this);
            empty.setText("还没有书源，点右上角导入 JSON。");
            empty.setTextColor(Color.parseColor("#6B6257"));
            empty.setPadding(dp(20), dp(20), dp(20), dp(20));
            list.addView(empty);
            return;
        }
        for (SourceStore.Source source : store.sources) {
            LinearLayout row = new LinearLayout(this);
            row.setOrientation(LinearLayout.VERTICAL);
            row.setPadding(dp(20), dp(12), dp(20), dp(12));
            row.setBackgroundColor(Color.parseColor("#FBF7EF"));

            TextView name = new TextView(this);
            name.setText(source.name);
            name.setTextSize(16);
            name.setTextColor(Color.parseColor("#2F2A24"));
            row.addView(name);

            TextView url = new TextView(this);
            url.setText(source.url);
            url.setTextSize(11);
            url.setTextColor(Color.parseColor("#6B6257"));
            row.addView(url);

            row.setOnLongClickListener(v -> {
                new AlertDialog.Builder(this)
                        .setTitle("删除书源")
                        .setMessage("确定删除「" + source.name + "」？")
                        .setPositiveButton("删除", (dialog, which) -> {
                            store.remove(source);
                            refresh();
                        })
                        .setNegativeButton("取消", null)
                        .show();
                return true;
            });
            list.addView(row);
        }
    }

    private void pickJson() {
        Intent intent = new Intent(Intent.ACTION_OPEN_DOCUMENT);
        intent.addCategory(Intent.CATEGORY_OPENABLE);
        intent.setType("*/*");
        startActivityForResult(intent, REQUEST_IMPORT);
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (requestCode != REQUEST_IMPORT || resultCode != RESULT_OK || data == null) {
            return;
        }
        Uri uri = data.getData();
        if (uri == null) {
            return;
        }
        new Thread(() -> {
            try (InputStream in = getContentResolver().openInputStream(uri)) {
                ByteArrayOutputStream out = new ByteArrayOutputStream();
                byte[] buffer = new byte[16384];
                int read;
                while (in != null && (read = in.read(buffer)) >= 0) {
                    out.write(buffer, 0, read);
                }
                int added = store.importJson(TxtImporter.decode(out.toByteArray()));
                runOnUiThread(() -> {
                    refresh();
                    Toast.makeText(this, added > 0 ? "已导入 " + added + " 个书源" : "没有新增书源",
                            Toast.LENGTH_SHORT).show();
                });
            } catch (Exception e) {
                runOnUiThread(() -> Toast.makeText(this,
                        "导入失败：" + e.getMessage(), Toast.LENGTH_LONG).show());
            }
        }, "yuewei-source-import").start();
    }

    private int dp(int value) {
        return Math.round(value * getResources().getDisplayMetrics().density);
    }
}
