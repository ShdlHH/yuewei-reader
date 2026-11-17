package com.yuewei.reader.mobile;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Color;
import android.text.Layout;
import android.text.StaticLayout;
import android.text.TextPaint;
import android.view.GestureDetector;
import android.view.MotionEvent;
import android.view.View;

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Pattern;

/** 按页排版正文：StaticLayout 计算行高，翻页只改变起始行，不重建文本。 */
public class PageView extends View {

    public interface Listener {
        void onPageChanged(int page, int pages, int percent);

        /** 到达最后一页继续后翻，返回是否成功切换章节。 */
        boolean onNextChapter();

        /** 到达第一页继续前翻，返回是否成功切换章节。 */
        boolean onPreviousChapter();
    }

    private static final Pattern CHAPTER_TITLE =
            Pattern.compile("^第[0-9零一二三四五六七八九十百千万两]+[章回节卷部篇集][\\s　:：、.．]?");

    private final TextPaint paint = new TextPaint(TextPaint.ANTI_ALIAS_FLAG);
    private final GestureDetector gestures;
    private final int padding;

    private String text = "";
    private StaticLayout layout;
    private int pageIndex;
    private int pageCount = 1;
    private final List<Integer> pageStartLines = new ArrayList<>();
    private float lineSpacing = 1.8f;
    private Listener listener;

    public PageView(Context context) {
        super(context);
        padding = dp(24);
        paint.setColor(Color.parseColor("#2F2A24"));
        paint.setTextSize(sp(18));
        gestures = new GestureDetector(context, new GestureDetector.SimpleOnGestureListener() {
            @Override
            public boolean onDown(MotionEvent e) {
                return true;
            }

            @Override
            public boolean onSingleTapUp(MotionEvent e) {
                if (e.getX() < getWidth() * 0.35f) {
                    previousPage();
                } else {
                    nextPage();
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
        paint.setTextSize(sp(fontSizeSp));
        paint.setColor(color);
        this.lineSpacing = Math.max(1.2f, Math.min(3.0f, lineSpacing));
        rebuild();
    }

    public void setText(String value) {
        text = indent(value == null ? "" : value);
        pageIndex = 0;
        rebuild();
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
            invalidate();
            notifyPage();
        } else if (listener != null) {
            listener.onNextChapter();
        }
    }

    public void previousPage() {
        if (pageIndex > 0) {
            pageIndex--;
            invalidate();
            notifyPage();
        } else if (listener != null) {
            listener.onPreviousChapter();
        }
    }

    public void toLastPage() {
        pageIndex = Math.max(0, pageCount - 1);
        invalidate();
        notifyPage();
    }

    @Override
    protected void onSizeChanged(int w, int h, int oldw, int oldh) {
        super.onSizeChanged(w, h, oldw, oldh);
        rebuild();
    }

    private void rebuild() {
        int width = Math.max(dp(120), getWidth() - padding * 2);
        if (text.isEmpty()) {
            layout = null;
            pageCount = 1;
            pageStartLines.clear();
            pageStartLines.add(0);
            invalidate();
            return;
        }
        layout = StaticLayout.Builder
                .obtain(text, 0, text.length(), paint, width)
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
        int usable = Math.max(dp(80), getHeight() - padding * 2);
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
        canvas.save();
        canvas.clipRect(padding, padding, getWidth() - padding, getHeight() - padding);
        canvas.translate(padding, padding - layout.getLineTop(line));
        layout.draw(canvas);
        canvas.restore();
    }

    @Override
    public boolean onTouchEvent(MotionEvent event) {
        return gestures.onTouchEvent(event) || super.onTouchEvent(event);
    }

    private void notifyPage() {
        if (listener != null) {
            listener.onPageChanged(pageIndex + 1, pageCount, currentPercent());
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

    private int dp(int value) {
        return Math.round(value * getResources().getDisplayMetrics().density);
    }

    private float sp(int value) {
        return value * getResources().getDisplayMetrics().scaledDensity;
    }
}
