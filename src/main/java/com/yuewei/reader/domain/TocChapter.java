package com.yuewei.reader.domain;

/** 目录中的一章（尚未入库）。 */
public class TocChapter {
    public int index;
    public String title;
    public String url;

    public TocChapter() {
    }

    public TocChapter(int index, String title, String url) {
        this.index = index;
        this.title = title;
        this.url = url;
    }
}
