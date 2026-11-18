package com.yuewei.reader.mobile;

import android.app.Activity;
import android.content.res.ColorStateList;
import android.os.Bundle;
import android.text.InputType;
import android.view.Gravity;
import android.view.View;
import android.widget.CheckBox;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.RadioButton;
import android.widget.RadioGroup;
import android.widget.ScrollView;
import android.widget.SeekBar;
import android.widget.TextView;
import android.widget.Toast;

/** 设置：WebDAV 同步参数与阅读排版。 */
public class SettingsActivity extends Activity {

    private AppSettings settings;
    private Ui.Palette palette;
    private CheckBox syncEnabled;
    private CheckBox autoSync;
    private EditText syncUrl;
    private EditText syncAccount;
    private EditText syncPassword;
    private EditText syncDir;
    private SeekBar fontSize;
    private TextView fontSizeLabel;
    private EditText lineSpacing;
    private RadioGroup themeGroup;
    private TextView syncHint;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        settings = new AppSettings(this);
        palette = Ui.palette(settings.theme());

        ScrollView scroll = new ScrollView(this);
        scroll.setBackgroundColor(palette.bg);
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setPadding(dp(18), dp(14), dp(18), dp(28));
        scroll.addView(root);
        setContentView(scroll);
        Ui.systemBars(this, palette);

        LinearLayout header = new LinearLayout(this);
        header.setOrientation(LinearLayout.HORIZONTAL);
        header.setGravity(Gravity.CENTER_VERTICAL);
        header.setPadding(0, 0, 0, dp(12));
        root.addView(header);

        TextView back = Ui.pill(this, "‹ 书架", false, palette);
        back.setOnClickListener(v -> finish());
        header.addView(back);

        TextView title = new TextView(this);
        title.setText("设置");
        title.setTextSize(22);
        title.setTypeface(Ui.SERIF_BOLD);
        title.setTextColor(palette.ink);
        title.setPadding(dp(14), 0, 0, 0);
        header.addView(title, new LinearLayout.LayoutParams(0,
                LinearLayout.LayoutParams.WRAP_CONTENT, 1f));

        LinearLayout syncCard = card();
        syncCard.addView(section("同步（WebDAV）"));

        syncEnabled = check("启用 WebDAV 同步", settings.syncEnabled());
        syncCard.addView(syncEnabled);
        autoSync = check("自动同步（打开应用、退出阅读时）", settings.autoSync());
        syncCard.addView(autoSync);

        syncUrl = input(syncCard, "WebDAV 地址", settings.syncUrl(),
                InputType.TYPE_TEXT_VARIATION_URI);
        syncUrl.setHint("https://dav.jianguoyun.com/dav/");
        syncAccount = input(syncCard, "账号", settings.syncAccount(), InputType.TYPE_CLASS_TEXT);
        syncPassword = input(syncCard, "密码 / 应用密码", settings.syncPassword(),
                InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_PASSWORD);
        syncDir = input(syncCard, "同步目录", settings.syncDir(), InputType.TYPE_CLASS_TEXT);
        syncDir.setHint("yuewei");

        syncHint = new TextView(this);
        syncHint.setTextSize(12);
        syncHint.setTextColor(palette.muted);
        syncHint.setPadding(0, dp(10), 0, 0);
        syncHint.setText(SyncManager.lastSyncText(this));
        syncCard.addView(syncHint);

        LinearLayout syncActions = new LinearLayout(this);
        syncActions.setOrientation(LinearLayout.HORIZONTAL);
        syncActions.setPadding(0, dp(12), 0, 0);

        TextView saveSync = Ui.pill(this, "保存", true, palette);
        saveSync.setOnClickListener(v -> saveAndFinish());
        syncActions.addView(saveSync);

        TextView syncNow = Ui.pill(this, "立即同步", false, palette);
        LinearLayout.LayoutParams syncParams = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        syncParams.setMargins(dp(10), 0, 0, 0);
        syncNow.setOnClickListener(v -> {
            saveAll();
            syncNow.setEnabled(false);
            syncHint.setText("正在同步…");
            Toast.makeText(this, "正在同步…", Toast.LENGTH_SHORT).show();
            SyncManager.sync(this, (message, ok) -> {
                syncNow.setEnabled(true);
                syncHint.setText(SyncManager.lastSyncText(this));
                Toast.makeText(this, message, Toast.LENGTH_LONG).show();
            });
        });
        syncActions.addView(syncNow, syncParams);
        syncCard.addView(syncActions);
        root.addView(syncCard);

        LinearLayout readerCard = card();
        readerCard.addView(section("阅读排版"));

        fontSizeLabel = new TextView(this);
        fontSizeLabel.setTextColor(palette.ink);
        fontSizeLabel.setTextSize(14);
        fontSizeLabel.setText("字号：" + settings.fontSize());
        readerCard.addView(fontSizeLabel);

        fontSize = new SeekBar(this);
        fontSize.setMax(20);
        fontSize.setProgress(settings.fontSize() - 12);
        fontSize.setProgressTintList(ColorStateList.valueOf(palette.accent));
        fontSize.setThumbTintList(ColorStateList.valueOf(palette.accent));
        fontSize.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            @Override
            public void onProgressChanged(SeekBar seekBar, int progress, boolean fromUser) {
                fontSizeLabel.setText("字号：" + (progress + 12));
            }

            @Override
            public void onStartTrackingTouch(SeekBar seekBar) {
            }

            @Override
            public void onStopTrackingTouch(SeekBar seekBar) {
            }
        });
        readerCard.addView(fontSize);

        lineSpacing = input(readerCard, "行距（1.2–3.0）", String.valueOf(settings.lineSpacing()),
                InputType.TYPE_CLASS_NUMBER | InputType.TYPE_NUMBER_FLAG_DECIMAL);

        themeGroup = new RadioGroup(this);
        themeGroup.setOrientation(RadioGroup.HORIZONTAL);
        RadioButton paper = themeOption("纸白", "paper");
        RadioButton night = themeOption("夜间", "night");
        RadioButton green = themeOption("护眼", "green");
        themeGroup.addView(paper);
        themeGroup.addView(night);
        themeGroup.addView(green);
        String savedTheme = settings.theme();
        if ("night".equals(savedTheme)) {
            night.setChecked(true);
        } else if ("green".equals(savedTheme)) {
            green.setChecked(true);
        } else {
            paper.setChecked(true);
        }
        readerCard.addView(themeGroup);
        root.addView(readerCard);

        TextView save = Ui.pill(this, "保存设置", true, palette);
        LinearLayout.LayoutParams saveParams = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        saveParams.setMargins(0, dp(16), 0, 0);
        save.setOnClickListener(v -> saveAndFinish());
        root.addView(save, saveParams);
    }

    /** 保存后只在本机生效，排版设置不参与 WebDAV 同步。 */
    private void saveAndFinish() {
        saveAll();
        Toast.makeText(this, "已保存", Toast.LENGTH_SHORT).show();
        finish();
    }

    private LinearLayout card() {
        LinearLayout card = new LinearLayout(this);
        card.setOrientation(LinearLayout.VERTICAL);
        card.setPadding(dp(16), dp(14), dp(16), dp(14));
        card.setBackground(Ui.round(this, palette.card, 14));
        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        params.setMargins(0, dp(6), 0, dp(6));
        card.setLayoutParams(params);
        return card;
    }

    private TextView section(String text) {
        TextView view = new TextView(this);
        view.setText(text);
        view.setTextSize(15);
        view.setTypeface(Ui.SERIF_BOLD);
        view.setPadding(0, dp(2), 0, dp(8));
        view.setTextColor(palette.accent);
        return view;
    }

    private CheckBox check(String text, boolean checked) {
        CheckBox box = new CheckBox(this);
        box.setText(text);
        box.setTextSize(14);
        box.setTextColor(palette.ink);
        box.setChecked(checked);
        box.setButtonTintList(ColorStateList.valueOf(palette.accent));
        box.setPadding(0, dp(4), 0, dp(4));
        return box;
    }

    private EditText input(LinearLayout parent, String label, String value, int inputType) {
        TextView caption = new TextView(this);
        caption.setText(label);
        caption.setTextSize(12);
        caption.setTextColor(palette.muted);
        caption.setPadding(0, dp(10), 0, dp(4));
        parent.addView(caption);

        EditText edit = new EditText(this);
        edit.setTextSize(14);
        edit.setTextColor(palette.ink);
        edit.setHintTextColor(palette.muted);
        edit.setSingleLine(true);
        edit.setPadding(dp(12), dp(9), dp(12), dp(9));
        edit.setBackground(Ui.roundStroke(this, palette.bg, palette.line, 10));
        edit.setText(value);
        boolean password = (inputType & InputType.TYPE_MASK_VARIATION)
                == InputType.TYPE_TEXT_VARIATION_PASSWORD;
        if (password) {
            edit.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_PASSWORD);
            edit.setTransformationMethod(
                    android.text.method.PasswordTransformationMethod.getInstance());
        } else {
            edit.setInputType(inputType);
        }
        edit.setSelection(edit.getText().length());
        parent.addView(edit);
        return edit;
    }

    private RadioButton themeOption(String text, String key) {
        RadioButton button = new RadioButton(this);
        button.setId(View.generateViewId());
        button.setText(text);
        button.setTag(key);
        button.setTextSize(14);
        button.setTextColor(palette.ink);
        button.setButtonTintList(ColorStateList.valueOf(palette.accent));
        button.setGravity(Gravity.CENTER_VERTICAL);
        return button;
    }

    private void saveAll() {
        settings.saveSync(syncEnabled.isChecked(), syncUrl.getText().toString(),
                syncAccount.getText().toString(), syncPassword.getText().toString(),
                syncDir.getText().toString());
        settings.saveAutoSync(autoSync.isChecked());
        int size = 12 + fontSize.getProgress();
        float spacing;
        try {
            spacing = Float.parseFloat(lineSpacing.getText().toString().trim());
        } catch (NumberFormatException e) {
            spacing = settings.lineSpacing();
        }
        int checked = themeGroup.getCheckedRadioButtonId();
        View selected = checked == -1 ? null : findViewById(checked);
        String theme = selected == null ? settings.theme() : String.valueOf(selected.getTag());
        settings.saveReader(size, spacing, theme);
    }

    private int dp(int value) {
        return Ui.dp(this, value);
    }
}
