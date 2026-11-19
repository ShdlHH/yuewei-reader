package com.yuewei.reader.mobile;

import android.app.Activity;
import android.app.AlertDialog;
import android.app.Dialog;
import android.content.Context;
import android.content.res.ColorStateList;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.RectF;
import android.graphics.Typeface;
import android.graphics.drawable.ColorDrawable;
import android.graphics.drawable.Drawable;
import android.graphics.drawable.GradientDrawable;
import android.graphics.drawable.LayerDrawable;
import android.graphics.drawable.RippleDrawable;
import android.os.Build;
import android.text.InputType;
import android.view.Gravity;
import android.view.View;
import android.view.Window;
import android.view.WindowInsets;
import android.view.WindowInsetsController;
import android.view.WindowManager;
import android.widget.CheckBox;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.RadioButton;
import android.widget.RadioGroup;
import android.widget.SeekBar;
import android.widget.Switch;
import android.widget.TextView;

import java.util.function.Consumer;

/** 统一的配色、圆角与按钮样式，书架/阅读/设置共用。 */
final class Ui {

    /** 一套主题颜色，与桌面端纸白/夜间/护眼对应。 */
    static final class Palette {
        final boolean dark;
        final int bg;
        final int panel;
        final int card;
        final int ink;
        final int muted;
        final int accent;
        final int line;
        final int punctDialogue;
        final int punctStop;
        final int punctAsk;
        final int punctExcl;
        final int punctEllipsis;
        final int punctParen;

        Palette(boolean dark, int bg, int panel, int card, int ink, int muted, int accent, int line,
                int punctDialogue, int punctStop, int punctAsk, int punctExcl,
                int punctEllipsis, int punctParen) {
            this.dark = dark;
            this.bg = bg;
            this.panel = panel;
            this.card = card;
            this.ink = ink;
            this.muted = muted;
            this.accent = accent;
            this.line = line;
            this.punctDialogue = punctDialogue;
            this.punctStop = punctStop;
            this.punctAsk = punctAsk;
            this.punctExcl = punctExcl;
            this.punctEllipsis = punctEllipsis;
            this.punctParen = punctParen;
        }
    }

    static final Typeface SERIF = Typeface.create("serif", Typeface.NORMAL);
    static final Typeface SERIF_BOLD = Typeface.create("serif", Typeface.BOLD);

    /** 全局间距规范：页面 20、列表 16、卡片 8，圆角 14。 */
    static final int SPACE_PAGE = 20;
    static final int SPACE_LIST = 16;
    static final int SPACE_CARD = 8;
    static final int HEADER_TOP = 14;
    static final int HEADER_BOTTOM = 8;
    static final int TOUCH_MIN = 44;

    /** 统一字号：页面标题 22、书架 17、列表行 16、分组 12.5、正文 14、提示 12、副标题 11。 */
    static final int TEXT_TITLE = 22;
    static final int TEXT_BOOK = 17;
    static final int TEXT_ROW = 16;
    static final int TEXT_SECTION = 13;
    static final int TEXT_BODY = 14;
    static final int TEXT_HINT = 12;
    static final int TEXT_META = 11;

    /** 统一圆角：卡片 14、列表行 12、输入框 10、胶囊 22、sheet 16。 */
    static final int RADIUS_CARD = 14;
    static final int RADIUS_ROW = 12;
    static final int RADIUS_INPUT = 10;
    static final int RADIUS_PILL = 22;
    static final int RADIUS_SHEET = 16;

    private Ui() {
    }

    static Palette palette(String theme) {
        if ("night".equals(theme)) {
            return new Palette(true, 0xFF141519, 0xFF1B1D23, 0xFF191B21,
                    0xFFD2CEC4, 0xFF8B877D, 0xFFD07A66, 0xFF2D3038,
                    0xFFC97A6C, 0xFF6FA3D0, 0xFF5FB3A6,
                    0xFFD8935F, 0xFFA8A49C, 0xFF93A6B2);
        }
        if ("green".equals(theme)) {
            return new Palette(false, 0xFFE8F0E3, 0xFFDCE8D5, 0xFFF2F8EE,
                    0xFF22301F, 0xFF67735F, 0xFF3F5C3D, 0xFFC9DAC2,
                    0xFF8E4A3F, 0xFF3A6B93, 0xFF2E6F63,
                    0xFFA65B33, 0xFF7C786F, 0xFF5F737F);
        }
        return new Palette(false, 0xFFF4EDDF, 0xFFEAE0CB, 0xFFFBF6EA,
                0xFF221D18, 0xFF8B7F6C, 0xFFA63B2C, 0xFFDED1B8,
                0xFFA63B2C, 0xFF2F6FA8, 0xFF17786B,
                0xFFC2632A, 0xFF8A8680, 0xFF6B7F8C);
    }

    static int dp(Context context, int value) {
        return Math.round(value * context.getResources().getDisplayMetrics().density);
    }

    static float sp(Context context, float value) {
        return value * context.getResources().getDisplayMetrics().scaledDensity;
    }

    static Drawable round(Context context, int color, int radiusDp) {
        GradientDrawable drawable = new GradientDrawable();
        drawable.setColor(color);
        drawable.setCornerRadius(dp(context, radiusDp));
        return drawable;
    }

    static Drawable roundStroke(Context context, int fill, int stroke, int radiusDp) {
        GradientDrawable drawable = new GradientDrawable();
        drawable.setColor(fill);
        drawable.setCornerRadius(dp(context, radiusDp));
        drawable.setStroke(dp(context, 1), stroke);
        return drawable;
    }

    static Drawable ripple(Context context, Drawable content, int rippleColor) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
            return new RippleDrawable(ColorStateList.valueOf(rippleColor), content, null);
        }
        return content;
    }

    /** 胶囊按钮：primary 用强调色，次级用卡片色描边。 */
    static TextView pill(Context context, String text, boolean primary, Palette palette) {
        TextView view = new TextView(context);
        view.setText(text);
        view.setTextSize(13);
        view.setGravity(Gravity.CENTER);
        view.setMinHeight(dp(context, TOUCH_MIN));
        view.setPadding(dp(context, 16), dp(context, 10), dp(context, 16), dp(context, 10));
        view.setClickable(true);
        view.setFocusable(true);
        if (primary) {
            view.setTextColor(0xFFFFFFFF);
            view.setBackground(ripple(context, round(context, palette.accent, RADIUS_PILL), 0x33FFFFFF));
        } else {
            view.setTextColor(palette.ink);
            view.setBackground(ripple(context,
                    roundStroke(context, palette.card, palette.line, RADIUS_PILL), 0x1F000000));
        }
        return view;
    }

    /** 紧凑胶囊：阅读页顶栏、搜索“加入”等小动作。 */
    static TextView pillCompact(Context context, String text, boolean primary, Palette palette) {
        TextView view = new TextView(context);
        view.setText(text);
        view.setTextSize(12);
        view.setGravity(Gravity.CENTER);
        view.setPadding(dp(context, 11), dp(context, 6), dp(context, 11), dp(context, 6));
        view.setClickable(true);
        view.setFocusable(true);
        if (primary) {
            view.setTextColor(0xFFFFFFFF);
            view.setBackground(ripple(context, round(context, palette.accent, RADIUS_PILL), 0x33FFFFFF));
        } else {
            view.setTextColor(palette.ink);
            view.setBackground(ripple(context,
                    roundStroke(context, palette.card, palette.line, RADIUS_PILL), 0x1F000000));
        }
        return view;
    }

    /** 44dp 圆形动作：返回、设置、更多。 */
    static TextView circleButton(Context context, Palette palette, String glyph) {
        TextView view = new TextView(context);
        view.setText(glyph);
        view.setTextSize(15);
        view.setTextColor(palette.ink);
        view.setGravity(Gravity.CENTER);
        view.setClickable(true);
        view.setFocusable(true);
        view.setMinWidth(dp(context, TOUCH_MIN));
        view.setMinHeight(dp(context, TOUCH_MIN));
        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(
                dp(context, TOUCH_MIN), dp(context, TOUCH_MIN));
        view.setLayoutParams(params);
        view.setBackground(ripple(context,
                roundStroke(context, palette.card, palette.line, TOUCH_MIN / 2), 0x1F000000));
        return view;
    }

    /** 统一卡片：竖向、16/14 内边距、14dp 圆角、上下 4dp 外边距。 */
    static LinearLayout card(Context context, Palette palette) {
        LinearLayout card = new LinearLayout(context);
        card.setOrientation(LinearLayout.VERTICAL);
        card.setPadding(dp(context, SPACE_LIST), dp(context, 14),
                dp(context, SPACE_LIST), dp(context, 14));
        card.setBackground(round(context, palette.card, RADIUS_CARD));
        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        params.setMargins(0, dp(context, SPACE_CARD / 2), 0, dp(context, SPACE_CARD / 2));
        card.setLayoutParams(params);
        return card;
    }

    /** 分组标题：12.5sp、朱砂、字距 .14em。 */
    static TextView section(Context context, Palette palette, String text) {
        TextView view = new TextView(context);
        view.setText(text);
        view.setTextSize(TEXT_SECTION);
        view.setTypeface(SERIF_BOLD);
        view.setLetterSpacing(0.14f);
        view.setPadding(0, dp(context, 2), 0, dp(context, 8));
        view.setTextColor(palette.accent);
        return view;
    }

    /** 页面标题：22sp 宋体、字距 .06em。 */
    static TextView pageTitle(Context context, Palette palette, String text) {
        TextView view = new TextView(context);
        view.setText(text);
        view.setTextSize(TEXT_TITLE);
        view.setTypeface(SERIF_BOLD);
        view.setLetterSpacing(0.06f);
        view.setTextColor(palette.ink);
        return view;
    }

    /** 页头副标题：11sp 灰褐。 */
    static TextView subTitle(Context context, Palette palette, String text) {
        TextView view = new TextView(context);
        view.setText(text);
        view.setTextSize(TEXT_META);
        view.setLetterSpacing(0.04f);
        view.setTextColor(palette.muted);
        view.setPadding(0, dp(context, 3), 0, 0);
        return view;
    }

    /** 控件标签。 */
    static TextView label(Context context, Palette palette, String text) {
        TextView view = new TextView(context);
        view.setText(text);
        view.setTextSize(TEXT_BODY);
        view.setTextColor(palette.ink);
        return view;
    }

    /** 输入框上方的小标题。 */
    static TextView caption(Context context, Palette palette, String text) {
        TextView view = new TextView(context);
        view.setText(text);
        view.setTextSize(TEXT_HINT);
        view.setTextColor(palette.muted);
        view.setPadding(0, dp(context, 10), 0, dp(context, 4));
        return view;
    }

    /** 统一输入框：14sp、12/10 内边距、10dp 描边圆角、最小高度 44dp。 */
    static EditText input(Context context, Palette palette, String hint, int inputType) {
        EditText edit = new EditText(context);
        edit.setTextSize(TEXT_BODY);
        edit.setTextColor(palette.ink);
        edit.setHintTextColor(palette.muted);
        edit.setSingleLine(true);
        edit.setHint(hint);
        edit.setMinHeight(dp(context, TOUCH_MIN));
        edit.setPadding(dp(context, 12), dp(context, 10), dp(context, 12), dp(context, 10));
        edit.setBackground(roundStroke(context, palette.bg, palette.line, RADIUS_INPUT));
        edit.setInputType(inputType == 0 ? InputType.TYPE_CLASS_TEXT : inputType);
        return edit;
    }

    /** 统一复选框。 */
    static CheckBox check(Context context, Palette palette, String text, boolean checked) {
        CheckBox box = new CheckBox(context);
        box.setText(text);
        box.setTextSize(TEXT_BODY);
        box.setTextColor(palette.ink);
        box.setChecked(checked);
        box.setButtonTintList(ColorStateList.valueOf(palette.accent));
        box.setPadding(0, dp(context, 4), 0, dp(context, 4));
        return box;
    }

    /** 主题分段器：三枚等宽选项，选中态墨底纸字。 */
    static LinearLayout segmented(Context context, Palette palette, String[] labels, String[] keys,
                                   String current, Consumer<String> onSelect) {
        LinearLayout box = new LinearLayout(context);
        box.setOrientation(LinearLayout.HORIZONTAL);
        box.setPadding(dp(context, 3), dp(context, 3), dp(context, 3), dp(context, 3));
        box.setBackground(round(context, palette.bg, RADIUS_PILL));
        final TextView[] options = new TextView[labels.length];
        for (int i = 0; i < labels.length; i++) {
            final String key = keys[i];
            TextView option = new TextView(context);
            option.setText(labels[i]);
            option.setTextSize(12);
            option.setGravity(Gravity.CENTER);
            option.setPadding(0, dp(context, 8), 0, dp(context, 8));
            option.setClickable(true);
            option.setTag(key);
            LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(
                    0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f);
            box.addView(option, params);
            options[i] = option;
        }
        final boolean[] ready = {false};
        Runnable refresh = () -> {
            for (TextView option : options) {
                boolean on = String.valueOf(option.getTag()).equals(current);
                option.setBackground(on ? round(context, palette.ink, RADIUS_PILL) : null);
                option.setTextColor(on ? paper(palette) : palette.muted);
            }
        };
        for (TextView option : options) {
            option.setOnClickListener(v -> {
                if (!ready[0]) {
                    return;
                }
                String key = String.valueOf(v.getTag());
                for (TextView item : options) {
                    boolean on = item == v;
                    item.setBackground(on ? round(context, palette.ink, RADIUS_PILL) : null);
                    item.setTextColor(on ? paper(palette) : palette.muted);
                }
                if (onSelect != null) {
                    onSelect.accept(key);
                }
            });
        }
        // 用最终 current 着色，避免闭包里捕获不到更新
        final String[] currentRef = {current};
        for (TextView option : options) {
            boolean on = String.valueOf(option.getTag()).equals(currentRef[0]);
            option.setBackground(on ? round(context, palette.ink, RADIUS_PILL) : null);
            option.setTextColor(on ? paper(palette) : palette.muted);
        }
        ready[0] = true;
        return box;
    }

    /** 纸色（用于选中态文字）。 */
    private static int paper(Palette palette) {
        return palette.dark ? 0xFF141519 : 0xFFFBF6EA;
    }

    /** 统一染色滑块。 */
    static SeekBar accentSeek(Context context, Palette palette) {
        SeekBar bar = new SeekBar(context);
        bar.setProgressTintList(ColorStateList.valueOf(palette.accent));
        bar.setThumbTintList(ColorStateList.valueOf(palette.accent));
        return bar;
    }

    /** 开关：朱砂轨道。 */
    static Switch toggle(Context context, Palette palette, boolean checked) {
        Switch view = new Switch(context);
        view.setChecked(checked);
        view.setThumbTintList(ColorStateList.valueOf(palette.accent));
        view.setTrackTintList(ColorStateList.valueOf(
                (palette.accent & 0x00FFFFFF) | 0x66000000));
        return view;
    }

    /** 统一列表行：16/12 内边距、12dp 圆角卡片，带点击涟漪。 */
    static LinearLayout row(Context context, Palette palette) {
        LinearLayout row = new LinearLayout(context);
        row.setOrientation(LinearLayout.VERTICAL);
        row.setPadding(dp(context, SPACE_LIST), dp(context, 12),
                dp(context, SPACE_LIST), dp(context, 12));
        row.setBackground(ripple(context, round(context, palette.card, RADIUS_ROW), 0x14000000));
        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        params.setMargins(0, dp(context, SPACE_CARD / 2), 0, dp(context, SPACE_CARD / 2));
        row.setLayoutParams(params);
        return row;
    }

    /** 统一空状态。 */
    static TextView empty(Context context, Palette palette, String text) {
        TextView view = new TextView(context);
        view.setText(text);
        view.setTextSize(TEXT_BODY);
        view.setTextColor(palette.muted);
        view.setGravity(Gravity.CENTER);
        view.setPadding(dp(context, 8), dp(context, 28), dp(context, 8), dp(context, 20));
        return view;
    }

    /** 1dp 发丝线。 */
    static View hairline(Context context, Palette palette) {
        View line = new View(context);
        line.setBackgroundColor(palette.line);
        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, dp(context, 1));
        line.setLayoutParams(params);
        return line;
    }

    /** 页面头：44dp 返回 + 22sp 标题 + 可选副标题 + 右侧动作。 */
    static LinearLayout pageHeader(Context context, Palette palette, String title, String subtitle,
                                   View right, Runnable onBack) {
        LinearLayout header = new LinearLayout(context);
        header.setOrientation(LinearLayout.HORIZONTAL);
        header.setGravity(Gravity.CENTER_VERTICAL);
        header.setPadding(dp(context, SPACE_PAGE), dp(context, HEADER_TOP),
                dp(context, SPACE_PAGE), dp(context, HEADER_BOTTOM));

        if (onBack != null) {
            TextView back = circleButton(context, palette, "←");
            back.setOnClickListener(v -> onBack.run());
            LinearLayout.LayoutParams backParams = new LinearLayout.LayoutParams(
                    dp(context, TOUCH_MIN), dp(context, TOUCH_MIN));
            backParams.setMargins(0, 0, dp(context, 10), 0);
            header.addView(back, backParams);
        }

        LinearLayout titles = new LinearLayout(context);
        titles.setOrientation(LinearLayout.VERTICAL);
        titles.addView(pageTitle(context, palette, title));
        if (subtitle != null && !subtitle.isEmpty()) {
            titles.addView(subTitle(context, palette, subtitle));
        }
        header.addView(titles, new LinearLayout.LayoutParams(
                0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f));

        if (right != null) {
            header.addView(right);
        }
        return header;
    }

    /** 细进度线：2dp 轨道 + 朱砂填充。 */
    static final class ProgressLine extends View {
        private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final int trackColor;
        private final int fillColor;
        private float fraction;

        ProgressLine(Context context, int trackColor, int fillColor) {
            super(context);
            this.trackColor = trackColor;
            this.fillColor = fillColor;
        }

        void setFraction(float value) {
            fraction = Math.max(0f, Math.min(1f, value));
            invalidate();
        }

        @Override
        protected void onDraw(Canvas canvas) {
            float height = Math.min(getHeight(), dp(getContext(), 2));
            float top = (getHeight() - height) / 2f;
            float radius = height / 2f;
            paint.setColor(trackColor);
            canvas.drawRoundRect(new RectF(0, top, getWidth(), top + height), radius, radius, paint);
            if (fraction > 0) {
                paint.setColor(fillColor);
                canvas.drawRoundRect(new RectF(0, top, getWidth() * fraction, top + height),
                        radius, radius, paint);
            }
        }
    }

    static ProgressLine progressLine(Context context, Palette palette) {
        ProgressLine line = new ProgressLine(context, palette.line, palette.accent);
        line.setLayoutParams(new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, dp(context, 2)));
        return line;
    }

    /** 在书中进度 0–10000 转 0–1。 */
    static float fraction(int percent) {
        return Math.max(0f, Math.min(1f, percent / 10000f));
    }

    /** 竖排书脊：朱砂渐变 + 逐字换行。 */
    static TextView spine(Context context, Palette palette, String title, int widthDp, int heightDp) {
        TextView view = new TextView(context);
        view.setText(vertical(title, 4));
        view.setTextSize(10);
        view.setTypeface(SERIF_BOLD);
        view.setTextColor(0xFFFFF6EA);
        view.setGravity(Gravity.CENTER);
        view.setLetterSpacing(0.08f);
        view.setLineSpacing(0, 0.9f);
        view.setPadding(0, dp(context, 5), 0, dp(context, 5));
        GradientDrawable background = new GradientDrawable(
                GradientDrawable.Orientation.TL_BR,
                new int[]{blend(palette.accent, 0xFF000000, 0.26f), palette.accent,
                        blend(palette.accent, 0xFFFFFFFF, 0.18f)});
        background.setCornerRadii(new float[]{
                dp(context, 2), dp(context, 2), dp(context, 4), dp(context, 4),
                dp(context, 4), dp(context, 4), dp(context, 2), dp(context, 2)});
        view.setBackground(background);
        view.setLayoutParams(new LinearLayout.LayoutParams(
                dp(context, widthDp), dp(context, heightDp)));
        return view;
    }

    /** 首二字书脊：用于搜索结果与较小的封面位。 */
    static TextView coverBadge(Context context, Palette palette, String title,
                               int widthDp, int heightDp) {
        TextView view = new TextView(context);
        String text = title == null || title.isBlank() ? "书" : title.trim();
        view.setText(text.length() <= 2 ? text : text.substring(0, 2));
        view.setTextSize(8);
        view.setTypeface(SERIF_BOLD);
        view.setTextColor(0xFFFFF6EA);
        view.setGravity(Gravity.CENTER);
        view.setLetterSpacing(0.1f);
        GradientDrawable background = new GradientDrawable(
                GradientDrawable.Orientation.TL_BR,
                new int[]{blend(palette.accent, 0xFF000000, 0.24f),
                        blend(palette.accent, 0xFFFFFFFF, 0.18f)});
        background.setCornerRadius(dp(context, 3));
        view.setBackground(background);
        view.setLayoutParams(new LinearLayout.LayoutParams(
                dp(context, widthDp), dp(context, heightDp)));
        return view;
    }

    private static String vertical(String title, int maxChars) {
        String text = title == null || title.isBlank() ? "书" : title.trim();
        if (text.length() > maxChars) {
            text = text.substring(0, maxChars);
        }
        StringBuilder builder = new StringBuilder();
        for (char c : text.toCharArray()) {
            if (builder.length() > 0) {
                builder.append('\n');
            }
            builder.append(c);
        }
        return builder.toString();
    }

    /** 清掉书源名前后常见的来源符号，只保留文字。 */
    static String clean(String value) {
        if (value == null) {
            return "";
        }
        return value.replaceAll("^[\\s\\u2192\\u00b7\\u2022\\u2708]+", "").trim();
    }

    static int blend(int color, int target, float ratio) {
        float r = ratio;
        int a = (int) (Color.alpha(color) + (Color.alpha(target) - Color.alpha(color)) * r);
        int red = (int) (Color.red(color) + (Color.red(target) - Color.red(color)) * r);
        int green = (int) (Color.green(color) + (Color.green(target) - Color.green(color)) * r);
        int blue = (int) (Color.blue(color) + (Color.blue(target) - Color.blue(color)) * r);
        return Color.argb(a, red, green, blue);
    }

    /** 底部 sheet：上圆角、半透明遮罩、贴底。fraction <= 0 时按内容高度。 */
    static Dialog sheet(Context context, Palette palette, View content, float fraction) {
        Dialog dialog = new Dialog(context);
        dialog.requestWindowFeature(Window.FEATURE_NO_TITLE);
        FrameLayout wrapper = new FrameLayout(context);
        wrapper.setBackground(sheetBackground(context, palette));
        int contentHeight = fraction <= 0
                ? FrameLayout.LayoutParams.WRAP_CONTENT
                : FrameLayout.LayoutParams.MATCH_PARENT;
        wrapper.addView(content, new FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT, contentHeight));
        dialog.setContentView(wrapper);
        Window window = dialog.getWindow();
        if (window != null) {
            window.setBackgroundDrawable(new ColorDrawable(Color.TRANSPARENT));
            window.addFlags(WindowManager.LayoutParams.FLAG_DIM_BEHIND);
            window.setDimAmount(0.38f);
            window.setGravity(Gravity.BOTTOM);
            int height = fraction <= 0
                    ? WindowManager.LayoutParams.WRAP_CONTENT
                    : (int) (context.getResources().getDisplayMetrics().heightPixels * fraction);
            window.setLayout(WindowManager.LayoutParams.MATCH_PARENT, height);
        }
        return dialog;
    }

    private static Drawable sheetBackground(Context context, Palette palette) {
        GradientDrawable fill = new GradientDrawable();
        fill.setColor(paper(palette));
        fill.setCornerRadii(new float[]{
                dp(context, RADIUS_SHEET), dp(context, RADIUS_SHEET),
                dp(context, RADIUS_SHEET), dp(context, RADIUS_SHEET),
                0, 0, 0, 0});
        return fill;
    }

    /** sheet 顶部把手。 */
    static View grabber(Context context, Palette palette) {
        View view = new View(context);
        view.setBackground(round(context, palette.line, 2));
        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(
                dp(context, 32), dp(context, 3));
        params.setMargins(0, dp(context, 9), 0, dp(context, 8));
        params.gravity = Gravity.CENTER_HORIZONTAL;
        view.setLayoutParams(params);
        return view;
    }

    /** 操作面板的一行：标题 + 说明 + 可选警示色。 */
    static LinearLayout actionRow(Context context, Palette palette, String title, String subtitle,
                                  boolean danger, Runnable action) {
        LinearLayout row = new LinearLayout(context);
        row.setOrientation(LinearLayout.VERTICAL);
        row.setPadding(dp(context, 18), dp(context, 13), dp(context, 18), dp(context, 13));
        row.setBackground(ripple(context, new ColorDrawable(Color.TRANSPARENT), 0x14000000));
        row.setClickable(true);

        TextView titleView = new TextView(context);
        titleView.setText(title);
        titleView.setTextSize(15);
        titleView.setTypeface(SERIF);
        titleView.setTextColor(danger ? palette.accent : palette.ink);
        row.addView(titleView);

        if (subtitle != null && !subtitle.isEmpty()) {
            TextView subView = new TextView(context);
            subView.setText(subtitle);
            subView.setTextSize(TEXT_META);
            subView.setTextColor(danger ? blend(palette.accent, palette.muted, 0.4f) : palette.muted);
            subView.setPadding(0, dp(context, 3), 0, 0);
            row.addView(subView);
        }

        row.setOnClickListener(v -> {
            if (action != null) {
                action.run();
            }
        });
        return row;
    }

    /** 带底部发丝线的页头/面板底。 */
    static Drawable panelBackground(Context context, Palette palette, boolean lineAtTop) {
        GradientDrawable fill = new GradientDrawable();
        fill.setColor(palette.panel);
        GradientDrawable line = new GradientDrawable();
        line.setColor(palette.line);
        LayerDrawable layers = new LayerDrawable(new Drawable[]{fill, line});
        layers.setLayerGravity(0, Gravity.FILL);
        layers.setLayerHeight(1, dp(context, 1));
        layers.setLayerGravity(1, lineAtTop ? Gravity.TOP : Gravity.BOTTOM);
        return layers;
    }

    /** 主题单选组保留给旧调用；新界面优先使用 segmented。 */
    static RadioGroup themeGroup(Context context, Palette palette, String current,
                                 Consumer<String> onChanged) {
        RadioGroup group = new RadioGroup(context);
        group.setOrientation(RadioGroup.HORIZONTAL);
        RadioButton paper = themeOption(context, palette, "纸白", "paper");
        RadioButton night = themeOption(context, palette, "夜间", "night");
        RadioButton green = themeOption(context, palette, "护眼", "green");
        group.addView(paper);
        group.addView(night);
        group.addView(green);
        if ("night".equals(current)) {
            night.setChecked(true);
        } else if ("green".equals(current)) {
            green.setChecked(true);
        } else {
            paper.setChecked(true);
        }
        boolean[] ready = {false};
        group.setOnCheckedChangeListener((g, checkedId) -> {
            if (!ready[0]) {
                return;
            }
            View checked = g.findViewById(checkedId);
            if (checked != null && onChanged != null) {
                onChanged.accept(String.valueOf(checked.getTag()));
            }
        });
        ready[0] = true;
        return group;
    }

    private static RadioButton themeOption(Context context, Palette palette, String text, String key) {
        RadioButton button = new RadioButton(context);
        button.setId(View.generateViewId());
        button.setText(text);
        button.setTag(key);
        button.setTextSize(TEXT_BODY);
        button.setTextColor(palette.ink);
        button.setButtonTintList(ColorStateList.valueOf(palette.accent));
        button.setGravity(Gravity.CENTER_VERTICAL);
        return button;
    }

    /** 对话框跟随主题：夜间用深色，其余用浅色。 */
    static AlertDialog.Builder dialog(Context context, String theme) {
        int style = "night".equals(theme)
                ? android.R.style.Theme_Material_Dialog
                : android.R.style.Theme_Material_Light_Dialog;
        return new AlertDialog.Builder(context, style);
    }

    /** 阅读页允许窗口画进屏幕开孔区，否则隐藏状态栏后顶部会留下一条黑边。 */
    static void allowDisplayCutout(Activity activity) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            activity.getWindow().getAttributes().layoutInDisplayCutoutMode =
                    android.view.WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES;
        }
    }

    /** 边到边：窗口始终铺满屏幕，系统栏显隐不改变正文布局尺寸。 */
    static void edgeToEdge(Activity activity) {
        Window window = activity.getWindow();
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            window.setDecorFitsSystemWindows(false);
        } else {
            int flags = window.getDecorView().getSystemUiVisibility()
                    | View.SYSTEM_UI_FLAG_LAYOUT_STABLE
                    | View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN
                    | View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION;
            window.getDecorView().setSystemUiVisibility(flags);
        }
    }

    /** 阅读页沉浸模式：隐藏系统状态栏与导航栏，边缘轻扫可临时呼出，不占正文空间。 */
    static void immersive(Activity activity, boolean on) {
        Window window = activity.getWindow();
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            WindowInsetsController controller = window.getInsetsController();
            if (controller == null) {
                return;
            }
            if (on) {
                controller.hide(WindowInsets.Type.systemBars());
                controller.setSystemBarsBehavior(
                        WindowInsetsController.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE);
            } else {
                controller.show(WindowInsets.Type.systemBars());
            }
            return;
        }
        int flags = window.getDecorView().getSystemUiVisibility();
        int immersiveFlags = View.SYSTEM_UI_FLAG_FULLSCREEN
                | View.SYSTEM_UI_FLAG_HIDE_NAVIGATION
                | View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY;
        flags = on ? flags | immersiveFlags : flags & ~immersiveFlags;
        window.getDecorView().setSystemUiVisibility(flags);
    }

    /** 把状态栏、导航栏染成主题色，浅色主题使用深色图标。 */
    static void systemBars(Activity activity, Palette palette) {
        Window window = activity.getWindow();
        window.setStatusBarColor(palette.panel);
        window.setNavigationBarColor(palette.bg);
        boolean lightBackground = !palette.dark;
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            WindowInsetsController controller = window.getInsetsController();
            if (controller != null) {
                int appearance = lightBackground
                        ? WindowInsetsController.APPEARANCE_LIGHT_STATUS_BARS
                        | WindowInsetsController.APPEARANCE_LIGHT_NAVIGATION_BARS
                        : 0;
                int mask = WindowInsetsController.APPEARANCE_LIGHT_STATUS_BARS
                        | WindowInsetsController.APPEARANCE_LIGHT_NAVIGATION_BARS;
                controller.setSystemBarsAppearance(appearance, mask);
            }
        } else {
            int flags = window.getDecorView().getSystemUiVisibility();
            int lightFlags = View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR
                    | View.SYSTEM_UI_FLAG_LIGHT_NAVIGATION_BAR;
            if (lightBackground) {
                flags |= lightFlags;
            } else {
                flags &= ~lightFlags;
            }
            window.getDecorView().setSystemUiVisibility(flags);
        }
    }
}
