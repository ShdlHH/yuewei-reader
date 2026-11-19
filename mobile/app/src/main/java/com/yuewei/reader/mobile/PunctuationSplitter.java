package com.yuewei.reader.mobile;

import android.text.SpannableString;
import android.text.Spanned;
import android.text.style.ForegroundColorSpan;

/**
 * 按标点把正文着色：对话、句号、问号、感叹号、省略号/破折号、括号。
 * 规则与桌面端 PunctuationHighlighter 对齐；这里直接给 SpannableString 上色。
 */
final class PunctuationSplitter {

    /** colors / rules 数组下标。 */
    static final int DIALOGUE = 0;
    static final int STOP = 1;
    static final int ASK = 2;
    static final int EXCL = 3;
    static final int ELLIPSIS = 4;
    static final int PAREN = 5;

    private PunctuationSplitter() {
    }

    static void apply(SpannableString text, boolean enabled, boolean[] rules, int[] colors) {
        if (text == null || !enabled || text.length() == 0) {
            return;
        }
        boolean[] active = rules == null
                ? new boolean[]{true, true, true, true, false, false} : rules;
        int quotes = 0;
        int parens = 0;
        int runStart = 0;
        int runRole = -1;
        for (int i = 0; i < text.length(); i++) {
            char ch = text.charAt(i);
            int role = roleOf(ch, active, quotes, parens);
            if (role != runRole) {
                colorize(text, runStart, i, runRole, colors);
                runStart = i;
                runRole = role;
            }
            if (isOpenQuote(ch)) {
                quotes++;
            } else if (isCloseQuote(ch)) {
                quotes = Math.max(0, quotes - 1);
            } else if (isOpenParen(ch)) {
                parens++;
            } else if (isCloseParen(ch)) {
                parens = Math.max(0, parens - 1);
            }
        }
        colorize(text, runStart, text.length(), runRole, colors);
    }

    private static void colorize(SpannableString text, int start, int end, int role, int[] colors) {
        if (role < 0 || end <= start || colors == null || role >= colors.length) {
            return;
        }
        text.setSpan(new ForegroundColorSpan(colors[role]), start, end,
                Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
    }

    private static int roleOf(char ch, boolean[] rules, int quotes, int parens) {
        if (rules[PAREN] && (parens > 0 || isOpenParen(ch) || isCloseParen(ch))) {
            return PAREN;
        }
        switch (ch) {
            case '。':
                return rules[STOP] ? STOP : -1;
            case '？':
            case '?':
                return rules[ASK] ? ASK : -1;
            case '！':
            case '!':
                return rules[EXCL] ? EXCL : -1;
            case '…':
            case '—':
                return rules[ELLIPSIS] ? ELLIPSIS : -1;
            default:
                break;
        }
        if (rules[DIALOGUE] && (quotes > 0 || isOpenQuote(ch) || isCloseQuote(ch))) {
            return DIALOGUE;
        }
        return -1;
    }

    private static boolean isOpenQuote(char ch) {
        return ch == '“' || ch == '「' || ch == '『';
    }

    private static boolean isCloseQuote(char ch) {
        return ch == '”' || ch == '」' || ch == '』';
    }

    private static boolean isOpenParen(char ch) {
        return ch == '（' || ch == '(' || ch == '【';
    }

    private static boolean isCloseParen(char ch) {
        return ch == '）' || ch == ')' || ch == '】';
    }
}
