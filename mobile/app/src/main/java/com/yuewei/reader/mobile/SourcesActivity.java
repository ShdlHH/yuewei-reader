package com.yuewei.reader.mobile;

import android.app.Activity;
import android.app.AlertDialog;
import android.app.Dialog;
import android.content.Intent;
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

/** 书源管理：导入 Legado 3.0 JSON，长按或点击管理。 */
public class SourcesActivity extends Activity {

    private static final int REQUEST_IMPORT = 2001;

    private SourceStore store;
    private Ui.Palette palette;
    private String theme;
    private LinearLayout list;
    private TextView countView;
    private TextView importButton;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        store = new SourceStore(this);
        theme = new AppSettings(this).theme();
        palette = Ui.palette(theme);

        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setBackgroundColor(palette.bg);
        setContentView(root);
        Ui.systemBars(this, palette);

        importButton = Ui.pillCompact(this, "导入 JSON", true, palette);
        importButton.setOnClickListener(v -> pickJson());

        countView = new TextView(this);
        countView.setTextSize(10.5f);
        countView.setTextColor(palette.muted);

        LinearLayout header = Ui.pageHeader(this, palette, "书源", null, importButton, this::finish);
        root.addView(header);

        LinearLayout hintRow = new LinearLayout(this);
        hintRow.setOrientation(LinearLayout.HORIZONTAL);
        hintRow.setGravity(Gravity.CENTER_VERTICAL);
        hintRow.setPadding(dp(Ui.SPACE_PAGE), 0, dp(Ui.SPACE_PAGE), dp(8));

        TextView hint = new TextView(this);
        hint.setText("支持 Legado 3.0 · 长按管理");
        hint.setTextSize(Ui.TEXT_META);
        hint.setTextColor(palette.muted);
        hintRow.addView(hint, new LinearLayout.LayoutParams(
                0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f));

        TextView help = new TextView(this);
        help.setText("?");
        help.setTextSize(10);
        help.setTextColor(palette.muted);
        help.setGravity(Gravity.CENTER);
        help.setBackground(Ui.roundStroke(this, palette.card, palette.line, 999));
        help.setClickable(true);
        help.setOnClickListener(v -> showHelp());
        hintRow.addView(help, new LinearLayout.LayoutParams(dp(20), dp(20)));
        root.addView(hintRow);

        ScrollView scroll = new ScrollView(this);
        scroll.setClipToPadding(false);
        list = new LinearLayout(this);
        list.setOrientation(LinearLayout.VERTICAL);
        list.setPadding(dp(Ui.SPACE_PAGE), dp(0), dp(Ui.SPACE_PAGE), dp(24));
        scroll.addView(list);
        root.addView(scroll, new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f));
        refresh();
    }

    private void refresh() {
        list.removeAllViews();
        int enabled = 0;
        for (SourceStore.Source source : store.sources) {
            if (source.enabled) {
                enabled++;
            }
        }
        countView.setText("共 " + store.sources.size() + " · 启用 " + enabled);

        if (store.sources.isEmpty()) {
            showEmpty();
            return;
        }
        for (SourceStore.Source source : store.sources) {
            list.addView(sourceRow(source));
            list.addView(divider());
        }
    }

    private View sourceRow(SourceStore.Source source) {
        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.setPadding(0, dp(12), 0, dp(12));
        row.setBackground(Ui.ripple(this, Ui.round(this, 0x00000000, 0), 0x14000000));
        row.setClickable(true);

        View dot = new View(this);
        dot.setBackground(Ui.round(this, source.enabled ? palette.accent
                : Ui.blend(palette.muted, palette.line, 0.5f), 999));
        LinearLayout.LayoutParams dotParams = new LinearLayout.LayoutParams(dp(6), dp(6));
        dotParams.setMargins(0, 0, dp(10), 0);
        row.addView(dot, dotParams);

        LinearLayout info = new LinearLayout(this);
        info.setOrientation(LinearLayout.VERTICAL);

        TextView name = new TextView(this);
        name.setText(Ui.clean(source.name));
        name.setTextSize(Ui.TEXT_ROW);
        name.setTextColor(source.enabled ? palette.ink : palette.muted);
        name.setSingleLine(true);
        name.setEllipsize(android.text.TextUtils.TruncateAt.END);
        info.addView(name);

        TextView host = new TextView(this);
        host.setText(hostOf(source.url) + " · " + (source.enabled ? "参与搜索" : "已停用"));
        host.setTextSize(Ui.TEXT_META);
        host.setTypeface(android.graphics.Typeface.MONOSPACE);
        host.setTextColor(palette.muted);
        host.setPadding(0, dp(2), 0, 0);
        host.setSingleLine(true);
        host.setEllipsize(android.text.TextUtils.TruncateAt.END);
        info.addView(host);

        row.addView(info, new LinearLayout.LayoutParams(
                0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f));

        TextView state = new TextView(this);
        state.setText(source.enabled ? "启用" : "停用");
        state.setTextSize(10.5f);
        state.setTextColor(source.enabled ? palette.muted
                : Ui.blend(palette.muted, palette.line, 0.4f));
        row.addView(state);

        row.setOnClickListener(v -> showSourceActions(source));
        row.setOnLongClickListener(v -> {
            showSourceActions(source);
            return true;
        });
        return row;
    }

    private View divider() {
        View divider = new View(this);
        divider.setBackgroundColor(palette.line);
        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, dp(1));
        params.setMargins(dp(16), 0, 0, 0);
        divider.setLayoutParams(params);
        return divider;
    }

    private void showEmpty() {
        LinearLayout box = new LinearLayout(this);
        box.setOrientation(LinearLayout.VERTICAL);
        box.setGravity(Gravity.CENTER_HORIZONTAL);
        box.setPadding(0, dp(52), 0, 20);

        TextView seal = new TextView(this);
        seal.setText("源");
        seal.setTextSize(20);
        seal.setTypeface(Ui.SERIF_BOLD);
        seal.setTextColor(0xFFFFF6EA);
        seal.setGravity(Gravity.CENTER);
        seal.setBackground(Ui.round(this, palette.accent, 6));
        LinearLayout.LayoutParams sealParams = new LinearLayout.LayoutParams(dp(48), dp(48));
        sealParams.setMargins(0, 0, 0, dp(14));
        box.addView(seal, sealParams);

        TextView title = new TextView(this);
        title.setText("还没有书源");
        title.setTextSize(Ui.TEXT_BOOK);
        title.setTypeface(Ui.SERIF_BOLD);
        title.setTextColor(palette.ink);
        box.addView(title);

        TextView hint = new TextView(this);
        hint.setText("导入 Legado 3.0 JSON 后即可在线搜索。");
        hint.setTextSize(Ui.TEXT_HINT);
        hint.setTextColor(palette.muted);
        hint.setGravity(Gravity.CENTER);
        hint.setPadding(0, dp(8), 0, 0);
        box.addView(hint);

        TextView action = Ui.pill(this, "导入 JSON", true, palette);
        action.setOnClickListener(v -> pickJson());
        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        params.setMargins(0, dp(16), 0, 0);
        box.addView(action, params);
        list.addView(box);
    }

    private void showSourceActions(SourceStore.Source source) {
        final Dialog[] holder = new Dialog[1];
        LinearLayout content = new LinearLayout(this);
        content.setOrientation(LinearLayout.VERTICAL);
        content.setPadding(0, 0, 0, dp(8));
        content.addView(Ui.grabber(this, palette));

        LinearLayout heading = new LinearLayout(this);
        heading.setOrientation(LinearLayout.VERTICAL);
        heading.setPadding(dp(Ui.SPACE_PAGE), 0, dp(Ui.SPACE_PAGE), dp(10));

        TextView title = new TextView(this);
        title.setText(source.name);
        title.setTextSize(Ui.TEXT_BOOK);
        title.setTypeface(Ui.SERIF_BOLD);
        title.setTextColor(palette.ink);
        heading.addView(title);

        TextView url = new TextView(this);
        url.setText(source.url);
        url.setTextSize(Ui.TEXT_META);
        url.setTypeface(android.graphics.Typeface.MONOSPACE);
        url.setTextColor(palette.muted);
        url.setSingleLine(true);
        url.setEllipsize(android.text.TextUtils.TruncateAt.END);
        url.setPadding(0, dp(3), 0, 0);
        heading.addView(url);
        content.addView(heading);
        content.addView(Ui.hairline(this, palette));

        content.addView(Ui.actionRow(this, palette,
                source.enabled ? "停用书源" : "启用书源",
                source.enabled ? "不再参与搜索，已入架的书仍可阅读" : "重新参与在线搜索",
                false, () -> {
                    if (holder[0] != null) {
                        holder[0].dismiss();
                    }
                    store.setEnabled(source, !source.enabled);
                    refresh();
                }));
        content.addView(Ui.actionRow(this, palette, "删除书源",
                "需要二次确认，已入架的书不受影响", true,
                () -> {
                    if (holder[0] != null) {
                        holder[0].dismiss();
                    }
                    confirmRemove(source);
                }));
        content.addView(Ui.actionRow(this, palette, "取消", null, false,
                () -> {
                    if (holder[0] != null) {
                        holder[0].dismiss();
                    }
                }));

        Dialog dialog = Ui.sheet(this, palette, content, 0);
        holder[0] = dialog;
        dialog.show();
    }

    private void confirmRemove(SourceStore.Source source) {
        Ui.dialog(this, theme)
                .setTitle("删除书源")
                .setMessage("确定删除「" + source.name + "」？")
                .setPositiveButton("删除", (dialog, which) -> {
                    store.remove(source);
                    refresh();
                })
                .setNegativeButton("取消", null)
                .show();
    }

    private void showHelp() {
        Ui.dialog(this, theme)
                .setTitle("书源说明")
                .setMessage("支持 Legado 3.0 的单个书源、数组，或 {\"bookSources\":[...]}。\n\n"
                        + "点击书源可以启用 / 停用或删除；停用后不再参与搜索，"
                        + "已加入书架的书仍可正常阅读。")
                .setPositiveButton("知道了", null)
                .show();
    }

    private String hostOf(String url) {
        try {
            String host = Uri.parse(url).getHost();
            return host == null || host.isBlank() ? url : host;
        } catch (RuntimeException e) {
            return url;
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
