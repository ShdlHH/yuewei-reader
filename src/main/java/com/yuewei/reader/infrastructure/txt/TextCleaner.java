package com.yuewei.reader.infrastructure.txt;

import org.jsoup.Jsoup;
import org.jsoup.nodes.Document;

import java.util.regex.Pattern;

/** 正文清洗：HTML 转文本、空白规整、广告行过滤。 */
public final class TextCleaner {

    public static final int MAX_CHARS = 200_000;

    private static final Pattern AD_LINE = Pattern.compile(
            "(?i)(一秒记住|笔趣阁|本章未完|请收藏本站|手机版阅读网址|记住本站|首发地址|无弹窗|"
                    + "最新章节！|www\\.[a-z0-9-]+|https?://|请记住本书)");
    private static final Pattern BLANK = Pattern.compile("[ \\t\\u00A0\\u3000]*");
    private static final Pattern ZERO_WIDTH = Pattern.compile("[\\u200B-\\u200F\\uFEFF]");

    private TextCleaner() {
    }

    /** 从正文 HTML 片段提取可读文本，保留段落换行。 */
    public static String fromHtml(String html) {
        if (html == null || html.isBlank()) {
            return "";
        }
        String withBreaks = html
                .replaceAll("(?i)<br\\s*/?>", "\n")
                .replaceAll("(?i)</(p|div|h[1-6]|li|tr)>", "\n")
                .replaceAll("(?i)<script[^>]*>.*?</script>", "")
                .replaceAll("(?i)<style[^>]*>.*?</style>", "");
        Document doc = Jsoup.parseBodyFragment(withBreaks);
        doc.outputSettings().prettyPrint(false);
        return clean(doc.body().wholeText(), true);
    }

    /** 本地 TXT：只规整空白，不过滤疑似内容。 */
    public static String cleanLocal(String text) {
        return clean(text, false);
    }

    public static String clean(String text, boolean filterAds) {
        if (text == null) {
            return "";
        }
        String normalized = text.replace("\r\n", "\n").replace('\r', '\n')
                .replace('\u00A0', ' ')
                .replace("\u3000", "  ");
        normalized = ZERO_WIDTH.matcher(normalized).replaceAll("");

        String[] lines = normalized.split("\n", -1);
        StringBuilder sb = new StringBuilder();
        boolean lastBlank = true;
        for (String raw : lines) {
            String line = raw.strip();
            if (line.isEmpty() || BLANK.matcher(line).matches()) {
                if (!lastBlank) {
                    sb.append('\n');
                    lastBlank = true;
                }
                continue;
            }
            if (filterAds && line.length() < 40 && AD_LINE.matcher(line).find()) {
                continue;
            }
            if (sb.length() > 0 && !lastBlank) {
                sb.append('\n');
            }
            sb.append(line);
            lastBlank = false;
        }
        String result = sb.toString().strip();
        if (result.length() > MAX_CHARS) {
            result = result.substring(0, MAX_CHARS);
        }
        return result;
    }
}
