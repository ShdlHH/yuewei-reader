package com.yuewei.reader.mobile;

import android.app.Activity;
import android.content.Intent;
import android.net.Uri;
import android.os.Bundle;
import android.text.InputType;
import android.text.SpannableString;
import android.text.style.ForegroundColorSpan;
import android.view.Gravity;
import android.view.View;
import android.widget.CheckBox;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.SeekBar;
import android.widget.Switch;
import android.widget.TextView;
import android.widget.Toast;

import java.util.List;
import java.util.Locale;

/** 设置：WebDAV 同步参数与阅读排版。 */
public class SettingsActivity extends Activity {

    private static final int REQUEST_IMPORT_FONT = 2001;

    private AppSettings settings;
    private Ui.Palette palette;
    private CheckBox syncEnabled;
    private CheckBox autoSync;
    private EditText syncUrl;
    private EditText syncAccount;
    private EditText syncPassword;
    private EditText syncDir;
    private SeekBar fontSizeBar;
    private TextView fontSizeValue;
    private SeekBar lineSpacingBar;
    private TextView lineSpacingValue;
    private TextView syncHint;
    private TextView fontLabel;
    private TextView preview;
    private String fontKey;
    private String selectedTheme;
    private Switch punctEnabled;
    private final CheckBox[] punctBoxes = new CheckBox[6];
    private boolean punctReady;
    private EditText cacheLimit;
    private TextView cacheUsage;
    private Ui.ProgressLine cacheLine;
    private Library cacheLibrary;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        settings = new AppSettings(this);
        palette = Ui.palette(settings.theme());
        selectedTheme = settings.theme();
        fontKey = settings.fontKey();
        cacheLibrary = new Library(this);

        FrameLayout root = new FrameLayout(this);
        root.setBackgroundColor(palette.bg);
        setContentView(root);

        ScrollView scroll = new ScrollView(this);
        scroll.setFillViewport(true);
        scroll.setClipToPadding(false);
        LinearLayout content = new LinearLayout(this);
        content.setOrientation(LinearLayout.VERTICAL);
        content.setPadding(0, 0, 0, dp(86));
        scroll.addView(content);
        root.addView(scroll, new FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT));

        TextView version = new TextView(this);
        version.setText("v0.4");
        version.setTextSize(10.5f);
        version.setTextColor(palette.muted);
        content.addView(Ui.pageHeader(this, palette, "设置", "阅读、标点与网络偏好",
                version, this::finish));

        buildSyncCard(content);
        buildReaderCard(content);
        buildPunctCard(content);
        buildCacheCard(content);
        buildStickyBar(root);

        Ui.systemBars(this, palette);
        refreshCacheUsage();
    }

    private void buildSyncCard(LinearLayout parent) {
        LinearLayout card = Ui.card(this, palette);
        card.addView(Ui.section(this, palette, "同步（WebDAV）"));

        syncEnabled = Ui.check(this, palette, "启用 WebDAV 同步", settings.syncEnabled());
        card.addView(syncEnabled);
        autoSync = Ui.check(this, palette, "自动同步（打开应用、退出阅读时）", settings.autoSync());
        card.addView(autoSync);

        syncUrl = input(card, "WebDAV 地址", settings.syncUrl(),
                InputType.TYPE_TEXT_VARIATION_URI);
        syncUrl.setHint("https://dav.jianguoyun.com/dav/");
        syncAccount = input(card, "账号", settings.syncAccount(), InputType.TYPE_CLASS_TEXT);
        syncPassword = input(card, "密码 / 应用密码", settings.syncPassword(),
                InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_PASSWORD);
        syncDir = input(card, "同步目录", settings.syncDir(), InputType.TYPE_CLASS_TEXT);
        syncDir.setHint("yuewei");

        syncHint = new TextView(this);
        syncHint.setTextSize(Ui.TEXT_HINT);
        syncHint.setTextColor(palette.muted);
        syncHint.setPadding(0, dp(10), 0, 0);
        syncHint.setText(SyncManager.lastSyncText(this));
        card.addView(syncHint);

        LinearLayout actions = new LinearLayout(this);
        actions.setOrientation(LinearLayout.HORIZONTAL);
        actions.setPadding(0, dp(12), 0, 0);

        TextView saveSync = Ui.pill(this, "保存并同步", true, palette);
        saveSync.setOnClickListener(v -> {
            saveAll();
            runSync();
        });
        actions.addView(saveSync, new LinearLayout.LayoutParams(
                0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f));

        TextView syncNow = Ui.pill(this, "立即同步", false, palette);
        LinearLayout.LayoutParams syncParams = new LinearLayout.LayoutParams(
                0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f);
        syncParams.setMargins(dp(8), 0, 0, 0);
        syncNow.setOnClickListener(v -> {
            saveAll();
            runSync();
        });
        actions.addView(syncNow, syncParams);
        card.addView(actions);
        addCard(parent, card);
    }

    private void runSync() {
        if (SyncManager.running()) {
            Toast.makeText(this, "正在同步，请稍候", Toast.LENGTH_SHORT).show();
            return;
        }
        syncHint.setText("正在同步…");
        Toast.makeText(this, "正在同步…", Toast.LENGTH_SHORT).show();
        SyncManager.sync(this, (message, ok) -> {
            syncHint.setText(SyncManager.lastSyncText(SettingsActivity.this));
            Toast.makeText(this, message, Toast.LENGTH_LONG).show();
        });
    }

    private void buildReaderCard(LinearLayout parent) {
        LinearLayout card = Ui.card(this, palette);

        LinearLayout head = new LinearLayout(this);
        head.setOrientation(LinearLayout.HORIZONTAL);
        head.setGravity(Gravity.CENTER_VERTICAL);
        head.addView(Ui.section(this, palette, "阅读排版"), new LinearLayout.LayoutParams(
                0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f));

        TextView live = new TextView(this);
        live.setText("即时生效");
        live.setTextSize(10.5f);
        live.setTextColor(palette.muted);
        head.addView(live);
        card.addView(head);

        preview = new TextView(this);
        preview.setText("天兽岛外，海雾未散。\n“道友客气了。”陈平脚步不停，只在海面上留下一线浅浅的白痕。");
        preview.setPadding(dp(14), dp(12), dp(14), dp(12));
        preview.setBackground(Ui.roundStroke(this, palette.bg, palette.line, 10));
        updatePreview();
        card.addView(preview);

        fontSizeValue = new TextView(this);
        fontSizeValue.setText(String.valueOf(settings.fontSize()));
        fontSizeValue.setTextSize(10.5f);
        fontSizeValue.setTypeface(android.graphics.Typeface.MONOSPACE);
        fontSizeValue.setTextColor(palette.muted);
        fontSizeValue.setGravity(Gravity.END);
        fontSizeValue.setMinWidth(dp(26));
        card.addView(sliderRow("A−", "A+", settings.fontSize() - 12, 20, fontSizeValue));

        fontSizeBar = findLastSeek(card);
        fontSizeBar.setProgress(settings.fontSize() - 12);
        fontSizeBar.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            @Override
            public void onProgressChanged(SeekBar seekBar, int progress, boolean fromUser) {
                if (!fromUser) {
                    return;
                }
                int size = 12 + progress;
                fontSizeValue.setText(String.valueOf(size));
                saveReaderNow();
                updatePreview();
            }

            @Override
            public void onStartTrackingTouch(SeekBar seekBar) {
            }

            @Override
            public void onStopTrackingTouch(SeekBar seekBar) {
            }
        });

        lineSpacingValue = new TextView(this);
        lineSpacingValue.setText(String.format(Locale.CHINA, "%.1f", settings.lineSpacing()));
        lineSpacingValue.setTextSize(10.5f);
        lineSpacingValue.setTypeface(android.graphics.Typeface.MONOSPACE);
        lineSpacingValue.setTextColor(palette.muted);
        lineSpacingValue.setGravity(Gravity.END);
        lineSpacingValue.setMinWidth(dp(26));
        card.addView(sliderRow("行距", "", Math.round((settings.lineSpacing() - 1.2f) * 10),
                18, lineSpacingValue));

        lineSpacingBar = findLastSeek(card);
        lineSpacingBar.setProgress(Math.round((settings.lineSpacing() - 1.2f) * 10));
        lineSpacingBar.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            @Override
            public void onProgressChanged(SeekBar seekBar, int progress, boolean fromUser) {
                if (!fromUser) {
                    return;
                }
                float spacing = 1.2f + progress / 10f;
                lineSpacingValue.setText(String.format(Locale.CHINA, "%.1f", spacing));
                saveReaderNow();
                updatePreview();
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
                selectedTheme, key -> {
                    if (key.equals(selectedTheme)) {
                        return;
                    }
                    selectedTheme = key;
                    saveAll();
                    recreate();
                });
        LinearLayout.LayoutParams segParams = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        segParams.setMargins(0, dp(10), 0, 0);
        card.addView(themeSeg, segParams);

        LinearLayout fontRow = new LinearLayout(this);
        fontRow.setOrientation(LinearLayout.HORIZONTAL);
        fontRow.setGravity(Gravity.CENTER_VERTICAL);
        fontRow.setPadding(0, dp(10), 0, 0);

        fontLabel = Ui.label(this, palette,
                "字体：" + MobileFonts.displayName(this, fontKey));
        fontLabel.setTextSize(13);
        fontLabel.setPadding(dp(12), dp(10), dp(12), dp(10));
        fontLabel.setBackground(Ui.roundStroke(this, palette.bg, palette.line, Ui.RADIUS_INPUT));
        fontLabel.setOnClickListener(v -> pickFont());
        fontRow.addView(fontLabel, new LinearLayout.LayoutParams(
                0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f));

        TextView importFont = new TextView(this);
        importFont.setText("导入字体文件");
        importFont.setTextSize(12);
        importFont.setTextColor(palette.accent);
        importFont.setPadding(dp(12), dp(8), 0, dp(8));
        importFont.setClickable(true);
        importFont.setOnClickListener(v -> openFontFile());
        fontRow.addView(importFont);
        card.addView(fontRow);

        addCard(parent, card);
    }

    /** 生成一行 A− / 滑杆 / A+ / 数值，并把 SeekBar 留在 card 的最后。 */
    private LinearLayout sliderRow(String left, String right, int progress, int max,
                                   TextView value) {
        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.setPadding(0, dp(6), 0, 0);

        TextView leftView = new TextView(this);
        leftView.setText(left);
        leftView.setTextSize(11);
        leftView.setTextColor(palette.muted);
        leftView.setMinWidth(dp(28));
        row.addView(leftView);

        SeekBar bar = Ui.accentSeek(this, palette);
        bar.setMax(max);
        bar.setProgress(progress);
        row.addView(bar, new LinearLayout.LayoutParams(
                0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f));

        if (right != null && !right.isEmpty()) {
            TextView rightView = new TextView(this);
            rightView.setText(right);
            rightView.setTextSize(11);
            rightView.setTextColor(palette.muted);
            rightView.setMinWidth(dp(26));
            row.addView(rightView);
        }
        row.addView(value);
        // 由调用方通过 findLastSeek 取回滑杆
        return row;
    }

    private SeekBar findLastSeek(LinearLayout card) {
        LinearLayout row = (LinearLayout) card.getChildAt(card.getChildCount() - 1);
        for (int i = row.getChildCount() - 1; i >= 0; i--) {
            if (row.getChildAt(i) instanceof SeekBar) {
                return (SeekBar) row.getChildAt(i);
            }
        }
        return new SeekBar(this);
    }

    private void buildPunctCard(LinearLayout parent) {
        LinearLayout card = Ui.card(this, palette);
        card.addView(Ui.section(this, palette, "标点着色"));

        LinearLayout masterRow = new LinearLayout(this);
        masterRow.setOrientation(LinearLayout.HORIZONTAL);
        masterRow.setGravity(Gravity.CENTER_VERTICAL);
        masterRow.setPadding(0, dp(2), 0, dp(4));

        punctEnabled = Ui.toggle(this, palette, settings.punctEnabled());
        masterRow.addView(punctEnabled);

        TextView masterLabel = new TextView(this);
        masterLabel.setText("启用标点着色");
        masterLabel.setTextSize(Ui.TEXT_BODY);
        masterLabel.setTextColor(palette.ink);
        masterLabel.setPadding(dp(10), 0, 0, 0);
        masterRow.addView(masterLabel);
        card.addView(masterRow);

        String[] names = {"对话（引号内）", "句号", "问号", "感叹号", "省略号 / 破折号", "括号（含内容）"};
        int[] colors = {palette.punctDialogue, palette.punctStop, palette.punctAsk,
                palette.punctExcl, palette.punctEllipsis, palette.punctParen};
        boolean[] values = {settings.punctDialogue(), settings.punctStop(), settings.punctAsk(),
                settings.punctExcl(), settings.punctEllipsis(), settings.punctParen()};

        LinearLayout grid = new LinearLayout(this);
        grid.setOrientation(LinearLayout.VERTICAL);
        for (int i = 0; i < names.length; i += 2) {
            LinearLayout row = new LinearLayout(this);
            row.setOrientation(LinearLayout.HORIZONTAL);
            row.setPadding(0, dp(2), 0, dp(2));
            row.addView(punctCheck(names[i], colors[i], values[i]), new LinearLayout.LayoutParams(
                    0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f));
            if (i + 1 < names.length) {
                LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(
                        0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f);
                params.setMargins(dp(12), 0, 0, 0);
                row.addView(punctCheck(names[i + 1], colors[i + 1], values[i + 1]), params);
            }
            grid.addView(row);
        }
        card.addView(grid);

        TextView hint = new TextView(this);
        hint.setText("关闭总开关或取消某条规则后，对应文字恢复墨色；颜色随主题自动调整。");
        hint.setTextSize(Ui.TEXT_HINT);
        hint.setTextColor(palette.muted);
        hint.setPadding(0, dp(8), 0, 0);
        card.addView(hint);

        punctReady = true;
        punctEnabled.setOnCheckedChangeListener((buttonView, checked) -> {
            for (CheckBox box : punctBoxes) {
                if (box != null) {
                    box.setEnabled(checked);
                }
            }
            savePunctuationNow();
        });
        addCard(parent, card);
    }

    private CheckBox punctCheck(String label, int color, boolean checked) {
        CheckBox box = Ui.check(this, palette, label, checked);
        SpannableString text = new SpannableString("● " + label);
        text.setSpan(new ForegroundColorSpan(color), 0, 1, 0);
        box.setText(text);
        box.setTextSize(12);
        box.setButtonTintList(android.content.res.ColorStateList.valueOf(palette.accent));
        box.setEnabled(settings.punctEnabled());
        box.setOnCheckedChangeListener((buttonView, isChecked) -> {
            if (punctReady) {
                savePunctuationNow();
            }
        });
        for (int i = 0; i < punctBoxes.length; i++) {
            if (punctBoxes[i] == null) {
                punctBoxes[i] = box;
                break;
            }
        }
        return box;
    }

    private void buildCacheCard(LinearLayout parent) {
        LinearLayout card = Ui.card(this, palette);
        card.addView(Ui.section(this, palette, "缓存与下载"));

        cacheUsage = new TextView(this);
        cacheUsage.setTextSize(14);
        cacheUsage.setTextColor(palette.ink);
        card.addView(cacheUsage);

        cacheLine = Ui.progressLine(this, palette);
        LinearLayout.LayoutParams lineParams = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, dp(2));
        lineParams.setMargins(0, dp(8), 0, dp(4));
        card.addView(cacheLine, lineParams);

        cacheLimit = input(card, "在线书缓存上限（MB，50–2048）",
                String.valueOf(settings.cacheLimitMb()), InputType.TYPE_CLASS_NUMBER);

        TextView clean = Ui.pill(this, "清理在线书缓存", false, palette);
        LinearLayout.LayoutParams cleanParams = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        cleanParams.setMargins(0, dp(12), 0, 0);
        clean.setOnClickListener(v -> promptCleanCache());
        card.addView(clean, cleanParams);

        TextView cacheHint = new TextView(this);
        cacheHint.setText("仅清理在线书正文，本地导入的 TXT 不受影响；整本下载请在书架长按书籍。");
        cacheHint.setTextSize(Ui.TEXT_HINT);
        cacheHint.setTextColor(palette.muted);
        cacheHint.setPadding(0, dp(8), 0, 0);
        card.addView(cacheHint);
        addCard(parent, card);
    }

    private void buildStickyBar(FrameLayout root) {
        LinearLayout bar = new LinearLayout(this);
        bar.setOrientation(LinearLayout.HORIZONTAL);
        bar.setGravity(Gravity.CENTER_VERTICAL);
        bar.setPadding(dp(Ui.SPACE_PAGE), dp(10), dp(Ui.SPACE_PAGE), dp(14));
        bar.setBackground(Ui.panelBackground(this, palette, true));

        TextView status = new TextView(this);
        status.setText("已自动保存 · 仅本机生效");
        status.setTextSize(10.5f);
        status.setTextColor(palette.muted);
        bar.addView(status, new LinearLayout.LayoutParams(
                0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f));

        TextView done = Ui.pill(this, "完成", true, palette);
        done.setOnClickListener(v -> {
            saveAll();
            Toast.makeText(this, "已保存", Toast.LENGTH_SHORT).show();
            finish();
        });
        bar.addView(done);

        FrameLayout.LayoutParams params = new FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.WRAP_CONTENT);
        params.gravity = Gravity.BOTTOM;
        root.addView(bar, params);
    }

    private void addCard(LinearLayout parent, LinearLayout card) {
        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        params.setMargins(dp(Ui.SPACE_PAGE), dp(4), dp(Ui.SPACE_PAGE), dp(4));
        parent.addView(card, params);
    }

    @Override
    protected void onResume() {
        super.onResume();
        refreshCacheUsage();
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (requestCode != REQUEST_IMPORT_FONT || resultCode != RESULT_OK
                || data == null || data.getData() == null) {
            return;
        }
        final Uri uri = data.getData();
        Toast.makeText(this, "正在导入字体…", Toast.LENGTH_SHORT).show();
        new Thread(() -> {
            try {
                String key = MobileFonts.importFont(this, uri);
                runOnUiThread(() -> {
                    fontKey = key;
                    fontLabel.setText("字体：" + MobileFonts.displayName(this, key));
                    updatePreview();
                    saveReaderNow();
                    Toast.makeText(this, "字体已导入", Toast.LENGTH_SHORT).show();
                });
            } catch (Exception e) {
                runOnUiThread(() -> Toast.makeText(this,
                        "导入失败：" + e.getMessage(), Toast.LENGTH_LONG).show());
            }
        }, "yuewei-font").start();
    }

    private void pickFont() {
        final List<MobileFonts.FontChoice> choices = MobileFonts.choices(this);
        String[] names = new String[choices.size()];
        for (int i = 0; i < choices.size(); i++) {
            names[i] = choices.get(i).name;
        }
        Ui.dialog(this, selectedTheme)
                .setTitle("正文字体")
                .setItems(names, (dialog, which) -> {
                    MobileFonts.FontChoice choice = choices.get(which);
                    fontKey = choice.key;
                    fontLabel.setText("字体：" + choice.name);
                    updatePreview();
                    saveReaderNow();
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

    private void updatePreview() {
        if (preview == null) {
            return;
        }
        preview.setTextSize(settings.fontSize());
        preview.setLineSpacing(0, settings.lineSpacing());
        preview.setTypeface(MobileFonts.resolve(this, fontKey));
        preview.setTextColor(palette.ink);
    }

    private void refreshCacheUsage() {
        if (cacheUsage == null || cacheLibrary == null) {
            return;
        }
        cacheLibrary.reload();
        long bytes = CacheManager.onlineCacheBytes(cacheLibrary);
        long limit = Math.max(1, settings.cacheLimitMb()) * 1024L * 1024L;
        cacheUsage.setText("在线书缓存：" + formatSize(bytes));
        if (cacheLine != null) {
            cacheLine.setFraction(Math.min(1f, bytes / (float) limit));
        }
    }

    private void promptCleanCache() {
        Ui.dialog(this, selectedTheme)
                .setTitle("清理在线书缓存")
                .setMessage("删除所有在线书的正文缓存？本地导入的 TXT 不受影响，已下载章节需要联网重新加载。")
                .setPositiveButton("清理", (dialog, which) -> {
                    int deleted = CacheManager.clearOnlineCache(cacheLibrary);
                    refreshCacheUsage();
                    Toast.makeText(this, deleted > 0
                                    ? "已清理 " + deleted + " 个章节文件" : "没有可清理的缓存",
                            Toast.LENGTH_SHORT).show();
                })
                .setNegativeButton("取消", null)
                .show();
    }

    private String formatSize(long bytes) {
        if (bytes >= 1024L * 1024L) {
            return String.format(Locale.CHINA, "%.1f MB", bytes / (1024.0 * 1024.0));
        }
        if (bytes >= 1024) {
            return String.format(Locale.CHINA, "%.0f KB", bytes / 1024.0);
        }
        return bytes + " B";
    }

    private void saveReaderNow() {
        if (fontSizeValue == null || lineSpacingValue == null) {
            return;
        }
        int size = 12 + fontSizeBar.getProgress();
        float spacing = 1.2f + lineSpacingBar.getProgress() / 10f;
        settings.saveReader(size, spacing, selectedTheme, fontKey);
    }

    private void savePunctuationNow() {
        if (punctEnabled == null || punctBoxes[0] == null) {
            return;
        }
        settings.savePunctuation(punctEnabled.isChecked(),
                punctBoxes[0].isChecked(), punctBoxes[1].isChecked(), punctBoxes[2].isChecked(),
                punctBoxes[3].isChecked(), punctBoxes[4].isChecked(), punctBoxes[5].isChecked());
    }

    /** 保存所有设置；同步卡与缓存需要显式保存。 */
    private void saveAll() {
        settings.saveSync(syncEnabled.isChecked(), syncUrl.getText().toString(),
                syncAccount.getText().toString(), syncPassword.getText().toString(),
                syncDir.getText().toString());
        settings.saveAutoSync(autoSync.isChecked());
        saveReaderNow();
        savePunctuationNow();
        int limit;
        try {
            limit = Integer.parseInt(cacheLimit.getText().toString().trim());
        } catch (NumberFormatException e) {
            limit = settings.cacheLimitMb();
        }
        settings.saveCacheLimit(limit);
        refreshCacheUsage();
    }

    private EditText input(LinearLayout parent, String label, String value, int inputType) {
        parent.addView(Ui.caption(this, palette, label));

        EditText edit = Ui.input(this, palette, "", inputType);
        edit.setText(value);
        boolean password = (inputType & InputType.TYPE_MASK_VARIATION)
                == InputType.TYPE_TEXT_VARIATION_PASSWORD;
        if (password) {
            edit.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_PASSWORD);
            edit.setTransformationMethod(
                    android.text.method.PasswordTransformationMethod.getInstance());
        }
        edit.setSelection(edit.getText().length());
        parent.addView(edit);
        return edit;
    }

    private int dp(int value) {
        return Ui.dp(this, value);
    }
}
