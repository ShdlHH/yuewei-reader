package com.yuewei.reader.domain;

/** 书源兼容级别。 */
public enum SupportLevel {
    FULL("完全支持"),
    PARTIAL("部分支持"),
    UNSUPPORTED("不支持");

    public final String label;

    SupportLevel(String label) {
        this.label = label;
    }
}
