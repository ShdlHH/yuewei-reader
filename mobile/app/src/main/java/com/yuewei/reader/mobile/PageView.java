package com.yuewei.reader.mobile;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.text.Layout;
import android.text.SpannableString;
import android.text.Spanned;
import android.text.StaticLayout;
import android.text.TextPaint;
import android.text.style.LineHeightSpan;
import android.view.GestureDetector;
import android.view.MotionEvent;
import android.view.View;

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
    /** 屏幕开孔/刘海的安全区，正文绘制与分页都要避开。 */
    private int safeTop;
    private int safeBottom;
    /** 上下工具栏显示时占用的高度，正文同样要避开，否则会被切掉半行。 */
    private int chromeTop;
    private int chromeBottom;
    private Listener listener;
    /** 本次 notifyPage 是否由用户主动翻页触发。 */
    private boolean userTurn;

    public PageView(Context context) {
        super(context);
        paddingX = dp(20);
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
        int percent = currentPercent();
        boolean hadPages = layout != null && pageCount > 1;
        paint.setTextSize(sp(fontSizeSp));
        paint.setColor(color);
        this.lineSpacing = Math.max(1.2f, Math.min(3.0f, lineSpacing));
        rebuild();
        restorePercent(percent, hadPages);
    }

    public void setText(String value) {
        text = indent(value == null ? "" : value);
        pageIndex = 0;
        rebuild();
    }

    /** 设置屏幕开孔安全区；变化后重新分页并保持阅读位置。 */
    public void setSafeInsets(int top, int bottom) {
        int safeTopValue = Math.max(0, top);
        int safeBottomValue = Math.max(0, bottom);
        if (safeTopValue == safeTop && safeBottomValue == safeBottom) {
            return;
        }
        applyInsetsChange(safeTopValue, safeBottomValue, chromeTop, chromeBottom);
    }

    /** 顶部/底部工具栏显示时让正文避让对应高度；收起后传 0 恢复满屏。 */
    public void setChromeInsets(int top, int bottom) {
        int chromeTopValue = Math.max(0, top);
        int chromeBottomValue = Math.max(0, bottom);
        if (chromeTopValue == chromeTop && chromeBottomValue == chromeBottom) {
            return;
        }
        applyInsetsChange(safeTop, safeBottom, chromeTopValue, chromeBottomValue);
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
            invalidate();
            notifyPage();
        } else if (listener != null) {
            listener.onNextChapter();
        }
    }

    public void previousPage() {
        if (pageIndex > 0) {
            pageIndex--;
            userTurn = true;
            invalidate();
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
                .obtain(withParagraphGaps(text), 0, text.length(), paint, width)
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
        canvas.translate(paddingX, contentTop - pageTop);
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

    /** 段落之间加一点额外间距，避免整页文字过密；首段不加。 */
    private CharSequence withParagraphGaps(String value) {
        int gap = Math.round(paint.getTextSize() * 0.35f);
        if (gap <= 0 || value.length() < 2) {
            return value;
        }
        SpannableString spannable = new SpannableString(value);
        for (int i = 1; i < value.length(); i++) {
            if (value.charAt(i - 1) == '\n' && value.charAt(i) != '\n') {
                spannable.setSpan(new ParagraphGapSpan(gap), i, i + 1,
                        Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
            }
        }
        return spannable;
    }

    /** 仅抬高段落首行的上沿，形成段间距。 */
    private static final class ParagraphGapSpan implements LineHeightSpan {
        private final int gap;

        ParagraphGapSpan(int gap) {
            this.gap = gap;
        }

        @Override
        public void chooseHeight(CharSequence text, int start, int end, int spanstartv,
                                 int lineHeight, Paint.FontMetricsInt metrics) {
            if (spanstartv == 0) {
                return;
            }
            metrics.ascent -= gap;
            metrics.top -= gap;
        }
    }

    private int dp(int value) {
        return Math.round(value * getResources().getDisplayMetrics().density);
    }

    private float sp(int value) {
        return value * getResources().getDisplayMetrics().scaledDensity;
    }
}
