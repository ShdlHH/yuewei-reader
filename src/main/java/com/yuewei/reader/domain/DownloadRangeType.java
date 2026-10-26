package com.yuewei.reader.domain;

/** 下载范围类型。 */
public enum DownloadRangeType {
    ALL("全部章节"),
    UNCACHED("仅未缓存"),
    RANGE("指定区间");

    public final String label;

    DownloadRangeType(String label) {
        this.label = label;
    }
}
