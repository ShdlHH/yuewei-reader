package com.yuewei.reader.domain;

/** 单条搜索结果（归属于一个书源）。 */
public class SearchResult {
    public String title;
    public String author;
    public String coverUrl;
    public String intro;
    public String lastChapter;
    public String bookUrl;
    public long sourceId;
    public String sourceName;

    public String key() {
        return (title == null ? "" : title.trim()) + "|" + (author == null ? "" : author.trim());
    }
}
