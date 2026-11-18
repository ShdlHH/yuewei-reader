package com.yuewei.reader.mobile;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.Context;
import android.content.res.ColorStateList;
import android.graphics.Typeface;
import android.graphics.drawable.Drawable;
import android.graphics.drawable.GradientDrawable;
import android.graphics.drawable.RippleDrawable;
import android.os.Build;
import android.view.Gravity;
import android.view.View;
import android.view.Window;
import android.view.WindowInsetsController;
import android.widget.TextView;

/** 统一的配色、圆角与按钮样式，书架/阅读/设置共用。 */
final class Ui {

    /** 一套主题颜色，与桌面端纸白/夜间/护眼对应。 */
    static final class Palette {
        final int bg;
        final int panel;
        final int card;
        final int ink;
        final int muted;
        final int accent;
        final int line;

        Palette(int bg, int panel, int card, int ink, int muted, int accent, int line) {
            this.bg = bg;
            this.panel = panel;
            this.card = card;
            this.ink = ink;
            this.muted = muted;
            this.accent = accent;
            this.line = line;
        }
    }

    static final Typeface SERIF = Typeface.create("serif", Typeface.NORMAL);
    static final Typeface SERIF_BOLD = Typeface.create("serif", Typeface.BOLD);

    private Ui() {
    }

    static Palette palette(String theme) {
        if ("night".equals(theme)) {
            return new Palette(0xFF1B1C20, 0xFF24262C, 0xFF23252A,
                    0xFFC8C6C0, 0xFF928E85, 0xFFC0695C, 0xFF34363C);
        }
        if ("green".equals(theme)) {
            return new Palette(0xFFE6EFE4, 0xFFD8E6D5, 0xFFEFF5EC,
                    0xFF2D3A2B, 0xFF5F6D5C, 0xFF3F5C48, 0xFFCBDCC6);
        }
        return new Palette(0xFFF6F1E7, 0xFFECE4D4, 0xFFFBF7EF,
                0xFF2F2A24, 0xFF6B6257, 0xFFA63D32, 0xFFE5DCCB);
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
        view.setTextSize(14);
        view.setGravity(Gravity.CENTER);
        view.setPadding(dp(context, 16), dp(context, 10), dp(context, 16), dp(context, 10));
        view.setClickable(true);
        view.setFocusable(true);
        if (primary) {
            view.setTextColor(0xFFFFFFFF);
            view.setBackground(ripple(context, round(context, palette.accent, 22), 0x33FFFFFF));
        } else {
            view.setTextColor(palette.ink);
            view.setBackground(ripple(context,
                    roundStroke(context, palette.card, palette.line, 22), 0x1F000000));
        }
        return view;
    }

    /** 对话框跟随主题：夜间用深色，其余用浅色。 */
    static AlertDialog.Builder dialog(Context context, String theme) {
        int style = "night".equals(theme)
                ? android.R.style.Theme_Material_Dialog
                : android.R.style.Theme_Material_Light_Dialog;
        return new AlertDialog.Builder(context, style);
    }

    /** 把状态栏、导航栏染成主题色，浅色主题使用深色图标。 */
    static void systemBars(Activity activity, Palette palette) {
        Window window = activity.getWindow();
        window.setStatusBarColor(palette.panel);
        window.setNavigationBarColor(palette.bg);
        boolean lightBackground = palette.bg != 0xFF1B1C20;
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            WindowInsetsController controller = window.getInsetsController();
            if (controller != null) {
                controller.setSystemBarsAppearance(
                        lightBackground ? WindowInsetsController.APPEARANCE_LIGHT_STATUS_BARS : 0,
                        WindowInsetsController.APPEARANCE_LIGHT_STATUS_BARS);
            }
        } else {
            int flags = window.getDecorView().getSystemUiVisibility();
            if (lightBackground) {
                flags |= View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR;
            } else {
                flags &= ~View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR;
            }
            window.getDecorView().setSystemUiVisibility(flags);
        }
    }
}
