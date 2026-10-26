package com.yuewei.reader.domain;

import java.util.ArrayList;
import java.util.List;

/** 一次批量缓存任务。 */
public class DownloadTask {
    public long id;
    public long bookId;
    public String bookTitle;
    public DownloadRangeType rangeType = DownloadRangeType.ALL;
    public Integer fromIndex;
    public Integer toIndex;
    public DownloadState state = DownloadState.QUEUED;
    public int successCount;
    public int failCount;
    public List<Integer> failedIndexes = new ArrayList<>();
    public String createdAt;
    public String updatedAt;

    @Override
    public String toString() {
        return (bookTitle == null ? "" : bookTitle);
    }
}
