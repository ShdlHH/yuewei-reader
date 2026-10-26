package com.yuewei.reader.domain;

/** 一本书内的章节。 */
public class Chapter {
    public long id;
    public long bookId;
    public int idx;
    public String title;
    /** 在线章节地址，本地章节为空。 */
    public String url;
    public ContentState state = ContentState.NOT_CACHED;
    public int wordCount;

    @Override
    public String toString() {
        return title == null ? ("第 " + (idx + 1) + " 章") : title;
    }
}
