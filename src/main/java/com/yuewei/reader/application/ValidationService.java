package com.yuewei.reader.application;

import com.fasterxml.jackson.databind.JsonNode;
import com.yuewei.reader.domain.BookDetail;
import com.yuewei.reader.domain.BookSource;
import com.yuewei.reader.domain.SearchResult;
import com.yuewei.reader.domain.SourceStatus;
import com.yuewei.reader.domain.SupportLevel;
import com.yuewei.reader.domain.TocChapter;
import com.yuewei.reader.infrastructure.db.BookSourceDao;
import com.yuewei.reader.infrastructure.network.HttpGateway;
import com.yuewei.reader.infrastructure.rule.RuleEngine;
import com.yuewei.reader.infrastructure.rule.SourceSupport;
import org.jsoup.nodes.Document;

import java.util.List;

/** 书源四段链路校验，结果同时用于调试面板。 */
public class ValidationService {

    private final HttpGateway http;
    private final BookSourceDao sourceDao;

    public ValidationService(HttpGateway http, BookSourceDao sourceDao) {
        this.http = http;
        this.sourceDao = sourceDao;
    }

    public ValidationReport validate(BookSource source, String keyword) {
        BookSource src = sourceDao.findById(source.id);
        if (src == null) {
            throw new AppException("书源不存在");
        }
        ValidationReport report = new ValidationReport();
        report.sourceName = src.name;
        report.keyword = keyword;
        long start = System.currentTimeMillis();

        JsonNode rules;
        try {
            rules = RuleJson.parse(src.ruleJson);
        } catch (AppException e) {
            sourceDao.updateCheck(src.id, SourceStatus.ERROR, src.support, e.getMessage());
            throw e;
        }
        SourceSupport.Result support = SourceSupport.analyze(rules);
        if (support.level() == SupportLevel.UNSUPPORTED) {
            ValidationReport.Stage stage = new ValidationReport.Stage();
            stage.name = "兼容性";
            stage.message = "失败：" + support.note();
            report.stages.add(stage);
            report.ok = false;
            report.elapsedMs = System.currentTimeMillis() - start;
            sourceDao.updateCheck(src.id, SourceStatus.UNSUPPORTED, support.level(), support.note());
            return report;
        }

        SearchResult picked = null;
        BookDetail detail = null;
        List<TocChapter> chapters = List.of();
        boolean contentOk = false;
        boolean failed = false;

        ValidationReport.Stage searchStage = new ValidationReport.Stage();
        searchStage.name = "搜索";
        try {
            searchStage.url = SourceUrls.search(src, rules, keyword, 1);
            HttpGateway.Response resp = http.get(searchStage.url);
            fill(searchStage, resp);
            Document doc = RuleEngine.parseHtml(resp.body, resp.finalUrl);
            RuleEngine.Stats stats = new RuleEngine.Stats();
            List<SearchResult> results = RuleEngine.parseSearch(doc, resp.finalUrl, rules, stats);
            searchStage.fields.putAll(stats.fields);
            searchStage.sample = sample(doc);
            if (results.isEmpty()) {
                searchStage.message = "失败：搜索规则未匹配到结果";
                failed = true;
            } else {
                searchStage.message = "通过 · " + results.size() + " 条";
                picked = results.get(0);
            }
        } catch (Exception e) {
            searchStage.message = "失败：" + msg(e);
            failed = true;
        }
        report.stages.add(searchStage);

        if (!failed) {
            ValidationReport.Stage detailStage = new ValidationReport.Stage();
            detailStage.name = "详情";
            try {
                detailStage.url = picked.bookUrl;
                HttpGateway.Response resp = http.get(picked.bookUrl);
                fill(detailStage, resp);
                Document doc = RuleEngine.parseHtml(resp.body, resp.finalUrl);
                RuleEngine.Stats stats = new RuleEngine.Stats();
                detail = RuleEngine.parseDetail(doc, resp.finalUrl, rules, stats);
                detailStage.sample = sample(doc);
                detailStage.fields.putAll(stats.fields);
                if (detail == null || detail.title == null || detail.title.isBlank()) {
                    detailStage.message = "失败：详情页未解析到书名";
                    failed = true;
                } else {
                    detailStage.message = "通过 · " + detail.title;
                }
            } catch (Exception e) {
                detailStage.message = "失败：" + msg(e);
                failed = true;
            }
            report.stages.add(detailStage);
        }

        if (!failed) {
            ValidationReport.Stage tocStage = new ValidationReport.Stage();
            tocStage.name = "目录";
            try {
                String tocUrl = detail.tocUrl == null || detail.tocUrl.isBlank() ? picked.bookUrl : detail.tocUrl;
                tocStage.url = tocUrl;
                HttpGateway.Response resp = http.get(tocUrl);
                fill(tocStage, resp);
                Document doc = RuleEngine.parseHtml(resp.body, resp.finalUrl);
                chapters = RuleEngine.parseToc(doc, resp.finalUrl, rules, null);
                tocStage.sample = sample(doc);
                if (chapters.isEmpty()) {
                    tocStage.message = "失败：目录规则匹配到 0 个章节";
                    failed = true;
                } else {
                    tocStage.message = "通过 · " + chapters.size() + " 章";
                    tocStage.fields.put("首章", chapters.get(0).title);
                }
            } catch (Exception e) {
                tocStage.message = "失败：" + msg(e);
                failed = true;
            }
            report.stages.add(tocStage);
        }

        if (!failed) {
            ValidationReport.Stage contentStage = new ValidationReport.Stage();
            contentStage.name = "正文";
            try {
                String chapterUrl = chapters.get(0).url;
                if (chapterUrl == null || chapterUrl.isBlank()) {
                    contentStage.message = "失败：首章缺少正文地址";
                    failed = true;
                } else {
                    contentStage.url = chapterUrl;
                    HttpGateway.Response resp = http.get(chapterUrl);
                    fill(contentStage, resp);
                    Document doc = RuleEngine.parseHtml(resp.body, resp.finalUrl);
                    RuleEngine.Stats stats = new RuleEngine.Stats();
                    String text = RuleEngine.parseContent(doc, resp.finalUrl, rules, stats);
                    contentStage.fields.putAll(stats.fields);
                    contentStage.sample = sample(doc);
                    if (text.isBlank()) {
                        contentStage.message = "失败：正文规则解析为空";
                        failed = true;
                    } else {
                        contentStage.message = "通过 · " + text.length() + " 字";
                        contentOk = true;
                    }
                }
            } catch (Exception e) {
                contentStage.message = "失败：" + msg(e);
                failed = true;
            }
            report.stages.add(contentStage);
        }

        if (failed) {
            for (String name : new String[]{"详情", "目录", "正文"}) {
                if (report.stages.stream().noneMatch(s -> s.name.equals(name))) {
                    ValidationReport.Stage skipped = new ValidationReport.Stage();
                    skipped.name = name;
                    skipped.skipped = true;
                    skipped.message = "未执行";
                    report.stages.add(skipped);
                }
            }
        }

        report.ok = !failed && contentOk;
        report.elapsedMs = System.currentTimeMillis() - start;
        SourceStatus status = report.ok
                ? (support.level() == SupportLevel.PARTIAL ? SourceStatus.PARTIAL : SourceStatus.OK)
                : SourceStatus.ERROR;
        String error = report.ok ? null : report.firstFailure();
        sourceDao.updateCheck(src.id, status, support.level(), error);
        return report;
    }

    private void fill(ValidationReport.Stage stage, HttpGateway.Response resp) {
        stage.status = resp.status;
        stage.millis = resp.millis;
        stage.url = resp.finalUrl;
    }

    private String sample(Document doc) {
        String text = doc.text().trim();
        return text.length() > 300 ? text.substring(0, 300) : text;
    }

    private String msg(Exception e) {
        return e.getMessage() == null ? e.getClass().getSimpleName() : e.getMessage();
    }
}
