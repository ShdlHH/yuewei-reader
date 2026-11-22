package com.yuewei.reader.mobile;

import android.animation.Animator;
import android.animation.AnimatorListenerAdapter;
import android.animation.ValueAnimator;
import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.Typeface;
import android.text.Layout;
import android.text.SpannableString;
import android.text.StaticLayout;
import android.text.TextPaint;
import android.view.GestureDetector;
import android.view.MotionEvent;
import android.view.View;
import android.view.animation.PathInterpolator;

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Pattern;

/** 按页排版正文：StaticLayout 计算行高，翻页只改变起始行，不重建文本。 */
public class PageView extends View {

    public interface Listener {
        /** userTurn=true 表示用户主动翻页；排版、跳转、缩窗引起的重排不应写进度。 */
        void onPageChanged(int page, int pages, int percent, boolean userTurn);

        /** 到达最后一页继续后翻，返回是否成功切换章节。 */
        boolean onNextChapter();

        /** 到达第一页继续前翻，返回是否成功切换章节。 */
        boolean onPreviousChapter();

        /** 轻点页面中间：呼出或收起上下工具栏。 */
        default void onToggleChrome() {
        }
    }

    private static final Pattern CHAPTER_TITLE =
            Pattern.compile("^第[0-9零一二三四五六七八九十百千万两]+[章回节卷部篇集][\\s　:：、.．]?");

    private final TextPaint paint = new TextPaint(TextPaint.ANTI_ALIAS_FLAG);
    private final GestureDetector gestures;
    private final int paddingX;
    private final int paddingY;

    private String text = "";
    private StaticLayout layout;
    private int pageIndex;
    private int pageCount = 1;
    private final List<Integer> pageStartLines = new ArrayList<>();
    private float lineSpacing = 1.8f;
    private Typeface typeface = Ui.SERIF;
    /** 标点着色开关、六条规则与主题色。 */
    private boolean punctEnabled;
    private boolean[] punctRules = {true, true, true, true, false, false};
    private int[] punctColors = new int[6];
    /** 屏幕开孔/刘海的安全区，正文绘制与分页都要避开。 */
    private int safeTop;
    private int safeBottom;
    /** 上下工具栏显示时占用的高度，正文同样要避开，否则会被切掉半行。 */
    private int chromeTop;
    private int chromeBottom;
    private Listener listener;
    /** 本次 notifyPage 是否由用户主动翻页触发。 */
    private boolean userTurn;
    /** 翻页过渡：横向轻移 + 淡入，只影响绘制，不改变分页。 */
    private ValueAnimator turnAnimator;
    private float turnShift;

    public PageView(Context context) {
        super(context);
        paddingX = dp(24);
        // 系统栏隐藏后正文直接贴屏幕边缘，上下各留 12dp 呼吸空间
        paddingY = dp(12);
        paint.setColor(Color.parseColor("#2F2A24"));
        paint.setTextSize(sp(18));
        // 正文使用衬线字体，接近桌面端的宋体排版
        paint.setTypeface(Ui.SERIF);
        paint.setSubpixelText(true);
        gestures = new GestureDetector(context, new GestureDetector.SimpleOnGestureListener() {
            @Override
            public boolean onDown(MotionEvent e) {
                return true;
            }

            @Override
            public boolean onSingleTapUp(MotionEvent e) {
                float width = getWidth();
                if (e.getX() < width * 0.32f) {
                    previousPage();
                } else if (e.getX() > width * 0.68f) {
                    nextPage();
                } else if (listener != null) {
                    listener.onToggleChrome();
                }
                return true;
            }

            @Override
            public boolean onFling(MotionEvent e1, MotionEvent e2, float velocityX, float velocityY) {
                if (e1 == null || e2 == null) {
                    return false;
                }
                float dx = e2.getX() - e1.getX();
                if (dx < -dp(40)) {
                    nextPage();
                } else if (dx > dp(40)) {
                    previousPage();
                }
                return true;
            }
        });
    }

    public void setListener(Listener listener) {
        this.listener = listener;
    }

    public void setStyle(int fontSizeSp, float lineSpacing, int color) {
        setStyle(fontSizeSp, lineSpacing, color, typeface);
    }

    public void setStyle(int fontSizeSp, float lineSpacing, int color, Typeface typeface) {
        int percent = currentPercent();
        boolean hadPages = layout != null && pageCount > 1;
        this.typeface = typeface == null ? Ui.SERIF : typeface;
        paint.setTypeface(this.typeface);
        paint.setTextSize(sp(fontSizeSp));
        paint.setColor(color);
        this.lineSpacing = Math.max(1.2f, Math.min(3.0f, lineSpacing));
        rebuild();
        restorePercent(percent, hadPages);
    }

    /** 设置标点着色；颜色只影响绘制，不改变分页。 */
    public void setPunctuation(boolean enabled, boolean[] rules, int[] colors) {
        this.punctEnabled = enabled;
        this.punctRules = rules == null
                ? new boolean[]{true, true, true, true, false, false} : rules.clone();
        this.punctColors = colors == null ? new int[6] : colors.clone();
        rebuild();
    }

    public void setText(String value) {
        text = indent(value == null ? "" : value);
        pageIndex = 0;
        rebuild();
    }

    /** 设置屏幕开孔安全区；变化后重新分页并保持阅读位置。 */
    public void setSafeInsets(int top, int bottom) {
        setInsets(top, bottom, chromeTop, chromeBottom);
    }

    /** 顶部/底部工具栏显示时让正文避让对应高度；收起后传 0 恢复满屏。 */
    public void setChromeInsets(int top, int bottom) {
        setInsets(safeTop, safeBottom, top, bottom);
    }

    /** 一次性更新四类留白，避免连续两次重排。 */
    public void setInsets(int safeTopValue, int safeBottomValue,
                          int chromeTopValue, int chromeBottomValue) {
        int top = Math.max(0, safeTopValue);
        int bottom = Math.max(0, safeBottomValue);
        int cTop = Math.max(0, chromeTopValue);
        int cBottom = Math.max(0, chromeBottomValue);
        if (top == safeTop && bottom == safeBottom
                && cTop == chromeTop && cBottom == chromeBottom) {
            return;
        }
        applyInsetsChange(top, bottom, cTop, cBottom);
    }

    private void applyInsetsChange(int safeTopValue, int safeBottomValue,
                                   int chromeTopValue, int chromeBottomValue) {
        int percent = currentPercent();
        boolean hadPages = layout != null && pageCount > 1;
        safeTop = safeTopValue;
        safeBottom = safeBottomValue;
        chromeTop = chromeTopValue;
        chromeBottom = chromeBottomValue;
        rebuild();
        restorePercent(percent, hadPages);
    }

    public void goToPercent(int percent) {
        if (pageCount <= 1) {
            pageIndex = 0;
        } else {
            pageIndex = Math.max(0, Math.min(pageCount - 1,
                    Math.round(percent / 10000f * (pageCount - 1))));
        }
        invalidate();
        notifyPage();
    }

    public int currentPercent() {
        return pageCount <= 1 ? 0 : (int) Math.round(pageIndex * 10000.0 / (pageCount - 1));
    }

    public void nextPage() {
        if (pageIndex < pageCount - 1) {
            pageIndex++;
            userTurn = true;
            playTurnAnimation(true);
            notifyPage();
        } else if (listener != null) {
            listener.onNextChapter();
        }
    }

    public void previousPage() {
        if (pageIndex > 0) {
            pageIndex--;
            userTurn = true;
            playTurnAnimation(false);
            notifyPage();
        } else if (listener != null) {
            listener.onPreviousChapter();
        }
    }

    public void toLastPage() {
        pageIndex = Math.max(0, pageCount - 1);
        userTurn = true;
        invalidate();
        notifyPage();
    }

    /** 新页从阅读方向轻移 8% 并淡入，160ms 收住；系统关闭动画时直接重绘。 */
    private void playTurnAnimation(boolean forward) {
        cancelTurnAnimation();
        if (!ValueAnimator.areAnimatorsEnabled() || getWidth() <= 0) {
            invalidate();
            return;
        }
        float start = getWidth() * 0.06f * (forward ? 1f : -1f);
        ValueAnimator animator = ValueAnimator.ofFloat(start, 0f);
        animator.setDuration(200);
        animator.setInterpolator(new PathInterpolator(0.22f, 0.8f, 0.24f, 1f));
        setAlpha(0.55f);
        animator.addUpdateListener(animation -> {
            turnShift = (float) animation.getAnimatedValue();
            setAlpha(0.55f + 0.45f * animation.getAnimatedFraction());
            invalidate();
        });
        animator.addListener(new AnimatorListenerAdapter() {
            @Override
            public void onAnimationEnd(Animator animation) {
                turnShift = 0f;
                setAlpha(1f);
                turnAnimator = null;
                invalidate();
            }
        });
        turnAnimator = animator;
        animator.start();
    }

    private void cancelTurnAnimation() {
        if (turnAnimator != null) {
            turnAnimator.cancel();
            turnAnimator = null;
        }
        turnShift = 0f;
        setAlpha(1f);
    }

    @Override
    protected void onSizeChanged(int w, int h, int oldw, int oldh) {
        super.onSizeChanged(w, h, oldw, oldh);
        // 沉浸模式切换、旋转等尺寸变化后按百分比回到原阅读位置
        int percent = currentPercent();
        boolean hadPages = layout != null && pageCount > 1;
        rebuild();
        restorePercent(percent, hadPages);
    }

    /** 分页结果变化后按百分比找回原来的位置。 */
    private void restorePercent(int percent, boolean hadPages) {
        if (!hadPages || pageCount <= 1) {
            return;
        }
        pageIndex = Math.max(0, Math.min(pageCount - 1,
                Math.round(percent / 10000f * (pageCount - 1))));
        invalidate();
        notifyPage();
    }

    private void rebuild() {
        cancelTurnAnimation();
        int width = Math.max(dp(120), getWidth() - paddingX * 2);
        if (text.isEmpty()) {
            layout = null;
            pageCount = 1;
            pageStartLines.clear();
            pageStartLines.add(0);
            invalidate();
            return;
        }
        layout = StaticLayout.Builder
                .obtain(styledText(text), 0, text.length(), paint, width)
                .setLineSpacing(0, lineSpacing)
                .setIncludePad(false)
                .setBreakStrategy(Layout.BREAK_STRATEGY_SIMPLE)
                .build();
        computePages();
        if (pageIndex >= pageCount) {
            pageIndex = pageCount - 1;
        }
        invalidate();
        notifyPage();
    }

    private void computePages() {
        pageStartLines.clear();
        int usable = Math.max(dp(80), getHeight() - paddingY * 2
                - safeTop - safeBottom - chromeTop - chromeBottom);
        pageStartLines.add(0);
        int startLine = 0;
        int pageTop = layout.getLineTop(0);
        for (int line = 0; line < layout.getLineCount(); line++) {
            if (layout.getLineBottom(line) - pageTop > usable && line > startLine) {
                startLine = line;
                pageTop = layout.getLineTop(line);
                pageStartLines.add(line);
            }
        }
        pageCount = pageStartLines.size();
    }

    @Override
    protected void onDraw(Canvas canvas) {
        super.onDraw(canvas);
        if (layout == null || pageStartLines.isEmpty()) {
            return;
        }
        int line = pageStartLines.get(Math.min(pageIndex, pageStartLines.size() - 1));
        int lastLine = pageIndex + 1 < pageStartLines.size()
                ? pageStartLines.get(pageIndex + 1) - 1
                : layout.getLineCount() - 1;
        int pageTop = layout.getLineTop(line);
        int contentTop = paddingY + safeTop + chromeTop;
        // 只画本页的行：裁剪到本页最后一行的下沿，避免下一页首行从页尾空隙露出半截
        int contentBottom = contentTop + (layout.getLineBottom(lastLine) - pageTop);
        canvas.save();
        canvas.clipRect(paddingX, contentTop, getWidth() - paddingX,
                Math.min(getHeight() - paddingY - safeBottom - chromeBottom, contentBottom));
        canvas.translate(paddingX + turnShift, contentTop - pageTop);
        layout.draw(canvas);
        canvas.restore();
    }

    @Override
    public boolean onTouchEvent(MotionEvent event) {
        return gestures.onTouchEvent(event) || super.onTouchEvent(event);
    }

    private void notifyPage() {
        boolean user = userTurn;
        userTurn = false;
        if (listener != null) {
            listener.onPageChanged(pageIndex + 1, pageCount, currentPercent(), user);
        }
    }

    /** 每个自然段首行缩进两字，章节标题不缩进，与桌面端保持一致。 */
    static String indent(String content) {
        String[] lines = content.split("\n", -1);
        StringBuilder sb = new StringBuilder(content.length() + lines.length * 2);
        for (int i = 0; i < lines.length; i++) {
            if (i > 0) {
                sb.append('\n');
            }
            String line = lines[i];
            if (!line.isBlank()) {
                String trimmed = line.stripLeading();
                if (!CHAPTER_TITLE.matcher(trimmed).find()) {
                    sb.append("　　");
                }
                sb.append(trimmed);
            }
        }
        return sb.toString();
    }

    /** 只做标点着色；行距统一交给 setLineSpacing，避免段首行被额外撑高。 */
    private SpannableString styledText(String value) {
        SpannableString spannable = new SpannableString(value);
        PunctuationSplitter.apply(spannable, punctEnabled, punctRules, punctColors);
        return spannable;
    }

    private int dp(int value) {
        return Math.round(value * getResources().getDisplayMetrics().density);
    }

    private float sp(int value) {
        return value * getResources().getDisplayMetrics().scaledDensity;
    }
}
