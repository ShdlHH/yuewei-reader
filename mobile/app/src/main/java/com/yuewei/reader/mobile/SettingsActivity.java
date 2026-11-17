package com.yuewei.reader.mobile;

import android.app.Activity;
import android.content.res.ColorStateList;
import android.graphics.Color;
import android.os.Bundle;
import android.text.InputType;
import android.view.Gravity;
import android.view.View;
import android.widget.Button;
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
    private CheckBox syncEnabled;
    private EditText syncUrl;
    private EditText syncAccount;
    private EditText syncPassword;
    private EditText syncDir;
    private SeekBar fontSize;
    private TextView fontSizeLabel;
    private EditText lineSpacing;
    private RadioGroup themeGroup;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        settings = new AppSettings(this);

        ScrollView scroll = new ScrollView(this);
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setPadding(dp(20), dp(16), dp(20), dp(24));
        root.setBackgroundColor(Color.parseColor("#F6F1E7"));
        scroll.addView(root);
        setContentView(scroll);

        TextView title = new TextView(this);
        title.setText("设置");
        title.setTextSize(22);
        title.setTextColor(Color.parseColor("#2F2A24"));
        title.setPadding(0, 0, 0, dp(12));
        root.addView(title);

        root.addView(section("同步（WebDAV）"));
        syncEnabled = new CheckBox(this);
        syncEnabled.setText("启用 WebDAV 同步");
        syncEnabled.setTextColor(Color.parseColor("#2F2A24"));
        syncEnabled.setChecked(settings.syncEnabled());
        root.addView(syncEnabled);

        syncUrl = input(root, "WebDAV 地址", settings.syncUrl(), InputType.TYPE_TEXT_VARIATION_URI);
        syncUrl.setHint("https://dav.jianguoyun.com/dav/");
        syncAccount = input(root, "账号", settings.syncAccount(), InputType.TYPE_CLASS_TEXT);
        syncPassword = input(root, "密码 / 应用密码", settings.syncPassword(),
                InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_PASSWORD);
        syncDir = input(root, "同步目录", settings.syncDir(), InputType.TYPE_CLASS_TEXT);
        syncDir.setHint("yuewei");

        root.addView(section("阅读排版"));
        fontSizeLabel = new TextView(this);
        fontSizeLabel.setTextColor(Color.parseColor("#2F2A24"));
        root.addView(fontSizeLabel);
        fontSize = new SeekBar(this);
        fontSize.setMax(20);
        fontSize.setProgress(settings.fontSize() - 12);
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
        fontSizeLabel.setText("字号：" + (fontSize.getProgress() + 12));
        root.addView(fontSize);

        lineSpacing = input(root, "行距（1.2–3.0）", String.valueOf(settings.lineSpacing()),
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
        root.addView(themeGroup);

        LinearLayout actions = new LinearLayout(this);
        actions.setOrientation(LinearLayout.HORIZONTAL);
        actions.setPadding(0, dp(16), 0, 0);

        Button save = new Button(this);
        save.setText("保存");
        save.setAllCaps(false);
        save.setOnClickListener(v -> {
            saveAll();
            Toast.makeText(this, "已保存", Toast.LENGTH_SHORT).show();
            finish();
        });
        actions.addView(save);

        Button syncNow = new Button(this);
        syncNow.setText("立即同步");
        syncNow.setAllCaps(false);
        syncNow.setOnClickListener(v -> {
            saveAll();
            syncNow.setEnabled(false);
            Toast.makeText(this, "正在同步…", Toast.LENGTH_SHORT).show();
            SyncManager.sync(this, (message, ok) -> {
                syncNow.setEnabled(true);
                Toast.makeText(this, message, Toast.LENGTH_LONG).show();
            });
        });
        actions.addView(syncNow);
        root.addView(actions);
    }

    private TextView section(String text) {
        TextView view = new TextView(this);
        view.setText(text);
        view.setTextSize(16);
        view.setPadding(0, dp(18), 0, dp(6));
        view.setTextColor(Color.parseColor("#A63D32"));
        return view;
    }

    private EditText input(LinearLayout parent, String label, String value, int inputType) {
        TextView caption = new TextView(this);
        caption.setText(label);
        caption.setTextColor(Color.parseColor("#2F2A24"));
        caption.setPadding(0, dp(6), 0, 0);
        parent.addView(caption);
        EditText edit = new EditText(this);
        edit.setInputType(inputType);
        edit.setText(value);
        edit.setTextColor(Color.parseColor("#2F2A24"));
        edit.setBackgroundTintList(ColorStateList.valueOf(Color.parseColor("#A63D32")));
        parent.addView(edit);
        return edit;
    }

    private RadioButton themeOption(String text, String key) {
        RadioButton button = new RadioButton(this);
        button.setId(View.generateViewId());
        button.setText(text);
        button.setTag(key);
        button.setTextColor(Color.parseColor("#2F2A24"));
        button.setGravity(Gravity.CENTER_VERTICAL);
        return button;
    }

    private void saveAll() {
        settings.saveSync(syncEnabled.isChecked(), syncUrl.getText().toString(),
                syncAccount.getText().toString(), syncPassword.getText().toString(),
                syncDir.getText().toString());
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
        return Math.round(value * getResources().getDisplayMetrics().density);
    }
}
