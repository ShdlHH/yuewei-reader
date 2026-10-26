package com.yuewei.reader.domain;

/** 章节正文缓存状态。 */
public enum ContentState {
    NOT_CACHED("未缓存"),
    CACHED("已缓存"),
    FAILED("失败");

    public final String label;

    ContentState(String label) {
        this.label = label;
    }
}
