package com.yuewei.reader.domain;

/** 书架中的一本书，本地 TXT 与在线书共用此模型。 */
public class Book {
    public long id;
    public String title;
    public String author;
    public String coverUrl;
    public String intro;
    public SourceType sourceType = SourceType.LOCAL;
    public Long sourceId;
    public String bookUrl;
    public int chapterCount;
    public String fileHash;
    public boolean offline;
    public String addedAt;
    public String lastReadAt;

    /** 展示用：书源名称，不落库。 */
    public String sourceName;
    /** 展示用：书源已被删除。 */
    public boolean sourceMissing;

    public boolean isOnline() {
        return sourceType == SourceType.ONLINE;
    }

    @Override
    public String toString() {
        return title == null ? "" : title;
    }
}
