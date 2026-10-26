package com.yuewei.reader.application;

import com.yuewei.reader.domain.AggregatedResult;
import com.yuewei.reader.domain.SearchResult;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** 一次聚合搜索的结果与逐源状态。 */
public class SearchOutcome {
    public final List<SearchResult> results = new ArrayList<>();
    public final List<SourceProgress> progress = new ArrayList<>();
    public final List<String> failures = new ArrayList<>();
    public long elapsedMs;

    public List<AggregatedResult> aggregate() {
        Map<String, AggregatedResult> map = new LinkedHashMap<>();
        for (SearchResult r : results) {
            AggregatedResult a = map.computeIfAbsent(r.key(), k -> {
                AggregatedResult item = new AggregatedResult();
                item.title = r.title;
                item.author = r.author;
                return item;
            });
            a.sources.add(r);
            if (a.lastChapter == null || a.lastChapter.isBlank()) {
                a.lastChapter = r.lastChapter;
            }
        }
        List<AggregatedResult> list = new ArrayList<>(map.values());
        list.sort(Comparator.comparingInt((AggregatedResult a) -> -a.sources.size()));
        return list;
    }

    public static class SourceProgress {
        public final String sourceName;
        public final String state;
        public final int count;
        public final String message;

        public SourceProgress(String sourceName, String state, int count, String message) {
            this.sourceName = sourceName;
            this.state = state;
            this.count = count;
            this.message = message;
        }

        @Override
        public String toString() {
            return sourceName + " · " + state + (count > 0 ? " · " + count + " 条" : "")
                    + (message == null || message.isBlank() ? "" : " · " + message);
        }
    }
}
