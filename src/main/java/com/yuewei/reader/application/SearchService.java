package com.yuewei.reader.application;

import com.fasterxml.jackson.databind.JsonNode;
import com.yuewei.reader.domain.BookSource;
import com.yuewei.reader.domain.SearchResult;
import com.yuewei.reader.infrastructure.network.HttpGateway;
import com.yuewei.reader.infrastructure.rule.RuleEngine;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;

/** 多书源并行搜索与结果聚合。 */
public class SearchService {

    private final HttpGateway http;
    private final SettingsService settings;

    public SearchService(HttpGateway http, SettingsService settings) {
        this.http = http;
        this.settings = settings;
    }

    public SearchOutcome search(String keyword, List<BookSource> sources, int page,
                                Consumer<SearchOutcome.SourceProgress> listener) {
        SearchOutcome outcome = new SearchOutcome();
        if (keyword == null || keyword.isBlank()) {
            throw new AppException("请输入书名或作者");
        }
        if (sources.isEmpty()) {
            throw new AppException("没有可用书源，请先在书源页导入并校验");
        }
        long start = System.currentTimeMillis();
        ExecutorService pool = Executors.newFixedThreadPool(Math.min(settings.searchConcurrency(), sources.size()));
        List<Future<?>> futures = new ArrayList<>();
        List<SearchResult> results = Collections.synchronizedList(new ArrayList<>());
        List<SearchOutcome.SourceProgress> progress = Collections.synchronizedList(new ArrayList<>());

        for (BookSource source : sources) {
            Callable<Void> task = () -> {
                listener.accept(new SearchOutcome.SourceProgress(source.name, "搜索中", 0, ""));
                try {
                    JsonNode rules = RuleJson.parse(source.ruleJson);
                    String url = SourceUrls.search(source, rules, keyword, page);
                    HttpGateway.Response resp = http.get(url);
                    var doc = RuleEngine.parseHtml(resp.body, resp.finalUrl);
                    List<SearchResult> found = RuleEngine.parseSearch(doc, resp.finalUrl, rules, null);
                    for (SearchResult r : found) {
                        r.sourceId = source.id;
                        r.sourceName = source.name;
                    }
                    results.addAll(found);
                    progress.add(new SearchOutcome.SourceProgress(source.name, "完成", found.size(), ""));
                    listener.accept(new SearchOutcome.SourceProgress(source.name, "完成", found.size(), ""));
                } catch (Exception e) {
                    String msg = e.getMessage() == null ? e.getClass().getSimpleName() : e.getMessage();
                    progress.add(new SearchOutcome.SourceProgress(source.name, "失败", 0, msg));
                    outcome.failures.add(source.name + "：" + msg);
                    listener.accept(new SearchOutcome.SourceProgress(source.name, "失败", 0, msg));
                }
                return null;
            };
            futures.add(pool.submit(task));
        }

        for (Future<?> f : futures) {
            try {
                f.get(90, TimeUnit.SECONDS);
            } catch (Exception ignored) {
                // 超时的书源已在任务内或这里被记入失败
            }
        }
        pool.shutdownNow();

        outcome.results.addAll(results);
        outcome.progress.addAll(progress);
        outcome.elapsedMs = System.currentTimeMillis() - start;
        return outcome;
    }

    public String previewSearchUrl(BookSource source, String keyword, int page) {
        JsonNode rules = RuleJson.parse(source.ruleJson);
        return SourceUrls.search(source, rules, keyword, page);
    }
}
