package com.yuewei.reader.infrastructure.txt;

import java.util.ArrayList;
import java.util.List;

/**
 * 按标点把正文切成着色片段：对话内容、句号、问号、感叹号、省略号/破折号、括号。
 * 只做分段，不涉及颜色与界面，便于单独测试。
 */
public final class PunctuationHighlighter {

    public enum Role { INK, DIALOGUE, STOP, ASK, EXCL, ELLIPSIS, PAREN }

    public record Run(String text, Role role) {
    }

    /** 六条规则的开关；默认开对话、句号、问号、感叹号。 */
    public record Rules(boolean dialogue, boolean stop, boolean ask, boolean excl,
                        boolean ellipsis, boolean paren) {

        public static Rules defaults() {
            return new Rules(true, true, true, true, false, false);
        }

        public static Rules allEnabled() {
            return new Rules(true, true, true, true, true, true);
        }
    }

    private PunctuationHighlighter() {
    }

    public static List<Run> highlight(String content, Rules rules) {
        List<Run> runs = new ArrayList<>();
        if (content == null || content.isEmpty()) {
            return runs;
        }
        Rules active = rules == null ? Rules.defaults() : rules;
        StringBuilder buffer = new StringBuilder();
        Role current = null;
        int quotes = 0;
        int parens = 0;

        for (int i = 0; i < content.length(); i++) {
            char ch = content.charAt(i);
            Role role = roleOf(ch, active, quotes, parens);
            if (role != current) {
                flush(runs, buffer, current);
                current = role;
            }
            buffer.append(ch);

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
        flush(runs, buffer, current);
        return runs;
    }

    private static Role roleOf(char ch, Rules rules, int quotes, int parens) {
        if (rules.paren() && (parens > 0 || isOpenParen(ch) || isCloseParen(ch))) {
            return Role.PAREN;
        }
        Role punctuation = switch (ch) {
            case '。' -> Role.STOP;
            case '？', '?' -> Role.ASK;
            case '！', '!' -> Role.EXCL;
            case '…', '—' -> Role.ELLIPSIS;
            default -> null;
        };
        if (punctuation != null && enabled(punctuation, rules)) {
            return punctuation;
        }
        if (rules.dialogue() && (quotes > 0 || isOpenQuote(ch) || isCloseQuote(ch))) {
            return Role.DIALOGUE;
        }
        return Role.INK;
    }

    private static boolean enabled(Role role, Rules rules) {
        return switch (role) {
            case STOP -> rules.stop();
            case ASK -> rules.ask();
            case EXCL -> rules.excl();
            case ELLIPSIS -> rules.ellipsis();
            default -> false;
        };
    }

    private static void flush(List<Run> runs, StringBuilder buffer, Role role) {
        if (role != null && buffer.length() > 0) {
            runs.add(new Run(buffer.toString(), role));
            buffer.setLength(0);
        }
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
