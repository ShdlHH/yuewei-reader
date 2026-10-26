package com.yuewei.reader.domain;

import java.util.ArrayList;
import java.util.List;

/** 在线书详情 + 目录。 */
public class BookDetail {
    public String title;
    public String author;
    public String coverUrl;
    public String intro;
    public String kind;
    public String lastChapter;
    public String tocUrl;
    public String bookUrl;
    public long sourceId;
    public String sourceName;
    public List<TocChapter> chapters = new ArrayList<>();
}
