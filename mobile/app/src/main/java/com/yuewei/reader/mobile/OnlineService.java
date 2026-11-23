package com.yuewei.reader.mobile;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.yuewei.reader.domain.BookDetail;
import com.yuewei.reader.domain.SearchResult;
import com.yuewei.reader.domain.TocChapter;
import com.yuewei.reader.infrastructure.network.Urls;
import com.yuewei.reader.infrastructure.rule.RuleEngine;

import org.jsoup.nodes.Document;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/** 在线书：搜索、详情、目录与正文，直接复用桌面端的 Legado 规则执行器。 */
public class OnlineService {

    public static class Hit {
        public SourceStore.Source source;
        public SearchResult result;
    }

    private static final String USER_AGENT =
            "Mozilla/5.0 (Linux; Android 13) AppleWebKit/537.36 (KHTML, like Gecko) "
                    + "Chrome/120.0 Mobile Safari/537.36";

    private final ObjectMapper mapper = new ObjectMapper();
    private final Map<String, JsonNode> ruleCache = new HashMap<>();

    public List<Hit> search(List<SourceStore.Source> sources, String keyword) throws IOException {
        List<Hit> hits = new ArrayList<>();
        StringBuilder errors = new StringBuilder();
        for (SourceStore.Source source : sources) {
            if (!source.enabled) {
                continue;
            }
            try {
                JsonNode rules = rules(source);
                String template = rules.path("searchUrl").asText("");
                if (template.isBlank()) {
                    continue;
                }
                String url = Urls.absolutize(source.url, template
                        .replace("{{key}}", Urls.encode(keyword))
                        .replace("{{keyword}}", Urls.encode(keyword))
                        .replace("{{page}}", "1"));
                Page page = fetch(url);
                Document doc = RuleEngine.parseHtml(page.body, page.url);
                List<SearchResult> found = RuleEngine.parseSearch(doc, page.url, rules, null);
                for (SearchResult result : found) {
                    result.sourceId = 0;
                    result.sourceName = source.name;
                    Hit hit = new Hit();
                    hit.source = source;
                    hit.result = result;
                    hits.add(hit);
                }
            } catch (Exception e) {
                errors.append(source.name).append("：")
                        .append(e.getMessage() == null ? e.getClass().getSimpleName() : e.getMessage())
                        .append('\n');
            }
        }
        if (hits.isEmpty() && errors.length() > 0) {
            throw new IOException(errors.toString().trim());
        }
        return hits;
    }

    public BookDetail detail(SourceStore.Source source, SearchResult result) throws IOException {
        JsonNode rules = rules(source);
        Page detailPage = fetch(result.bookUrl);
        Document detailDoc = RuleEngine.parseHtml(detailPage.body, detailPage.url);
        BookDetail detail = RuleEngine.parseDetail(detailDoc, detailPage.url, rules, null);
        if (detail == null) {
            throw new IOException("该书源未配置 ruleBookInfo");
        }
        if (detail.title == null || detail.title.isBlank()) {
            detail.title = result.title;
        }
        if (detail.author == null || detail.author.isBlank()) {
            detail.author = result.author;
        }
        detail.bookUrl = result.bookUrl;
        String tocUrl = detail.tocUrl == null || detail.tocUrl.isBlank() ? result.bookUrl : detail.tocUrl;
        Page tocPage = fetch(tocUrl);
        Document tocDoc = RuleEngine.parseHtml(tocPage.body, tocPage.url);
        List<TocChapter> chapters = RuleEngine.parseToc(tocDoc, tocPage.url, rules, null);
        if (chapters.isEmpty()) {
            throw new IOException("目录为空，可能书源规则已失效");
        }
        detail.chapters = chapters;
        return detail;
    }

    public String content(SourceStore.Source source, String chapterUrl) throws IOException {
        JsonNode rules = rules(source);
        String url = chapterUrl;
        StringBuilder text = new StringBuilder();
        for (int page = 0; page < 6 && url != null && !url.isBlank(); page++) {
            Page html = fetch(url);
            Document doc = RuleEngine.parseHtml(html.body, html.url);
            String part = RuleEngine.parseContent(doc, html.url, rules, null);
            if (part != null && !part.isBlank()) {
                if (text.length() > 0) {
                    text.append('\n');
                }
                text.append(part);
            }
            url = RuleEngine.nextContentUrl(doc, html.url, rules);
        }
        if (text.length() == 0) {
            throw new IOException("正文解析为空，可能书源规则已失效");
        }
        return text.toString();
    }

    private JsonNode rules(SourceStore.Source source) throws IOException {
        JsonNode cached = ruleCache.get(source.url);
        if (cached == null) {
            cached = mapper.readTree(source.ruleJson);
            ruleCache.put(source.url, cached);
        }
        return cached;
    }

    private static class Page {
        byte[] body;
        String url;
    }

    private Page fetch(String rawUrl) throws IOException {
        String target = rawUrl.replace(" ", "%20");
        HttpURLConnection connection = (HttpURLConnection) new URL(target).openConnection();
        connection.setConnectTimeout(15000);
        connection.setReadTimeout(20000);
        connection.setRequestProperty("User-Agent", USER_AGENT);
        connection.setInstanceFollowRedirects(true);
        boolean reusable = false;
        try {
            int status = connection.getResponseCode();
            if (status / 100 != 2) {
                throw new IOException("HTTP " + status + " · " + target);
            }
            Page page = new Page();
            page.url = connection.getURL().toString();
            try (InputStream in = connection.getInputStream()) {
                ByteArrayOutputStream out = new ByteArrayOutputStream();
                byte[] buffer = new byte[16384];
                int read;
                while ((read = in.read(buffer)) >= 0) {
                    out.write(buffer, 0, read);
                }
                page.body = out.toByteArray();
            }
            // 流已读完并关闭：不 disconnect，让 HttpURLConnection 把连接放回 keep-alive 池复用
            reusable = true;
            return page;
        } finally {
            // 只有完整读完的成功响应才复用；异常或非 2xx 直接断开，避免把坏连接放回池
            if (!reusable) {
                connection.disconnect();
            }
        }
    }
}
