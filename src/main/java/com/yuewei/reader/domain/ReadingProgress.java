package com.yuewei.reader.domain;

/** 阅读进度：章节序号 + 章节内字符偏移。 */
public class ReadingProgress {
    public long bookId;
    public int chapterIndex;
    public int charOffset;
    public String updatedAt;
}
