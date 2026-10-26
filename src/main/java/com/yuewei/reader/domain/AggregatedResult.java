package com.yuewei.reader.domain;

import java.util.ArrayList;
import java.util.List;

/** 按「书名 + 作者」聚合后的搜索结果。 */
public class AggregatedResult {
    public String title;
    public String author;
    public String lastChapter;
    public List<SearchResult> sources = new ArrayList<>();

    public String sourceSummary() {
        List<String> names = new ArrayList<>();
        for (SearchResult r : sources) {
            names.add(r.sourceName);
        }
        return String.join("、", names);
    }
}
