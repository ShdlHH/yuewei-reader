package com.yuewei.reader.mobile;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.Intent;
import android.graphics.Color;
import android.net.Uri;
import android.os.Bundle;
import android.view.Gravity;
import android.view.View;
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
    private Ui.Palette palette;
    private LinearLayout list;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        store = new SourceStore(this);
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
        title.setText("书源");
        title.setTextSize(20);
        title.setTypeface(Ui.SERIF_BOLD);
        title.setTextColor(palette.ink);
        title.setPadding(dp(14), 0, 0, 0);
        header.addView(title, new LinearLayout.LayoutParams(0,
                LinearLayout.LayoutParams.WRAP_CONTENT, 1f));

        TextView importButton = Ui.pill(this, "导入 JSON", true, palette);
        importButton.setOnClickListener(v -> pickJson());
        header.addView(importButton);
        root.addView(header);

        TextView hint = new TextView(this);
        hint.setText("支持 Legado 3.0 单个书源、数组或 {\"bookSources\":[...]}；长按书源可删除。");
        hint.setTextSize(12);
        hint.setTextColor(palette.muted);
        hint.setPadding(dp(20), dp(4), dp(20), dp(8));
        root.addView(hint);

        ScrollView scroll = new ScrollView(this);
        scroll.setClipToPadding(false);
        list = new LinearLayout(this);
        list.setOrientation(LinearLayout.VERTICAL);
        list.setPadding(dp(16), dp(4), dp(16), dp(24));
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
            empty.setTextSize(14);
            empty.setTextColor(palette.muted);
            empty.setPadding(dp(8), dp(28), dp(8), dp(20));
            list.addView(empty);
            return;
        }
        for (SourceStore.Source source : store.sources) {
            LinearLayout row = new LinearLayout(this);
            row.setOrientation(LinearLayout.VERTICAL);
            row.setPadding(dp(16), dp(12), dp(16), dp(12));
            row.setBackground(Ui.ripple(this, Ui.round(this, palette.card, 12), 0x14000000));
            LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
            params.setMargins(0, dp(5), 0, dp(5));
            row.setLayoutParams(params);

            TextView name = new TextView(this);
            name.setText(source.name);
            name.setTextSize(16);
            name.setTextColor(palette.ink);
            row.addView(name);

            TextView url = new TextView(this);
            url.setText(source.url);
            url.setTextSize(11);
            url.setTextColor(palette.muted);
            row.addView(url);

            row.setOnLongClickListener(v -> {
                Ui.dialog(this, new AppSettings(this).theme())
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
        return Ui.dp(this, value);
    }
}
