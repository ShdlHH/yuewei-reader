package com.yuewei.reader.infrastructure.rule;

import com.fasterxml.jackson.databind.JsonNode;
import com.yuewei.reader.domain.BookDetail;
import com.yuewei.reader.domain.SearchResult;
import com.yuewei.reader.domain.TocChapter;
import com.yuewei.reader.infrastructure.network.HttpGateway;
import com.yuewei.reader.infrastructure.txt.TextCleaner;
import org.jsoup.Jsoup;
import org.jsoup.nodes.Document;
import org.jsoup.nodes.Element;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;

/**
 * Legado 3.0 书源的规则执行器（子集）。
 * 支持 class / tag / id 选择、text / ownText / href / src / html / attr 取值、
 * {{key}} / {{page}} 变量、##正则##替换；不支持 JS 规则与登录态。
 */
public class RuleEngine {

    /** 解析统计，用于书源调试面板。 */
    public static class Stats {
        public int matched;
        public final Map<String, String> fields = new LinkedHashMap<>();
    }

    private RuleEngine() {
    }

    public static Document parseHtml(byte[] body, String baseUrl) {
        try {
            return Jsoup.parse(new ByteArrayInputStream(body == null ? new byte[0] : body), null, baseUrl);
        } catch (IOException e) {
            return Jsoup.parse("", baseUrl);
        }
    }

    public static Document parseHtml(String html, String baseUrl) {
        return Jsoup.parse(html == null ? "" : html, baseUrl);
    }

    // ---------------------------------------------------------------- 搜索

    public static List<SearchResult> parseSearch(Document doc, String pageUrl, JsonNode rules, Stats stats) {
        JsonNode rs = rules == null ? null : rules.get("ruleSearch");
        if (rs == null || rs.isNull()) {
            return List.of();
        }
        List<Element> items = selectElements(doc.body(), str(rs, "bookList"));
        if (stats != null) {
            stats.matched = items.size();
        }
        List<SearchResult> results = new ArrayList<>();
        for (Element item : items) {
            String title = firstText(item, str(rs, "name"), pageUrl);
            if (title.isBlank()) {
                continue;
            }
            SearchResult r = new SearchResult();
            r.title = title;
            r.author = firstText(item, str(rs, "author"), pageUrl);
            r.coverUrl = firstUrl(item, str(rs, "coverUrl"), pageUrl);
            r.intro = firstText(item, str(rs, "intro"), pageUrl);
            r.lastChapter = firstText(item, str(rs, "lastChapter"), pageUrl);
            r.bookUrl = firstUrl(item, str(rs, "bookUrl"), pageUrl);
            results.add(r);
            if (stats != null && results.size() == 1) {
                stats.fields.put("书名", r.title);
                stats.fields.put("作者", nullToEmpty(r.author));
                stats.fields.put("书籍地址", nullToEmpty(r.bookUrl));
            }
        }
        return results;
    }

    public static List<SearchResult> parseSearch(String html, String pageUrl, JsonNode rules) {
        return parseSearch(parseHtml(html, pageUrl), pageUrl, rules, null);
    }

    // ---------------------------------------------------------------- 详情

    public static BookDetail parseDetail(Document doc, String pageUrl, JsonNode rules, Stats stats) {
        JsonNode bi = rules == null ? null : rules.get("ruleBookInfo");
        if (bi == null || bi.isNull()) {
            return null;
        }
        BookDetail d = new BookDetail();
        d.bookUrl = pageUrl;
        d.title = firstText(doc.body(), str(bi, "name"), pageUrl);
        d.author = firstText(doc.body(), str(bi, "author"), pageUrl);
        d.coverUrl = firstUrl(doc.body(), str(bi, "coverUrl"), pageUrl);
        d.intro = firstText(doc.body(), str(bi, "intro"), pageUrl);
        d.kind = firstText(doc.body(), str(bi, "kind"), pageUrl);
        d.lastChapter = firstText(doc.body(), str(bi, "lastChapter"), pageUrl);
        String tocRule = str(bi, "tocUrl");
        d.tocUrl = tocRule.isBlank() ? null : firstUrl(doc.body(), tocRule, pageUrl);
        if (stats != null) {
            stats.matched = d.title.isBlank() ? 0 : 1;
            stats.fields.put("书名", nullToEmpty(d.title));
            stats.fields.put("作者", nullToEmpty(d.author));
            stats.fields.put("目录地址", nullToEmpty(d.tocUrl));
        }
        return d;
    }

    // ---------------------------------------------------------------- 目录

    public static List<TocChapter> parseToc(Document doc, String pageUrl, JsonNode rules, Stats stats) {
        JsonNode toc = rules == null ? null : rules.get("ruleToc");
        if (toc == null || toc.isNull()) {
            return List.of();
        }
        List<Element> items = selectElements(doc.body(), str(toc, "chapterList"));
        List<TocChapter> chapters = new ArrayList<>();
        int index = 0;
        for (Element item : items) {
            String title = firstText(item, str(toc, "chapterName"), pageUrl);
            String url = firstUrl(item, str(toc, "chapterUrl"), pageUrl);
            if (title.isBlank() && url == null) {
                continue;
            }
            chapters.add(new TocChapter(index++, title.isBlank() ? "第 " + (index) + " 章" : title, url));
        }
        if (stats != null) {
            stats.matched = chapters.size();
            if (!chapters.isEmpty()) {
                stats.fields.put("首章", chapters.get(0).title);
            }
        }
        return chapters;
    }

    // ---------------------------------------------------------------- 正文

    public static String parseContent(Document doc, String pageUrl, JsonNode rules, Stats stats) {
        JsonNode rc = rules == null ? null : rules.get("ruleContent");
        if (rc == null || rc.isNull()) {
            return "";
        }
        String rule = str(rc, "content");
        List<Element> elements = selectElements(doc.body(), rule);
        String text = "";
        if (!elements.isEmpty()) {
            text = TextCleaner.fromHtml(elements.get(0).html());
        }
        if (text.isBlank()) {
            List<String> values = extract(doc.body(), rule, pageUrl);
            if (!values.isEmpty()) {
                text = TextCleaner.clean(values.get(0), true);
            }
        }
        if (stats != null) {
            stats.matched = text.isBlank() ? 0 : 1;
            stats.fields.put("正文长度", text.length() + " 字");
        }
        return text;
    }

    /** 正文分页时取下一页地址；无此规则返回 null。 */
    public static String nextContentUrl(Document doc, String pageUrl, JsonNode rules) {
        JsonNode rc = rules == null ? null : rules.get("ruleContent");
        if (rc == null || rc.isNull()) {
            return null;
        }
        String rule = str(rc, "nextContentUrl");
        if (rule.isBlank()) {
            return null;
        }
        String url = firstUrl(doc.body(), rule, pageUrl);
        if (url == null || url.isBlank() || url.equals(pageUrl)) {
            return null;
        }
        return url;
    }

    // ---------------------------------------------------------------- 选择与取值

    public static List<Element> selectElements(Element root, String rule) {
        Parsed parsed = parseRule(rule);
        List<Element> current = new ArrayList<>();
        current.add(root);
        for (Step step : parsed.steps) {
            if (step.extractor) {
                break;
            }
            current = applySelect(current, step);
            if (current.isEmpty()) {
                return List.of();
            }
        }
        return current;
    }

    public static List<String> extract(Element root, String rule, String baseUrl) {
        Parsed parsed = parseRule(rule);
        if (parsed.steps.isEmpty()) {
            return List.of();
        }
        List<Element> current = new ArrayList<>();
        current.add(root);
        List<String> values = new ArrayList<>();
        Step last = null;
        for (Step step : parsed.steps) {
            if (!step.extractor) {
                current = applySelect(current, step);
                if (current.isEmpty()) {
                    return List.of();
                }
            } else {
                List<String> found = new ArrayList<>();
                for (Element el : current) {
                    String value = extractOne(el, step, baseUrl);
                    if (value != null && !value.isBlank()) {
                        found.add(value);
                    }
                }
                if (step.index >= 0) {
                    found = found.size() > step.index ? List.of(found.get(step.index)) : List.of();
                }
                values = found;
                last = step;
            }
        }
        if (values.isEmpty() && (last == null)) {
            for (Element el : current) {
                values.add(el.text());
            }
        }
        List<String> out = new ArrayList<>();
        for (String v : values) {
            out.add(applyReplacements(v, parsed.replacements));
        }
        return out;
    }

    public static String firstText(Element root, String rule, String baseUrl) {
        List<String> values = extract(root, rule, baseUrl);
        return values.isEmpty() ? "" : values.get(0).trim();
    }

    public static String firstUrl(Element root, String rule, String baseUrl) {
        String value = firstText(root, rule, baseUrl);
        if (value.isBlank()) {
            return null;
        }
        return HttpGateway.absolutize(baseUrl, value);
    }

    private static List<Element> applySelect(List<Element> current, Step step) {
        List<Element> out = new ArrayList<>();
        for (Element el : current) {
            switch (step.kind) {
                case CLASS -> {
                    for (Element e : el.getElementsByClass(step.name)) {
                        if (e != el) {
                            out.add(e);
                        }
                    }
                }
                case TAG -> {
                    for (Element e : el.getElementsByTag(step.name)) {
                        if (e != el) {
                            out.add(e);
                        }
                    }
                }
                case ID -> {
                    for (Element e : el.getAllElements()) {
                        if (e != el && step.name.equals(e.id())) {
                            out.add(e);
                        }
                    }
                }
                default -> {
                }
            }
        }
        if (step.index >= 0) {
            return out.size() > step.index ? List.of(out.get(step.index)) : List.of();
        }
        return out;
    }

    private static String extractOne(Element el, Step step, String baseUrl) {
        return switch (step.kind) {
            case TEXT -> el.text();
            case OWN_TEXT -> el.ownText();
            case HTML -> el.html();
            case HREF -> HttpGateway.absolutize(baseUrl, el.attr("href"));
            case SRC -> HttpGateway.absolutize(baseUrl, el.attr("src"));
            case ATTR -> el.attr(step.name);
            default -> null;
        };
    }

    private static String str(JsonNode node, String field) {
        JsonNode v = node == null ? null : node.get(field);
        return v == null || v.isNull() ? "" : v.asText();
    }

    private static String nullToEmpty(String v) {
        return v == null ? "" : v;
    }

    private static String applyReplacements(String value, List<String[]> replacements) {
        String out = value;
        for (String[] pair : replacements) {
            try {
                out = out.replaceAll(pair[0], Matcher.quoteReplacement(pair[1]));
            } catch (Exception ignored) {
                // 非法正则不影响主流程
            }
        }
        return out;
    }

    // ---------------------------------------------------------------- 规则解析

    public enum Kind {
        CLASS, TAG, ID, TEXT, OWN_TEXT, HREF, SRC, HTML, ATTR
    }

    public static class Step {
        public Kind kind;
        public String name = "";
        public int index = -1;
        public boolean extractor;

        boolean urlLike() {
            return kind == Kind.HREF || kind == Kind.SRC;
        }
    }

    private static class Parsed {
        final List<Step> steps = new ArrayList<>();
        final List<String[]> replacements = new ArrayList<>();
    }

    private static Parsed parseRule(String rule) {
        Parsed parsed = new Parsed();
        if (rule == null || rule.isBlank()) {
            return parsed;
        }
        String selector = rule;
        int replaceAt = rule.indexOf("##");
        if (replaceAt >= 0) {
            selector = rule.substring(0, replaceAt);
            String rest = rule.substring(replaceAt + 2);
            String[] parts = rest.split("##", -1);
            for (int i = 0; i + 1 < parts.length; i += 2) {
                parsed.replacements.add(new String[]{parts[i], parts[i + 1]});
            }
        }
        String[] rawSteps = selector.split("@");
        for (String raw : rawSteps) {
            String s = raw.trim();
            if (s.isEmpty()) {
                continue;
            }
            Step step = parseStep(s);
            if (step != null) {
                parsed.steps.add(step);
            }
        }
        return parsed;
    }

    private static Step parseStep(String raw) {
        String s = raw;
        int index = -1;
        int dot = s.lastIndexOf('.');
        if (dot > 0 && dot < s.length() - 1) {
            String tail = s.substring(dot + 1);
            if (tail.matches("-?\\d+")) {
                index = Integer.parseInt(tail);
                s = s.substring(0, dot);
            }
        }
        String lower = s.toLowerCase();
        Step step = new Step();
        step.index = index;
        switch (lower) {
            case "text" -> {
                step.kind = Kind.TEXT;
                step.extractor = true;
                return step;
            }
            case "owntext" -> {
                step.kind = Kind.OWN_TEXT;
                step.extractor = true;
                return step;
            }
            case "href" -> {
                step.kind = Kind.HREF;
                step.extractor = true;
                return step;
            }
            case "src" -> {
                step.kind = Kind.SRC;
                step.extractor = true;
                return step;
            }
            case "html" -> {
                step.kind = Kind.HTML;
                step.extractor = true;
                return step;
            }
            default -> {
            }
        }
        if (lower.startsWith("attr.")) {
            step.kind = Kind.ATTR;
            step.name = s.substring(5);
            step.extractor = true;
            return step;
        }
        int dot2 = s.indexOf('.');
        if (dot2 <= 0 || dot2 == s.length() - 1) {
            return null;
        }
        String type = s.substring(0, dot2);
        String name = s.substring(dot2 + 1);
        switch (type.toLowerCase()) {
            case "class" -> step.kind = Kind.CLASS;
            case "tag" -> step.kind = Kind.TAG;
            case "id" -> step.kind = Kind.ID;
            default -> {
                return null;
            }
        }
        step.name = name;
        return step;
    }
}
