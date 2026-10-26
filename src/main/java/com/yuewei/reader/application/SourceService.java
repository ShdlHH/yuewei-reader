package com.yuewei.reader.application;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.yuewei.reader.domain.BookSource;
import com.yuewei.reader.domain.SourceStatus;
import com.yuewei.reader.domain.SupportLevel;
import com.yuewei.reader.infrastructure.db.BookSourceDao;
import com.yuewei.reader.infrastructure.rule.SourceSupport;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/** 书源导入、编辑、启停、导出。 */
public class SourceService {

    public static final String TEMPLATE = """
            {
              "bookSourceName": "新书源",
              "bookSourceUrl": "https://example.com",
              "bookSourceGroup": "默认",
              "bookSourceType": 0,
              "enabled": true,
              "searchUrl": "https://example.com/search?q={{key}}&page={{page}}",
              "ruleSearch": {
                "bookList": "class.result-list.0@tag.li",
                "name": "tag.h3.0@text",
                "author": "class.author.0@text",
                "bookUrl": "tag.a.0@href",
                "coverUrl": "tag.img.0@src",
                "lastChapter": "class.last.0@text"
              },
              "ruleBookInfo": {
                "name": "tag.h1.0@text",
                "author": "class.author.0@text",
                "intro": "id.intro.0@text",
                "tocUrl": "class.toc.0@href"
              },
              "ruleToc": {
                "chapterList": "id.list.0@tag.a",
                "chapterName": "text",
                "chapterUrl": "href"
              },
              "ruleContent": {
                "content": "id.content.0@text"
              }
            }
            """;

    private final BookSourceDao dao;

    public SourceService(BookSourceDao dao) {
        this.dao = dao;
    }

    public List<BookSource> findAll() {
        return dao.findAll();
    }

    public List<BookSource> findSearchable() {
        return dao.findSearchable();
    }

    public BookSource findById(long id) {
        return dao.findById(id);
    }

    public ImportReport importJson(String json, boolean overwrite) {
        JsonNode root = RuleJson.parse(json);
        List<JsonNode> nodes = new ArrayList<>();
        if (root.isArray()) {
            root.forEach(nodes::add);
        } else if (root.isObject()) {
            nodes.add(root);
        } else {
            throw new AppException("不是有效的书源文件：顶层必须是对象或数组");
        }

        ImportReport report = new ImportReport();
        for (JsonNode node : nodes) {
            String name = node.path("bookSourceName").asText("").trim();
            String url = normalizeUrl(node.path("bookSourceUrl").asText(""));
            if (name.isEmpty() || url.isEmpty()) {
                report.failed++;
                report.items.add(new ImportReport.Item(name, url, "失败", "缺少 bookSourceName 或 bookSourceUrl"));
                continue;
            }
            SourceSupport.Result support = SourceSupport.analyze(node);
            BookSource existing = dao.findByUrl(url);
            if (existing != null) {
                report.duplicates++;
                if (!overwrite) {
                    report.skipped++;
                    report.items.add(new ImportReport.Item(name, url, "跳过", "已存在相同地址的书源"));
                    continue;
                }
                applyJson(existing, node, support);
                existing.ruleJson = node.toString();
                existing.lastCheck = null;
                existing.lastError = null;
                existing.status = initialStatus(support, existing.enabled);
                dao.update(existing);
                report.added++;
                report.items.add(new ImportReport.Item(name, url, "覆盖", support.note()));
                continue;
            }
            BookSource src = new BookSource();
            src.name = name;
            src.url = url;
            applyJson(src, node, support);
            src.ruleJson = node.toString();
            dao.insert(src);
            report.added++;
            report.items.add(new ImportReport.Item(name, url, "新增", support.note()));
        }
        return report;
    }

    public void saveEdited(long id, String json) {
        JsonNode node = RuleJson.parse(json);
        if (!node.isObject()) {
            throw new AppException("书源必须是一个 JSON 对象");
        }
        String name = node.path("bookSourceName").asText("").trim();
        String url = normalizeUrl(node.path("bookSourceUrl").asText(""));
        if (name.isEmpty() || url.isEmpty()) {
            throw new AppException("bookSourceName 与 bookSourceUrl 不能为空");
        }
        BookSource existing = dao.findById(id);
        if (existing == null) {
            throw new AppException("书源不存在");
        }
        BookSource duplicate = dao.findByUrl(url);
        if (duplicate != null && duplicate.id != id) {
            throw new AppException("该地址已被其他书源使用");
        }
        SourceSupport.Result support = SourceSupport.analyze(node);
        applyJson(existing, node, support);
        existing.ruleJson = node.toString();
        existing.status = initialStatus(support, existing.enabled);
        existing.lastCheck = null;
        existing.lastError = null;
        dao.update(existing);
    }

    public void setEnabled(long id, boolean enabled) {
        dao.setEnabled(id, enabled);
    }

    public void delete(long id) {
        dao.delete(id);
    }

    public int countBooks(long sourceId) {
        return dao.countBooks(sourceId);
    }

    public String exportJson() {
        ArrayNode array = RuleJson.MAPPER.createArrayNode();
        for (BookSource src : dao.findAll()) {
            try {
                array.add(RuleJson.parse(src.ruleJson));
            } catch (AppException ignored) {
                // 损坏的书源不参与导出
            }
        }
        return array.toString();
    }

    public String template() {
        return TEMPLATE;
    }

    private void applyJson(BookSource src, JsonNode node, SourceSupport.Result support) {
        src.name = node.path("bookSourceName").asText("").trim();
        src.url = normalizeUrl(node.path("bookSourceUrl").asText(""));
        src.group = node.path("bookSourceGroup").asText("");
        src.type = node.path("bookSourceType").asInt(0);
        src.support = support.level();
        src.compatNote = support.note();
        boolean defaultEnabled = support.level() == SupportLevel.FULL
                && node.path("enabled").asBoolean(true);
        src.enabled = defaultEnabled;
        src.status = initialStatus(support, src.enabled);
    }

    private SourceStatus initialStatus(SourceSupport.Result support, boolean enabled) {
        if (support.level() == SupportLevel.UNSUPPORTED) {
            return SourceStatus.UNSUPPORTED;
        }
        if (support.level() == SupportLevel.PARTIAL) {
            return SourceStatus.PARTIAL;
        }
        return SourceStatus.UNCHECKED;
    }

    public static String normalizeUrl(String url) {
        String value = url == null ? "" : url.trim();
        while (value.endsWith("/") && value.length() > 8) {
            value = value.substring(0, value.length() - 1);
        }
        return value;
    }

    public static String dedupeKey(String url) {
        String value = normalizeUrl(url);
        try {
            java.net.URI uri = java.net.URI.create(value);
            String host = uri.getHost() == null ? "" : uri.getHost().toLowerCase(Locale.ROOT);
            String path = uri.getPath() == null ? "" : uri.getPath();
            return (uri.getScheme() == null ? "" : uri.getScheme().toLowerCase(Locale.ROOT)) + "://" + host + path;
        } catch (Exception e) {
            return value.toLowerCase(Locale.ROOT);
        }
    }
}
