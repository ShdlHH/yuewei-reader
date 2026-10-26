package com.yuewei.reader.infrastructure.txt;

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Pattern;

/**
 * TXT 分章。默认规则覆盖常见中英文标题行；识别不到时抛 {@link NoChaptersException}，
 * 由上层让用户选择固定字数切分或整本单章。
 */
public final class ChapterSplitter {

    public enum Mode {
        AUTO, FIXED, SINGLE, REGEX
    }

    public static class NoChaptersException extends RuntimeException {
        public NoChaptersException(String message) {
            super(message);
        }
    }

    public static class Result {
        public final List<String> titles = new ArrayList<>();
        public final List<String> contents = new ArrayList<>();
    }

    private static final Pattern[] HEAD_PATTERNS = {
            Pattern.compile("^第[0-9〇零一二三四五六七八九十百千万两]+[章回节卷集部篇].*"),
            Pattern.compile("^(序章|序言|楔子|引子|前言|后记|尾声|番外|终章).*"),
            Pattern.compile("^(?i:chapter)\\s+\\d+.*")
    };

    private ChapterSplitter() {
    }

    public static Result splitAuto(String text) {
        return splitByHeading(text, line -> {
            String t = line.trim();
            if (t.isEmpty() || t.length() > 45 || t.endsWith("。")) {
                return false;
            }
            for (Pattern p : HEAD_PATTERNS) {
                if (p.matcher(t).matches()) {
                    return true;
                }
            }
            return false;
        }, 2);
    }

    public static Result splitByRegex(String text, String regex) {
        Pattern pattern;
        try {
            pattern = Pattern.compile(regex);
        } catch (Exception e) {
            throw new NoChaptersException("自定义章节规则不是合法正则：" + e.getMessage());
        }
        return splitByHeading(text, line -> {
            String t = line.trim();
            return !t.isEmpty() && t.length() <= 80 && pattern.matcher(t).find();
        }, 1);
    }

    public static Result splitByFixedSize(String text, int size) {
        int chunk = Math.max(500, size);
        Result r = new Result();
        String normalized = text.replace("\r\n", "\n").replace('\r', '\n');
        int start = 0;
        int part = 1;
        while (start < normalized.length()) {
            int end = Math.min(normalized.length(), start + chunk);
            if (end < normalized.length()) {
                int nl = normalized.indexOf('\n', end);
                if (nl > 0 && nl - end < chunk / 2) {
                    end = nl + 1;
                }
            }
            r.titles.add("第 " + part + " 部分");
            r.contents.add(normalized.substring(start, end).trim());
            start = end;
            part++;
        }
        if (r.titles.isEmpty()) {
            throw new NoChaptersException("文件内容为空");
        }
        return r;
    }

    public static Result singleChapter(String text, String title) {
        Result r = new Result();
        String content = text.replace("\r\n", "\n").replace('\r', '\n').trim();
        if (content.isEmpty()) {
            throw new NoChaptersException("文件内容为空");
        }
        r.titles.add(title == null || title.isBlank() ? "全文" : title);
        r.contents.add(content);
        return r;
    }

    private static Result splitByHeading(String text, LineMatcher matcher, int minHeadings) {
        String normalized = text.replace("\r\n", "\n").replace('\r', '\n');
        String[] lines = normalized.split("\n", -1);
        List<Integer> starts = new ArrayList<>();
        for (int i = 0; i < lines.length; i++) {
            if (matcher.isHeading(lines[i])) {
                starts.add(i);
            }
        }
        if (starts.size() < minHeadings) {
            throw new NoChaptersException("未识别到足够的章节标题");
        }

        Result r = new Result();
        String prefix = join(lines, 0, starts.get(0)).trim();
        if (!prefix.isEmpty()) {
            r.titles.add("序");
            r.contents.add(prefix);
        }
        for (int i = 0; i < starts.size(); i++) {
            int from = starts.get(i);
            int to = i + 1 < starts.size() ? starts.get(i + 1) : lines.length;
            String title = lines[from].trim();
            String content = join(lines, from + 1, to).trim();
            r.titles.add(title);
            r.contents.add(content);
        }
        return r;
    }

    private static String join(String[] lines, int from, int to) {
        StringBuilder sb = new StringBuilder();
        for (int i = from; i < to; i++) {
            if (sb.length() > 0) {
                sb.append('\n');
            }
            sb.append(lines[i]);
        }
        return sb.toString();
    }

    @FunctionalInterface
    private interface LineMatcher {
        boolean isHeading(String line);
    }
}
